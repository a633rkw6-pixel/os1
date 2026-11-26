#include <android/log.h>
#include <fcntl.h>
#include <shadowhook.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdarg>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "VPhoneIOHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace vphone {

struct RedirectRule {
    std::string src;
    std::string dst;
};

static std::mutex gRuleMtx;
static std::vector<RedirectRule> gRules;
static bool gIoHooked = false;

static int (*orig_openat)(int, const char*, int, ...) = nullptr;
static int (*orig_access)(const char*, int) = nullptr;
static int (*orig_stat)(const char*, struct stat*) = nullptr;

static std::string redirectPath(const char* path) {
    if (!path) return "";
    std::string p(path);
    std::lock_guard<std::mutex> lock(gRuleMtx);
    for (const auto& r : gRules) {
        if (p.compare(0, r.src.size(), r.src) == 0) {
            return r.dst + p.substr(r.src.size());
        }
    }
    return p;
}

static int hooked_openat(int dirfd, const char* pathname, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    std::string mapped = redirectPath(pathname);
    return orig_openat(dirfd, mapped.c_str(), flags, mode);
}

static int hooked_access(const char* pathname, int mode) {
    std::string mapped = redirectPath(pathname);
    return orig_access(mapped.c_str(), mode);
}

static int hooked_stat(const char* pathname, struct stat* statbuf) {
    std::string mapped = redirectPath(pathname);
    return orig_stat(mapped.c_str(), statbuf);
}

void addIoRedirectRule(const std::string& srcPrefix, const std::string& dstPrefix) {
    std::lock_guard<std::mutex> lock(gRuleMtx);
    for (auto& r : gRules) {
        if (r.src == srcPrefix) {
            r.dst = dstPrefix;
            return;
        }
    }
    gRules.push_back({srcPrefix, dstPrefix});
    LOGI("Added IO redirect: %s -> %s", srcPrefix.c_str(), dstPrefix.c_str());
}

void installIoHooks() {
    if (gIoHooked) return;
    gIoHooked = true;

    shadowhook_hook_sym_name("libc.so", "openat",
                             reinterpret_cast<void*>(hooked_openat),
                             reinterpret_cast<void**>(&orig_openat));
    shadowhook_hook_sym_name("libc.so", "access",
                             reinterpret_cast<void*>(hooked_access),
                             reinterpret_cast<void**>(&orig_access));
    shadowhook_hook_sym_name("libc.so", "stat",
                             reinterpret_cast<void*>(hooked_stat),
                             reinterpret_cast<void**>(&orig_stat));
    LOGI("Native libc IO sandbox hooks installed.");
}

} // namespace vphone
