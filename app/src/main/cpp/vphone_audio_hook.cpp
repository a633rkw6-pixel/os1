#include "VirtualMicClient.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <shadowhook.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#define LOG_TAG "VPhoneAudioHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace vphone {

// ============================================================================
// PART 1: libaudioclient.so Hooks (Covers ALL Java android.media.AudioRecord
//         and legacy native C++ AudioRecord consumers)
// ============================================================================
static constexpr uint32_t kAudioFormatPcm16Bit = 0x1;
static constexpr uint32_t kAudioFormatPcm8Bit  = 0x2;
static constexpr uint32_t kAudioFormatPcmFloat = 0x5;

using AudioRecordStartFn = int32_t (*)(void*, int32_t, int32_t);
using AudioRecordStopFn  = void (*)(void*);
using AudioRecordReadFn  = ssize_t (*)(void*, void*, size_t, bool);
using AudioRecordGetSampleRateFn = uint32_t (*)(const void*);
using AudioRecordChannelCountFn  = uint32_t (*)(const void*);
using AudioRecordFormatFn        = uint32_t (*)(const void*);

static AudioRecordStartFn orig_AudioRecord_start = nullptr;
static AudioRecordStopFn  orig_AudioRecord_stop  = nullptr;
static AudioRecordReadFn  orig_AudioRecord_read  = nullptr;

static AudioRecordGetSampleRateFn sym_getSampleRate = nullptr;
static AudioRecordChannelCountFn  sym_channelCount  = nullptr;
static AudioRecordFormatFn        sym_format        = nullptr;

struct AudioRecordStreamCtx {
    bool virtualized = false;
    StreamResampleState resample{};
};

static std::mutex gArMapMtx;
static std::unordered_map<void*, AudioRecordStreamCtx> gArStreams;

static int32_t hooked_AudioRecord_start(void* self, int32_t syncEvent, int32_t triggerSession) {
    if (VirtualMicClient::instance().isEnabled()) {
        std::lock_guard<std::mutex> lock(gArMapMtx);
        auto& ctx = gArStreams[self];
        ctx.virtualized = true;
        ctx.resample = StreamResampleState{};
        LOGI("AudioRecord::start(%p) intercepted -> routing from ClearVoice Virtual Mic (no hardware mic conflict)", self);
        return 0; // NO_ERROR
    }
    return orig_AudioRecord_start ? orig_AudioRecord_start(self, syncEvent, triggerSession) : 0;
}

static void hooked_AudioRecord_stop(void* self) {
    bool wasVirt = false;
    {
        std::lock_guard<std::mutex> lock(gArMapMtx);
        auto it = gArStreams.find(self);
        if (it != gArStreams.end()) {
            wasVirt = it->second.virtualized;
            gArStreams.erase(it);
        }
    }
    if (!wasVirt && orig_AudioRecord_stop) {
        orig_AudioRecord_stop(self);
    }
}

