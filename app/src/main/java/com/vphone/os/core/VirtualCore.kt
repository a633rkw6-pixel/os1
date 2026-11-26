package com.vphone.os.core

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.vphone.os.stub.StubActivityP0
import com.vphone.os.stub.StubActivityP1
import com.vphone.os.stub.StubActivityP2
import com.vphone.os.stub.StubActivityP3
import java.util.concurrent.ConcurrentHashMap

class VirtualCore private constructor(private val appContext: Context) {

    val packageManager = VirtualPackageManager(appContext)

    private val stubSlots: List<Class<out Activity>> = listOf(
        StubActivityP0::class.java,
        StubActivityP1::class.java,
        StubActivityP2::class.java,
        StubActivityP3::class.java
    )
    private val packageSlotMap = ConcurrentHashMap<String, Int>()

    companion object {
        @Volatile private var instance: VirtualCore? = null

        fun init(context: Context): VirtualCore {
            return instance ?: synchronized(this) {
                instance ?: VirtualCore(context.applicationContext).also { instance = it }
            }
        }

        fun get(): VirtualCore = checkNotNull(instance) { "VirtualCore.init() must be called first." }
    }

    fun allocateStubForPackage(packageName: String): Class<out Activity> {
        val idx = packageSlotMap.getOrPut(packageName) {
            (packageName.hashCode() and 0x7FFFFFFF) % stubSlots.size
        }
        return stubSlots[idx]
    }

    fun launchVirtualApp(context: Context, vApp: VirtualAppInfo) {
        val targetIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(vApp.packageName, vApp.launchActivity)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(targetIntent)
    }
}
