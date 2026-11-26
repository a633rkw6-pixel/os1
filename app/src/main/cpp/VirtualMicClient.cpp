#include "VirtualMicClient.h"

#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#define LOG_TAG "VPhoneMicClient"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace vphone {

VirtualMicClient& VirtualMicClient::instance() {
    static VirtualMicClient inst;
    return inst;
}

VirtualMicClient::~VirtualMicClient() {
    stop();
}

void VirtualMicClient::start() {
    if (started_.exchange(true)) return;
    running_.store(true);
    readerThread_ = std::thread(&VirtualMicClient::readerLoop, this);
}

void VirtualMicClient::stop() {
    if (!started_.exchange(false)) return;
    running_.store(false);
    int fd = sockFd_.exchange(-1);
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }
    cv_.notify_all();
    if (readerThread_.joinable()) {
        readerThread_.join();
    }
}

void VirtualMicClient::setEnabled(bool enabled) {
    enabled_.store(enabled, std::memory_order_relaxed);
    cv_.notify_all();
}

void VirtualMicClient::readerLoop() {
    constexpr size_t kChunkSamples = 960; // 20ms @ 48kHz
    int16_t readBuf[kChunkSamples];

    while (running_.load()) {
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(300));
            continue;
        }

        struct sockaddr_un addr;
        std::memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        // Abstract namespace socket: leading NUL byte + name
        addr.sun_path[0] = '\0';
        size_t nameLen = std::strlen(VMIC_SOCKET_NAME);
        std::memcpy(addr.sun_path + 1, VMIC_SOCKET_NAME, nameLen);
        socklen_t addrLen = static_cast<socklen_t>(offsetof(struct sockaddr_un, sun_path) + 1 + nameLen);

        if (connect(fd, reinterpret_cast<struct sockaddr*>(&addr), addrLen) != 0) {
            close(fd);
            connected_.store(false);
            std::this_thread::sleep_for(std::chrono::milliseconds(250));
            continue;
        }

        sockFd_.store(fd);
        connected_.store(true);
        LOGI("Connected to host ClearVoice Virtual Mic bus (@%s)", VMIC_SOCKET_NAME);

        // Read stream of 16-bit PCM samples
        uint8_t* rawPtr = reinterpret_cast<uint8_t*>(readBuf);
        size_t leftoverBytes = 0;

        while (running_.load()) {
            ssize_t n = recv(fd, rawPtr + leftoverBytes, sizeof(readBuf) - leftoverBytes, 0);
            if (n <= 0) {
                break;
            }
            size_t totalBytes = leftoverBytes + static_cast<size_t>(n);
            size_t sampleCount = totalBytes / sizeof(int16_t);
            if (sampleCount > 0) {
                pushSamples(readBuf, sampleCount);
            }
            leftoverBytes = totalBytes % sizeof(int16_t);
            if (leftoverBytes > 0) {
                rawPtr[0] = rawPtr[sampleCount * sizeof(int16_t)];
            }
        }

        connected_.store(false);
        int oldFd = sockFd_.exchange(-1);
        if (oldFd >= 0) {
            close(oldFd);
        }
        LOGW("Disconnected from Virtual Mic bus; retrying...");
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
}

void VirtualMicClient::pushSamples(const int16_t* data, size_t count) {
    std::lock_guard<std::mutex> lock(mtx_);
    for (size_t i = 0; i < count; ++i) {
        ring_[writeIdx_] = data[i];
        writeIdx_ = (writeIdx_ + 1) % kRingCapacity;
        if (count_ < kRingCapacity) {
            ++count_;
        } else {
            readIdx_ = (readIdx_ + 1) % kRingCapacity;
        }
    }
    // Keep live latency tight if consumer fell behind
    if (count_ > kMaxLatencySamples) {
        size_t drop = count_ - (kMaxLatencySamples / 2);
        readIdx_ = (readIdx_ + drop) % kRingCapacity;
        count_ -= drop;
    }
    cv_.notify_all();
}

size_t VirtualMicClient::availableLocked() const {
    return count_;
}

int16_t VirtualMicClient::popSampleLocked() {
    if (count_ == 0) return 0;
    int16_t v = ring_[readIdx_];
    readIdx_ = (readIdx_ + 1) % kRingCapacity;
    --count_;
    return v;
}

size_t VirtualMicClient::readPcm16(int16_t* out,
                                   size_t framesRequested,
                                   int targetSampleRate,
                                   int targetChannels,
                                   bool blocking,
                                   StreamResampleState& state) {
    if (!out || framesRequested == 0) return 0;
    if (targetSampleRate <= 0) targetSampleRate = VMIC_SOURCE_SAMPLE_RATE;
    if (targetChannels <= 0) targetChannels = 1;

    const double step = static_cast<double>(VMIC_SOURCE_SAMPLE_RATE) / static_cast<double>(targetSampleRate);
    const size_t neededSourceSamples = std::max<size_t>(
        1, static_cast<size_t>(std::ceil(framesRequested * step)) + 2
    );

    std::unique_lock<std::mutex> lock(mtx_);
    if (blocking) {
        // Wait up to 80ms for real-time audio from ClearVoice host service.
        // If host service is stopped, we pace gently and output silence.
        auto expectedMs = std::max<int64_t>(5, static_cast<int64_t>((framesRequested * 1000LL) / targetSampleRate));
        auto waitTime = std::chrono::milliseconds(std::min<int64_t>(expectedMs + 25, 100));
        cv_.wait_for(lock, waitTime, [&]() {
            return availableLocked() >= neededSourceSamples || !running_.load();
        });
    } else if (availableLocked() == 0) {
        return 0;
    }

    size_t framesWritten = 0;
    for (size_t f = 0; f < framesRequested; ++f) {
        int16_t s0 = state.prevSample;
        int16_t s1 = (count_ > 0) ? ring_[readIdx_] : s0;

        double frac = state.phase;
        double interp = static_cast<double>(s0) * (1.0 - frac) + static_cast<double>(s1) * frac;
        int32_t clamped = static_cast<int32_t>(std::lrint(interp));
        if (clamped > 32767) clamped = 32767;
        if (clamped < -32768) clamped = -32768;
        int16_t finalSample = static_cast<int16_t>(clamped);

        for (int ch = 0; ch < targetChannels; ++ch) {
            out[f * targetChannels + ch] = finalSample;
        }
        ++framesWritten;

        state.phase += step;
        while (state.phase >= 1.0) {
            state.phase -= 1.0;
            if (count_ > 0) {
                state.prevSample = popSampleLocked();
            } else {
                state.prevSample = 0;
            }
        }
    }

    return framesWritten;
}

size_t VirtualMicClient::readPcmFloat(float* out,
                                      size_t framesRequested,
                                      int targetSampleRate,
                                      int targetChannels,
                                      bool blocking,
                                      StreamResampleState& state) {
    if (!out || framesRequested == 0) return 0;
    if (targetChannels <= 0) targetChannels = 1;

    std::vector<int16_t> temp(framesRequested * static_cast<size_t>(targetChannels));
    size_t written = readPcm16(temp.data(), framesRequested, targetSampleRate, targetChannels, blocking, state);
    size_t totalSamples = written * static_cast<size_t>(targetChannels);
    for (size_t i = 0; i < totalSamples; ++i) {
        out[i] = static_cast<float>(temp[i]) / 32768.0f;
    }
    return written;
}

} // namespace vphone