static ssize_t hooked_AudioRecord_read(void* self, void* buffer, size_t size, bool blocking) {
    if (!buffer || size == 0) return 0;

    bool shouldVirt = VirtualMicClient::instance().isEnabled();
    if (!shouldVirt) {
        return orig_AudioRecord_read ? orig_AudioRecord_read(self, buffer, size, blocking) : 0;
    }

    uint32_t sampleRate = 48000;
    uint32_t channels = 1;
    uint32_t fmt = kAudioFormatPcm16Bit;

    if (sym_getSampleRate) {
        uint32_t sr = sym_getSampleRate(self);
        if (sr >= 8000 && sr <= 192000) sampleRate = sr;
    }
    if (sym_channelCount) {
        uint32_t ch = sym_channelCount(self);
        if (ch >= 1 && ch <= 8) channels = ch;
    }
    if (sym_format) {
        fmt = sym_format(self);
    }

    StreamResampleState stateCopy{};
    {
        std::lock_guard<std::mutex> lock(gArMapMtx);
        stateCopy = gArStreams[self].resample;
    }

    size_t bytesWritten = 0;
    if (fmt == kAudioFormatPcmFloat) {
        size_t frameBytes = channels * sizeof(float);
        size_t framesReq = size / frameBytes;
        size_t written = VirtualMicClient::instance().readPcmFloat(
            reinterpret_cast<float*>(buffer), framesReq,
            static_cast<int>(sampleRate), static_cast<int>(channels),
            blocking, stateCopy);
        bytesWritten = written * frameBytes;
    } else if (fmt == kAudioFormatPcm8Bit) {
        size_t frameBytes = channels * sizeof(uint8_t);
        size_t framesReq = size / frameBytes;
        std::vector<int16_t> tmp(framesReq * channels);
        size_t written = VirtualMicClient::instance().readPcm16(
            tmp.data(), framesReq,
            static_cast<int>(sampleRate), static_cast<int>(channels),
            blocking, stateCopy);
        uint8_t* out8 = reinterpret_cast<uint8_t*>(buffer);
        for (size_t i = 0; i < written * channels; ++i) {
            out8[i] = static_cast<uint8_t>((tmp[i] >> 8) + 128);
        }
        bytesWritten = written * frameBytes;
    } else {
        // Default: PCM 16-bit
        size_t frameBytes = channels * sizeof(int16_t);
        size_t framesReq = size / frameBytes;
        size_t written = VirtualMicClient::instance().readPcm16(
            reinterpret_cast<int16_t*>(buffer), framesReq,
            static_cast<int>(sampleRate), static_cast<int>(channels),
            blocking, stateCopy);
        bytesWritten = written * frameBytes;
    }

    {
        std::lock_guard<std::mutex> lock(gArMapMtx);
        auto it = gArStreams.find(self);
        if (it != gArStreams.end()) {
            it->second.resample = stateCopy;
        }
    }

    return static_cast<ssize_t>(bytesWritten);
}

// ============================================================================
// PART 2: libaaudio.so Hooks (Covers Oboe, WebRTC, Discord, Games, Chrome)
// ============================================================================
struct AAudioBuilderConfig {
    aaudio_direction_t direction = AAUDIO_DIRECTION_OUTPUT;
    int32_t sampleRate = 48000;
    int32_t channelCount = 1;
    aaudio_format_t format = AAUDIO_FORMAT_PCM_I16;
    AAudioStream_dataCallback dataCallback = nullptr;
    void* userData = nullptr;
};

struct AAudioStreamCtx {
    aaudio_direction_t direction = AAUDIO_DIRECTION_OUTPUT;
    int32_t sampleRate = 48000;
    int32_t channelCount = 1;
    aaudio_format_t format = AAUDIO_FORMAT_PCM_I16;
    AAudioStream_dataCallback dataCallback = nullptr;
    void* userData = nullptr;
    bool virtualized = false;
    std::atomic<bool> callbackRunning{false};
    std::thread callbackThread;
    StreamResampleState resample{};
};

static std::mutex gAaMtx;
static std::unordered_map<AAudioStreamBuilder*, AAudioBuilderConfig> gAaBuilders;
static std::unordered_map<AAudioStream*, std::shared_ptr<AAudioStreamCtx>> gAaStreams;

static void (*orig_AAudioStreamBuilder_setDirection)(AAudioStreamBuilder*, aaudio_direction_t) = nullptr;
static void (*orig_AAudioStreamBuilder_setSampleRate)(AAudioStreamBuilder*, int32_t) = nullptr;
static void (*orig_AAudioStreamBuilder_setChannelCount)(AAudioStreamBuilder*, int32_t) = nullptr;
static void (*orig_AAudioStreamBuilder_setFormat)(AAudioStreamBuilder*, aaudio_format_t) = nullptr;
static void (*orig_AAudioStreamBuilder_setDataCallback)(AAudioStreamBuilder*, AAudioStream_dataCallback, void*) = nullptr;
static aaudio_result_t (*orig_AAudioStreamBuilder_openStream)(AAudioStreamBuilder*, AAudioStream**) = nullptr;
static aaudio_result_t (*orig_AAudioStream_requestStart)(AAudioStream*) = nullptr;
static aaudio_result_t (*orig_AAudioStream_requestStop)(AAudioStream*) = nullptr;
static aaudio_result_t (*orig_AAudioStream_read)(AAudioStream*, void*, int32_t, int64_t) = nullptr;
static aaudio_result_t (*orig_AAudioStream_close)(AAudioStream*) = nullptr;

