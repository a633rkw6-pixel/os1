#pragma once

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

namespace vphone {

constexpr int VMIC_SOURCE_SAMPLE_RATE = 48000;
constexpr int VMIC_SOURCE_CHANNELS = 1;
constexpr const char* VMIC_SOCKET_NAME = "vphone_vmic_bus";

struct StreamResampleState {
    double phase = 0.0;
    int16_t prevSample = 0;
};

/**
 * Connects over Linux abstract Unix socket (@vphone_vmic_bus) to the host
 * process's ClearVoice Live VirtualMicService, buffers incoming 48kHz mono
 * PCM-16 frames, and resamples on demand to whatever sample rate, channel
 * count, and sample format any guest app inside :vp0-:vp3 requests.
 */
class VirtualMicClient {
public:
    static VirtualMicClient& instance();

    void start();
    void stop();

    void setEnabled(bool enabled);
    bool isEnabled() const { return enabled_.load(std::memory_order_relaxed); }
    bool isConnected() const { return connected_.load(std::memory_order_relaxed); }

    /**
     * Reads frames into PCM-16 output buffer.
     * Returns number of frames written (each frame = targetChannels * int16_t).
     */
    size_t readPcm16(int16_t* out,
                     size_t framesRequested,
                     int targetSampleRate,
                     int targetChannels,
                     bool blocking,
                     StreamResampleState& state);

    /**
     * Reads frames into PCM-Float [-1.0, 1.0] output buffer.
     */
    size_t readPcmFloat(float* out,
                        size_t framesRequested,
                        int targetSampleRate,
                        int targetChannels,
                        bool blocking,
                        StreamResampleState& state);

private:
    VirtualMicClient() = default;
    ~VirtualMicClient();

    void readerLoop();
    void pushSamples(const int16_t* data, size_t count);
    size_t availableLocked() const;
    int16_t popSampleLocked();

    static constexpr size_t kRingCapacity = 48000 * 2; // 2 sec max
    static constexpr size_t kMaxLatencySamples = 4800; // ~100ms cap to keep voice live

    std::atomic<bool> started_{false};
    std::atomic<bool> running_{false};
    std::atomic<bool> enabled_{true};
    std::atomic<bool> connected_{false};
    std::atomic<int> sockFd_{-1};

    std::thread readerThread_;

    mutable std::mutex mtx_;
    std::condition_variable cv_;
    std::vector<int16_t> ring_{std::vector<int16_t>(kRingCapacity, 0)};
    size_t readIdx_ = 0;
    size_t writeIdx_ = 0;
    size_t count_ = 0;
};

} // namespace vphone
