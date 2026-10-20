package com.indstream.foryou

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock

object AppSession {

    private const val MIN_ENTRY_GAP_MS = 60_000L

    @Volatile
    private var current: Long = 0L

    @Volatile
    private var lastEntryAt: Long = 0L

    @Volatile
    private var attached = false

    private val fallback: Long by lazy { SystemClock.elapsedRealtime() }

    val id: Long
        get() = current.takeIf { it != 0L } ?: fallback

    fun attach(context: Context) {
        if (attached) return
        val app = context.applicationContext as? Application ?: return
        attached = true
        current = SystemClock.elapsedRealtime()
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1) onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                if (started > 0) started--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun onForeground() {
        val now = System.currentTimeMillis()
        if (now - lastEntryAt < MIN_ENTRY_GAP_MS) return
        lastEntryAt = now
        current = SystemClock.elapsedRealtime()
    }
}
