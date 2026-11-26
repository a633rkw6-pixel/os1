#include "VirtualMicClient.h"

#include <android/log.h>
#include <jni.h>
#include <shadowhook.h>

#include <string>

#define LOG_TAG "VPhoneJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace vphone {
void addIoRedirectRule(const std::string& srcPrefix, const std::string& dstPrefix);
void installIoHooks();
void installVirtualMicHooks();
} // namespace vphone

static bool gShadowHookInitialized = false;

extern "C" {

JNIEXPORT void JNICALL
Java_com_vphone_os_core_VirtualNativeBridge_nativeInitVirtualProcess(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean enableVirtualMicHook) {
    if (!gShadowHookInitialized) {
        shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
        gShadowHookInitialized = true;
    }
    vphone::installIoHooks();
    if (enableVirtualMicHook) {
        vphone::installVirtualMicHooks();
    }
    LOGI("Virtual process native engine initialized (micHook=%d)", enableVirtualMicHook ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_vphone_os_core_VirtualNativeBridge_nativeAddIoRedirect(
    JNIEnv* env, jobject /*thiz*/, jstring srcPrefix, jstring dstPrefix) {
    const char* src = env->GetStringUTFChars(srcPrefix, nullptr);
    const char* dst = env->GetStringUTFChars(dstPrefix, nullptr);
    if (src && dst) {
        vphone::addIoRedirectRule(src, dst);
    }
    if (src) env->ReleaseStringUTFChars(srcPrefix, src);
    if (dst) env->ReleaseStringUTFChars(dstPrefix, dst);
}

JNIEXPORT void JNICALL
Java_com_vphone_os_core_VirtualNativeBridge_nativeSetVirtualMicEnabled(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    vphone::VirtualMicClient::instance().setEnabled(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_vphone_os_core_VirtualNativeBridge_nativeIsVirtualMicConnected(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return vphone::VirtualMicClient::instance().isConnected() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
