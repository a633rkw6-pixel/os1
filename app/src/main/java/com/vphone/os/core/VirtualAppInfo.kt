package com.vphone.os.core

data class VirtualAppInfo(
    val packageName: String,
    val label: String,
    val apkPath: String,
    val nativeLibDir: String,
    val dataDir: String,
    val launchActivity: String,
    val versionName: String
)
