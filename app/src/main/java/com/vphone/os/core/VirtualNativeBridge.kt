package com.vphone.os.core

object VirtualNativeBridge {
    init {
        System.loadLibrary("vphone_native")
    }

    external fun nativeInitVirtualProcess(enableVirtualMicHook: Boolean)
    external fun nativeAddIoRedirect(srcPrefix: String, dstPrefix: String)
    external fun nativeSetVirtualMicEnabled(enabled: Boolean)
    external fun nativeIsVirtualMicConnected(): Boolean
}
