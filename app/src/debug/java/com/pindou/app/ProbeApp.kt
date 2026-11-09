package com.pindou.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.pindou.app.ui.AppViewModel

/**
 * debug 包的入口壳：只在 `am start ... --ez probe true` 时干活，平时行为与普通 Application 一致。
 *
 * 为什么要藏在 Application 里：这台 MIUI 上 `adb shell input` 被 INJECT_EVENTS 拦，
 * 而 instrumentation 进程拉起 Activity 又会被 BAL 规则挡住；应用自己已经在跑的时候
 * 直接从 ViewModel 驱动流程，才能稳定拿到真机截图与耗时。
 */
class ProbeApp : Application(), Application.ActivityLifecycleCallbacks {

    private var started = false

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (started || activity !is MainActivity) return
        if (activity.intent?.getBooleanExtra("probe", false) != true) return
        started = true
        val vm = ViewModelProvider(activity as ComponentActivity)[AppViewModel::class.java]
        Thread { ProbeFlow.run(activity, vm) }.start()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