static void hooked_AAudioStreamBuilder_setDirection(AAudioStreamBuilder* b, aaudio_direction_t dir) {
    {
        std::lock_guard<std::mutex> lock(gAaMtx);
        gAaBuilders[b].direction = dir;
    }
    if (orig_AAudioStreamBuilder_setDirection) orig_AAudioStreamBuilder_setDirection(b, dir);
}

static void hooked_AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder* b, int32_t sr) {
    {
        std::lock_guard<std::mutex> lock(gAaMtx);
        gAaBuilders[b].sampleRate = sr;
    }
    if (orig_AAudioStreamBuilder_setSampleRate) orig_AAudioStreamBuilder_setSampleRate(b, sr);
}

static void hooked_AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder* b, int32_t ch) {
    {
        std::lock_guard<std::mutex> lock(gAaMtx);
        gAaBuilders[b].channelCount = ch;
    }
    if (orig_AAudioStreamBuilder_setChannelCount) orig_AAudioStreamBuilder_setChannelCount(b, ch);
}

static void hooked_AAudioStreamBuilder_setFormat(AAudioStreamBuilder* b, aaudio_format_t fmt) {
    {
        std::lock_guard<std::mutex> lock(gAaMtx);
        gAaBuilders[b].format = fmt;
    }
    if (orig_AAudioStreamBuilder_setFormat) orig_AAudioStreamBuilder_setFormat(b, fmt);
}

static void hooked_AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder* b, AAudioStream_dataCallback cb, void* userData) {
    {
        std::lock_guard<std::mutex> lock(gAaMtx);
        gAaBuilders[b].dataCallback = cb;
        gAaBuilders[b].userData = userData;
    }
    if (orig_AAudioStreamBuilder_setDataCallback) orig_AAudioStreamBuilder_setDataCallback(b, cb, userData);
}

static aaudio_result_t hooked_AAudioStreamBuilder_openStream(AAudioStreamBuilder* b, AAudioStream** streamOut) {
    aaudio_result_t res = orig_AAudioStreamBuilder_openStream ? orig_AAudioStreamBuilder_openStream(b, streamOut) : AAUDIO_ERROR_INTERNAL;
    if (res == AAUDIO_OK && streamOut && *streamOut) {
        std::lock_guard<std::mutex> lock(gAaMtx);
        auto cfg = gAaBuilders[b];
        auto ctx = std::make_shared<AAudioStreamCtx>();
        ctx->direction = cfg.direction;
        ctx->sampleRate = cfg.sampleRate > 0 ? cfg.sampleRate : 48000;
        ctx->channelCount = cfg.channelCount > 0 ? cfg.channelCount : 1;
        ctx->format = cfg.format;
        ctx->dataCallback = cfg.dataCallback;
        ctx->userData = cfg.userData;
        gAaStreams[*streamOut] = ctx;
    }
    return res;
}

static aaudio_result_t hooked_AAudioStream_requestStart(AAudioStream* stream) {
    std::shared_ptr<AAudioStreamCtx> ctx;
    {
        std::lock_guard<std::mutex> lock(gAaMtx);
        auto it = gAaStreams.find(stream);
        if (it != gAaStreams.end()) ctx = it->second;
    }

    if (ctx && ctx->direction == AAUDIO_DIRECTION_INPUT && VirtualMicClient::instance().isEnabled()) {
        ctx->virtualized = true;
        LOGI("AAudio INPUT stream (%p) routed from ClearVoice Virtual Mic", stream);

        if (ctx->dataCallback != nullptr && !ctx->callbackRunning.exchange(true)) {
            ctx->callbackThread = std::thread([stream, ctx]() {
                const int32_t framesPerBurst = std::max(192, ctx->sampleRate / 50); // 20ms burst
                std::vector<int16_t> buf16(static_cast<size_t>(framesPerBurst * ctx->channelCount));
                std::vector<float> bufFloat(static_cast<size_t>(framesPerBurst * ctx->channelCount));

                while (ctx->callbackRunning.load()) {
                    void* audioPtr = nullptr;
                    size_t got = 0;
                    if (ctx->format == AAUDIO_FORMAT_PCM_FLOAT) {
                        got = VirtualMicClient::instance().readPcmFloat(
                            bufFloat.data(), framesPerBurst, ctx->sampleRate, ctx->channelCount, true, ctx->resample);
                        audioPtr = bufFloat.data();
                    } else {
                        got = VirtualMicClient::instance().readPcm16(
                            buf16.data(), framesPerBurst, ctx->sampleRate, ctx->channelCount, true, ctx->resample);
                        audioPtr = buf16.data();
                    }
                    if (got > 0 && ctx->callbackRunning.load()) {
                        aaudio_data_callback_result_t cbRes = ctx->dataCallback(
                            stream, ctx->userData, audioPtr, static_cast<int32_t>(got));
                        if (cbRes == AAUDIO_CALLBACK_RESULT_STOP) {
                            ctx->callbackRunning.store(false);
                            break;
                        }
                    }
                }
            });
        }
        return AAUDIO_OK;
    }

    return orig_AAudioStream_requestStart ? orig_AAudioStream_requestStart(stream) : AAUDIO_OK;
}

