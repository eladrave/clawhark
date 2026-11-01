package com.ettlinger.wearrecorder

import android.app.Application
import android.app.Activity
import android.os.Bundle

class RaveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        RecorderStatus.processStarted(this)
        BatteryMonitor.init(this)
        AppLog.i("App", "Application started; version=${BuildConfig.VERSION_NAME}")
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) { started++; BatteryMonitor.appVisible = true }
            override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0); BatteryMonitor.appVisible = started > 0 }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
