package com.vphone.os

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.vphone.os.core.ActivityManagerHook
import com.vphone.os.core.ActivityThreadCallback
import com.vphone.os.core.VirtualCore
import com.vphone.os.core.VirtualNativeBridge
import org.lsposed.hiddenapibypass.HiddenApiBypass

class VPhoneApplication : Application() {

    companion object {
        private const val TAG = "VPhoneApplication"
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        VirtualCore.init(this)

        val processName = getCurrentProcessNameSafe()
        val isVirtualProcess = processName.contains(":vp")
        Log.i(TAG, "Process started: $processName (isVirtualProcess=$isVirtualProcess)")

        // Hook ActivityManager in every process so virtual launches route to :vp0-:vp3
        ActivityManagerHook.install(this)

        if (isVirtualProcess) {
            // ONLY inside virtual phone processes (:vp0-:vp3):
            // 1. Intercept ActivityThread launches for guest APKs
            // 2. Install ShadowHook IO sandbox + Virtual Microphone interception
            //    so ALL apps inside the virtual phone get audio from host ClearVoice Live!
            ActivityThreadCallback.install(this)
            VirtualNativeBridge.nativeInitVirtualProcess(enableVirtualMicHook = true)
        }
    }

    private fun getCurrentProcessNameSafe(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName() ?: packageName
        } else {
            packageName
        }
    }
}