static aaudio_result_t hooked_AAudioStream_requestStop(AAudioStream* stream) {
    std::shared_ptr<AAudioStreamCtx> ctx;
    {
        std::lock_guard<std::mutex> lock(gAaMtx);
        auto it = gAaStreams.find(stream);
        if (it != gAaStreams.end()) ctx = it->second;
    }

    if (ctx && ctx->virtualized) {
        ctx->callbackRunning.store(false);
        if (ctx->callbackThread.joinable()) {
            ctx->callbackThread.join();
        }
        return AAUDIO_OK;
    }
    return orig_AAudioStream_requestStop ? orig_AAudioStream_requestStop(stream) : AAUDIO_OK;
}

static aaudio_result_t hooked_AAudioStream_read(AAudioStream* stream, void* buffer, int32_t numFrames, int64_t timeoutNanos) {
    std::shared_ptr<AAudioStreamCtx> ctx;
    {
        std::lock_guard<std::mutex> lock(gAaMtx);
        auto it = gAaStreams.find(stream);
        if (it != gAaStreams.end()) ctx = it->second;
    }

    if (ctx && ctx->direction == AAUDIO_DIRECTION_INPUT && VirtualMicClient::instance().isEnabled()) {
        bool blocking = (timeoutNanos > 0);
        size_t written = 0;
        if (ctx->format == AAUDIO_FORMAT_PCM_FLOAT) {
            written = VirtualMicClient::instance().readPcmFloat(
                reinterpret_cast<float*>(buffer), static_cast<size_t>(numFrames),
                ctx->sampleRate, ctx->channelCount, blocking, ctx->resample);
        } else {
            written = VirtualMicClient::instance().readPcm16(
                reinterpret_cast<int16_t*>(buffer), static_cast<size_t>(numFrames),
                ctx->sampleRate, ctx->channelCount, blocking, ctx->resample);
        }
        return static_cast<aaudio_result_t>(written);
    }

    return orig_AAudioStream_read ? orig_AAudioStream_read(stream, buffer, numFrames, timeoutNanos) : 0;
}

static aaudio_result_t hooked_AAudioStream_close(AAudioStream* stream) {
    std::shared_ptr<AAudioStreamCtx> ctx;
    {
        std::lock_guard<std::mutex> lock(gAaMtx);
        auto it = gAaStreams.find(stream);
        if (it != gAaStreams.end()) {
            ctx = it->second;
            gAaStreams.erase(it);
        }
    }
    if (ctx) {
        ctx->callbackRunning.store(false);
        if (ctx->callbackThread.joinable()) {
            ctx->callbackThread.join();
        }
    }
    return orig_AAudioStream_close ? orig_AAudioStream_close(stream) : AAUDIO_OK;
}

// ============================================================================
// INSTALLER (Only invoked in virtual app processes :vp0 - :vp3!)
// ============================================================================
static bool gAudioHooked = false;

