package com.vphone.os.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Hooks IActivityTaskManager / IActivityManager so that any startActivity()
 * targeting an installed virtual app is transparently routed through an
 * isolated stub activity (:vp0 - :vp3) registered in AndroidManifest.xml.
 */
object ActivityManagerHook {

    private const val TAG = "ActivityManagerHook"
    const val EXTRA_TARGET_INTENT = "com.vphone.os.EXTRA_TARGET_INTENT"
    const val EXTRA_TARGET_PACKAGE = "com.vphone.os.EXTRA_TARGET_PACKAGE"

    fun install(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hookSingleton(
                context = context,
                holderClassName = "android.app.ActivityTaskManager",
                singletonFieldName = "IActivityTaskManagerSingleton",
                interfaceName = "android.app.IActivityTaskManager"
            )
        }
        hookSingleton(
            context = context,
            holderClassName = "android.app.ActivityManager",
            singletonFieldName = "IActivityManagerSingleton",
            interfaceName = "android.app.IActivityManager"
        )
    }

    private fun hookSingleton(
        context: Context,
        holderClassName: String,
        singletonFieldName: String,
        interfaceName: String
    ) {
        runCatching {
            val holderClass = Class.forName(holderClassName)
            val singletonField = holderClass.getDeclaredField(singletonFieldName).apply {
                isAccessible = true
            }
            val singleton = singletonField.get(null) ?: return
            val singletonClass = Class.forName("android.util.Singleton")
            val getMethod = singletonClass.getDeclaredMethod("get").apply { isAccessible = true }
            val original = getMethod.invoke(singleton) ?: return

            val iface = Class.forName(interfaceName)
            val proxy = Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
                StartActivityProxyHandler(context, original)
            )
            val instanceField = singletonClass.getDeclaredField("mInstance").apply {
                isAccessible = true
            }
            instanceField.set(singleton, proxy)
            Log.i(TAG, "Installed dynamic proxy on $interfaceName")
        }.onFailure {
            Log.w(TAG, "Skipped hook for $interfaceName: ${it.message}")
        }
    }

    private class StartActivityProxyHandler(
        private val context: Context,
        private val original: Any
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "startActivity" && args != null) {
                for (i in args.indices) {
                    val arg = args[i]
                    if (arg is Intent) {
                        val targetPkg = arg.component?.packageName ?: arg.`package`
                        if (targetPkg != null && targetPkg != context.packageName) {
                            val vApp = VirtualCore.get().packageManager.getApp(targetPkg)
                            if (vApp != null) {
                                val stubClass = VirtualCore.get().allocateStubForPackage(targetPkg)
                                val wrapped = Intent().apply {
                                    component = ComponentName(context.packageName, stubClass.name)
                                    putExtra(EXTRA_TARGET_INTENT, arg)
                                    putExtra(EXTRA_TARGET_PACKAGE, targetPkg)
                                    flags = arg.flags
                                }
                                @Suppress("UNCHECKED_CAST")
                                (args as Array<Any?>)[i] = wrapped
                                break
                            }
                        }
                    }
                }
            }
            return if (args != null) {
                method.invoke(original, *args)
            } else {
                method.invoke(original)
            }
        }
    }
}
