package com.vphone.os.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Message
import android.util.Log

/**
 * Intercepts ActivityThread.mH (EXECUTE_TRANSACTION / LAUNCH_ACTIVITY) inside
 * stub processes (:vp0 - :vp3) to unwrap the target virtual Intent and load
 * the guest APK's ClassLoader before Activity instantiation.
 */
object ActivityThreadCallback {

    private const val TAG = "ActivityThreadCallback"
    private const val LAUNCH_ACTIVITY = 100
    private const val EXECUTE_TRANSACTION = 159

    fun install(context: Context) {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val mHField = atClass.getDeclaredField("mH").apply { isAccessible = true }
            val mH = mHField.get(currentAT) as Handler
            val mCallbackField = Handler::class.java.getDeclaredField("mCallback").apply {
                isAccessible = true
            }
            val origCallback = mCallbackField.get(mH) as? Handler.Callback

            mCallbackField.set(mH, Handler.Callback { msg: Message ->
                when (msg.what) {
                    LAUNCH_ACTIVITY -> handleLaunchActivity(context, msg)
                    EXECUTE_TRANSACTION -> handleExecuteTransaction(context, msg)
                }
                origCallback?.handleMessage(msg) ?: false
            })
            Log.i(TAG, "Installed ActivityThread.mH callback")
        }.onFailure {
            Log.w(TAG, "Could not hook ActivityThread.mH: ${it.message}")
        }
    }

    private fun handleLaunchActivity(context: Context, msg: Message) {
        runCatching {
            val record = msg.obj ?: return
            val intentField = record.javaClass.getDeclaredField("intent").apply { isAccessible = true }
            val rawIntent = intentField.get(record) as? Intent ?: return
            val targetIntent = extractTargetIntent(rawIntent) ?: return
            val targetPkg = rawIntent.getStringExtra(ActivityManagerHook.EXTRA_TARGET_PACKAGE) ?: return

            VirtualCore.get().packageManager.getApp(targetPkg)?.let { vApp ->
                LoadedApkHelper.ensureLoaded(context, vApp)
                intentField.set(record, targetIntent)
            }
        }
    }

    private fun handleExecuteTransaction(context: Context, msg: Message) {
        runCatching {
            val transaction = msg.obj ?: return
            val callbacksMethod = transaction.javaClass.getDeclaredMethod("getCallbacks").apply {
                isAccessible = true
            }
            val callbacks = callbacksMethod.invoke(transaction) as? List<*> ?: return
            for (item in callbacks) {
                if (item != null && item.javaClass.name.endsWith("LaunchActivityItem")) {
                    val intentField = item.javaClass.getDeclaredField("mIntent").apply {
                        isAccessible = true
                    }
                    val rawIntent = intentField.get(item) as? Intent ?: continue
                    val targetIntent = extractTargetIntent(rawIntent) ?: continue
                    val targetPkg = rawIntent.getStringExtra(ActivityManagerHook.EXTRA_TARGET_PACKAGE) ?: continue

                    VirtualCore.get().packageManager.getApp(targetPkg)?.let { vApp ->
                        LoadedApkHelper.ensureLoaded(context, vApp)
                        intentField.set(item, targetIntent)
                    }
                }
            }
        }
    }

    private fun extractTargetIntent(raw: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            raw.getParcelableExtra(ActivityManagerHook.EXTRA_TARGET_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            raw.getParcelableExtra(ActivityManagerHook.EXTRA_TARGET_INTENT)
        }
    }
}