void installVirtualMicHooks() {
    if (gAudioHooked) return;
    gAudioHooked = true;

    // Start background client connecting to host @vphone_vmic_bus
    VirtualMicClient::instance().start();

    // 1. Hook libaudioclient.so (Java AudioRecord + native AudioRecord)
    void* handle = shadowhook_dlopen("libaudioclient.so");
    if (handle) {
        sym_getSampleRate = reinterpret_cast<AudioRecordGetSampleRateFn>(
            shadowhook_dlsym(handle, "_ZNK7android11AudioRecord13getSampleRateEv"));
        sym_channelCount = reinterpret_cast<AudioRecordChannelCountFn>(
            shadowhook_dlsym(handle, "_ZNK7android11AudioRecord12channelCountEv"));
        sym_format = reinterpret_cast<AudioRecordFormatFn>(
            shadowhook_dlsym(handle, "_ZNK7android11AudioRecord6formatEv"));
        shadowhook_dlclose(handle);
    }

    shadowhook_hook_sym_name(
        "libaudioclient.so",
        "_ZN7android11AudioRecord5startENS_11AudioSystem12sync_event_tE15audio_session_t",
        reinterpret_cast<void*>(hooked_AudioRecord_start),
        reinterpret_cast<void**>(&orig_AudioRecord_start));

    shadowhook_hook_sym_name(
        "libaudioclient.so",
        "_ZN7android11AudioRecord4stopEv",
        reinterpret_cast<void*>(hooked_AudioRecord_stop),
        reinterpret_cast<void**>(&orig_AudioRecord_stop));

#if defined(__LP64__)
    const char* readSym = "_ZN7android11AudioRecord4readEPvmb";
#else
    const char* readSym = "_ZN7android11AudioRecord4readEPvjb";
#endif
    shadowhook_hook_sym_name(
        "libaudioclient.so",
        readSym,
        reinterpret_cast<void*>(hooked_AudioRecord_read),
        reinterpret_cast<void**>(&orig_AudioRecord_read));

    // 2. Hook libaaudio.so (AAudio / Oboe / WebRTC / VoIP / Games)
    shadowhook_hook_sym_name("libaaudio.so", "AAudioStreamBuilder_setDirection",
                             reinterpret_cast<void*>(hooked_AAudioStreamBuilder_setDirection),
                             reinterpret_cast<void**>(&orig_AAudioStreamBuilder_setDirection));
    shadowhook_hook_sym_name("libaaudio.so", "AAudioStreamBuilder_setSampleRate",
                             reinterpret_cast<void*>(hooked_AAudioStreamBuilder_setSampleRate),
                             reinterpret_cast<void**>(&orig_AAudioStreamBuilder_setSampleRate));
    shadowhook_hook_sym_name("libaaudio.so", "AAudioStreamBuilder_setChannelCount",
                             reinterpret_cast<void*>(hooked_AAudioStreamBuilder_setChannelCount),
                             reinterpret_cast<void**>(&orig_AAudioStreamBuilder_setChannelCount));
    shadowhook_hook_sym_name("libaaudio.so", "AAudioStreamBuilder_setFormat",
                             reinterpret_cast<void*>(hooked_AAudioStreamBuilder_setFormat),
                             reinterpret_cast<void**>(&orig_AAudioStreamBuilder_setFormat));
    shadowhook_hook_sym_name("libaaudio.so", "AAudioStreamBuilder_setDataCallback",
                             reinterpret_cast<void*>(hooked_AAudioStreamBuilder_setDataCallback),
                             reinterpret_cast<void**>(&orig_AAudioStreamBuilder_setDataCallback));
    shadowhook_hook_sym_name("libaaudio.so", "AAudioStreamBuilder_openStream",
                             reinterpret_cast<void*>(hooked_AAudioStreamBuilder_openStream),
                             reinterpret_cast<void**>(&orig_AAudioStreamBuilder_openStream));
    shadowhook_hook_sym_name("libaaudio.so", "AAudioStream_requestStart",
                             reinterpret_cast<void*>(hooked_AAudioStream_requestStart),
                             reinterpret_cast<void**>(&orig_AAudioStream_requestStart));
    shadowhook_hook_sym_name("libaaudio.so", "AAudioStream_requestStop",
                             reinterpret_cast<void*>(hooked_AAudioStream_requestStop),
                             reinterpret_cast<void**>(&orig_AAudioStream_requestStop));
    shadowhook_hook_sym_name("libaaudio.so", "AAudioStream_read",
                             reinterpret_cast<void*>(hooked_AAudioStream_read),
                             reinterpret_cast<void**>(&orig_AAudioStream_read));
    shadowhook_hook_sym_name("libaaudio.so", "AAudioStream_close",
                             reinterpret_cast<void*>(hooked_AAudioStream_close),
                             reinterpret_cast<void**>(&orig_AAudioStream_close));

    LOGI("Virtual Microphone native hooks installed (AudioRecord + AAudio).");
}

} // namespace vphone
