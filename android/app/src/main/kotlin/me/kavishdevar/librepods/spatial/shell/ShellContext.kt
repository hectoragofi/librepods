package me.kavishdevar.librepods.spatial.shell

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo

/**
 * AudioTrack / AudioPolicy under UID 2000 need a real shell package identity bound to the
 * process: a system context that reports com.android.shell, installed as the process's
 * application. Ported from the standalone test app (Engine.shellContext).
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal object ShellContext {
    private const val SHELL = "com.android.shell"
    private const val SHELL_UID = 2000

    @Volatile
    private var cached: Context? = null

    @Synchronized
    fun get(): Context {
        cached?.let { return it }
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
            ?: at.getMethod("systemMain").invoke(null)
        val sys = at.getMethod("getSystemContext").invoke(thread) as Context
        val ctx = object : ContextWrapper(sys) {
            override fun getPackageName(): String = SHELL
            override fun getOpPackageName(): String = SHELL
            override fun getAttributionSource(): AttributionSource =
                AttributionSource.Builder(SHELL_UID).setPackageName(SHELL).build()

            override fun getApplicationContext(): Context = this
        }
        setField(thread, "mInitialApplication", Instrumentation.newApplication(Application::class.java, ctx))
        val bind = Class.forName("android.app.ActivityThread\$AppBindData")
        val ctor = bind.getDeclaredConstructor().apply { isAccessible = true }
        val data = ctor.newInstance()
        val info = ApplicationInfo().apply { packageName = SHELL; uid = SHELL_UID }
        setField(data, "appInfo", info)
        setField(thread, "mBoundApplication", data)
        cached = ctx
        return ctx
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }
}
