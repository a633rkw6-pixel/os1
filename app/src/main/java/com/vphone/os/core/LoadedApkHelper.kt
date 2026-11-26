package com.vphone.os.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads a virtual APK's DEX classes & native libraries inside a stub process
 * (:vp0 - :vp3) and registers its LoadedApk + ClassLoader into ActivityThread.
 */
object LoadedApkHelper {

    private const val TAG = "LoadedApkHelper"
    private val classLoaders = ConcurrentHashMap<String, ClassLoader>()

    fun ensureLoaded(hostContext: Context, vApp: VirtualAppInfo): ClassLoader {
        classLoaders[vApp.packageName]?.let { return it }

        // 1. Register IO sandbox redirection before guest code runs
        VirtualNativeBridge.nativeAddIoRedirect("/data/data/${vApp.packageName}", vApp.dataDir)
        VirtualNativeBridge.nativeAddIoRedirect("/data/user/0/${vApp.packageName}", vApp.dataDir)

        // 2. Create DexClassLoader for the virtual APK
        val optDir = File(vApp.dataDir, "oat").apply { mkdirs() }
        val loader = DexClassLoader(
            vApp.apkPath,
            optDir.absolutePath,
            vApp.nativeLibDir,
            hostContext.classLoader.parent
        )

        // 3. Inject into ActivityThread.mPackages so ActivityThread can instantiate target Activity
        runCatching {
            val pm = hostContext.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(vApp.apkPath, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(vApp.apkPath, 0)
            }
            val appInfo: ApplicationInfo = pkgInfo?.applicationInfo ?: ApplicationInfo().apply {
                packageName = vApp.packageName
            }
            appInfo.sourceDir = vApp.apkPath
            appInfo.publicSourceDir = vApp.apkPath
            appInfo.dataDir = vApp.dataDir
            appInfo.nativeLibraryDir = vApp.nativeLibDir

            val atClass = Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getDeclaredMethod("currentActivityThread").invoke(null)

            val compatClass = Class.forName("android.content.res.CompatibilityInfo")
            val defaultCompat = compatClass.getDeclaredField("DEFAULT_COMPATIBILITY_INFO").get(null)

            val getPkgInfoNoCheck = atClass.getDeclaredMethod(
                "getPackageInfoNoCheck",
                ApplicationInfo::class.java,
                compatClass
            )
            val loadedApk = getPkgInfoNoCheck.invoke(currentAT, appInfo, defaultCompat)

            if (loadedApk != null) {
                val clField = loadedApk.javaClass.getDeclaredField("mClassLoader").apply {
                    isAccessible = true
                }
                clField.set(loadedApk, loader)

                val mPackagesField = atClass.getDeclaredField("mPackages").apply {
                    isAccessible = true
                }
                @Suppress("UNCHECKED_CAST")
                val mPackages = mPackagesField.get(currentAT) as? MutableMap<String, WeakReference<Any>>
                mPackages?.put(vApp.packageName, WeakReference(loadedApk))
            }
        }.onFailure {
            Log.w(TAG, "LoadedApk registration warning (fallback ClassLoader active): ${it.message}")
        }

        classLoaders[vApp.packageName] = loader
        return loader
    }
}
