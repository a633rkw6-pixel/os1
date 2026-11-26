package com.vphone.os.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

class VirtualPackageManager(private val context: Context) {

    companion object {
        private const val TAG = "VirtualPackageManager"
        private const val REGISTRY_FILE = "vphone_apps.json"
    }

    private val rootDir = File(context.filesDir, "vphone").apply { mkdirs() }
    private val apksDir = File(rootDir, "apks").apply { mkdirs() }
    private val dataRoot = File(rootDir, "data").apply { mkdirs() }
    private val registryFile = File(rootDir, REGISTRY_FILE)

    private val installed = LinkedHashMap<String, VirtualAppInfo>()

    init {
        loadRegistry()
    }

    fun getInstalledApps(): List<VirtualAppInfo> {
        loadRegistry()
        return installed.values.toList()
    }

    fun getApp(packageName: String): VirtualAppInfo? {
        if (!installed.containsKey(packageName)) loadRegistry()
        return installed[packageName]
    }

    fun installFromApkFile(sourceApk: File): Result<VirtualAppInfo> {
        return try {
            val pm = context.packageManager
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(sourceApk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(sourceApk.absolutePath, flags)
            } ?: return Result.failure(IllegalArgumentException("Invalid or unreadable APK file."))

            val appInfo = pkgInfo.applicationInfo
                ?: return Result.failure(IllegalArgumentException("Missing ApplicationInfo in APK."))

            appInfo.sourceDir = sourceApk.absolutePath
            appInfo.publicSourceDir = sourceApk.absolutePath

            val pkgName = pkgInfo.packageName
            val label = pm.getApplicationLabel(appInfo)?.toString()?.ifBlank { pkgName } ?: pkgName
            val versionName = pkgInfo.versionName ?: "1.0"

            val launchAct = resolveLaunchActivity(pkgInfo.activities?.map { it.name }.orEmpty(), pkgName)
                ?: return Result.failure(IllegalStateException("No launchable Activity found inside $pkgName."))

            val pkgApkDir = File(apksDir, pkgName).apply { mkdirs() }
            val destApk = File(pkgApkDir, "base.apk")
            sourceApk.copyTo(destApk, overwrite = true)

            val libDir = File(pkgApkDir, "lib").apply { mkdirs() }
            extractNativeLibs(destApk, libDir)

            val sandboxData = File(dataRoot, pkgName).apply {
                mkdirs()
                File(this, "files").mkdirs()
                File(this, "cache").mkdirs()
                File(this, "databases").mkdirs()
                File(this, "shared_prefs").mkdirs()
            }

            val vApp = VirtualAppInfo(
                packageName = pkgName,
                label = label,
                apkPath = destApk.absolutePath,
                nativeLibDir = libDir.absolutePath,
                dataDir = sandboxData.absolutePath,
                launchActivity = launchAct,
                versionName = versionName
            )

            installed[pkgName] = vApp
            saveRegistry()
            Log.i(TAG, "Installed virtual app: $pkgName -> $launchAct")
            Result.success(vApp)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            Result.failure(e)
        }
    }

    fun cloneInstalledHostApp(packageName: String): Result<VirtualAppInfo> {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val sourceApk = File(appInfo.sourceDir)
            if (!sourceApk.exists()) {
                return Result.failure(IllegalStateException("Base APK not accessible for $packageName"))
            }
            installFromApkFile(sourceApk)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun uninstall(packageName: String) {
        installed.remove(packageName)
        File(apksDir, packageName).deleteRecursively()
        File(dataRoot, packageName).deleteRecursively()
        saveRegistry()
    }

    private fun resolveLaunchActivity(activities: List<String>, pkgName: String): String? {
        // Prefer an Activity matching launcher conventions, else first declared activity
        return activities.firstOrNull {
            it.contains("MainActivity", ignoreCase = true) ||
                it.contains("Launcher", ignoreCase = true) ||
                it.contains("Splash", ignoreCase = true)
        } ?: activities.firstOrNull()
    }

    private fun extractNativeLibs(apkFile: File, outDir: File) {
        val supportedAbis = Build.SUPPORTED_ABIS
        ZipFile(apkFile).use { zip ->
            for (abi in supportedAbis) {
                val prefix = "lib/$abi/"
                var foundForAbi = false
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.startsWith(prefix) && entry.name.endsWith(".so")) {
                        val soName = entry.name.substringAfterLast('/')
                        val outFile = File(outDir, soName)
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        outFile.setReadable(true, false)
                        outFile.setExecutable(true, false)
                        foundForAbi = true
                    }
                }
                if (foundForAbi) break
            }
        }
    }

    private fun saveRegistry() {
        val arr = JSONArray()
        for (app in installed.values) {
            arr.put(JSONObject().apply {
                put("packageName", app.packageName)
                put("label", app.label)
                put("apkPath", app.apkPath)
                put("nativeLibDir", app.nativeLibDir)
                put("dataDir", app.dataDir)
                put("launchActivity", app.launchActivity)
                put("versionName", app.versionName)
            })
        }
        registryFile.writeText(arr.toString(2))
    }

    private fun loadRegistry() {
        installed.clear()
        if (!registryFile.exists()) return
        runCatching {
            val arr = JSONArray(registryFile.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val app = VirtualAppInfo(
                    packageName = obj.getString("packageName"),
                    label = obj.getString("label"),
                    apkPath = obj.getString("apkPath"),
                    nativeLibDir = obj.getString("nativeLibDir"),
                    dataDir = obj.getString("dataDir"),
                    launchActivity = obj.getString("launchActivity"),
                    versionName = obj.optString("versionName", "1.0")
                )
                if (File(app.apkPath).exists()) {
                    installed[app.packageName] = app
                }
            }
        }
    }
}
