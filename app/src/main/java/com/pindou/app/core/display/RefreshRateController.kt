package com.pindou.app.core.display

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 全局刷新率控制。
 *
 * 两个抓手，都是公开 API：
 * 1. 窗口的 preferredDisplayModeId + preferredRefreshRate，让系统给本窗口挑模式；
 * 2. Surface.setFrameRate，给独立 Surface 打帧率标记。
 *
 * 只传 modeId 不够稳：部分 ROM 只认帧率，部分只认 modeId，所以两个一起写。
 * 恢复“跟随系统”时统一清 0，把调度权交还系统。
 */
object RefreshRateController {

    const val SYSTEM = "system"

    /** 设置项取值，顺序即设置页展示顺序。 */
    val MODES = listOf(SYSTEM, "60", "90", "120")

    fun normalize(raw: String?): String = if (raw != null && raw in MODES) raw else "120"

    /** 目标帧率；跟随系统返回 null。 */
    fun targetHz(mode: String): Float? = normalize(mode).toFloatOrNull()

    /** 屏幕真实支持哪几档，四舍五入去重后升序。 */
    fun supportedRates(display: Display?): List<Int> {
        val modes = display?.supportedModes ?: return emptyList()
        return modes.map { it.refreshRate.roundToInt() }.distinct().sorted()
    }

    /** 取最接近目标帧率的模式 id；0 表示不指定。 */
    fun closestModeId(display: Display?, hz: Float?): Int {
        if (hz == null) return 0
        val modes = display?.supportedModes ?: return 0
        return modes.minByOrNull { abs(it.refreshRate - hz) }?.modeId ?: 0
    }

    /**
     * 把设置里的目标帧率落到屏幕真支持的档位。
     * 60Hz 屏选 120 时不能硬报 120，取最接近的一档。
     */
    fun effectiveHz(display: Display?, mode: String): Float? {
        val hz = targetHz(mode) ?: return null
        val rates = display?.supportedModes?.map { it.refreshRate } ?: emptyList()
        if (rates.isEmpty()) return hz
        return rates.minByOrNull { abs(it - hz) }
    }

    /**
     * 写窗口参数。改完必须重新赋值 attributes，只改对象不会触发 WM 刷新。
     */
    fun applyWindow(activity: Activity, mode: String) {
        val display = displayOf(activity)
        val hz = effectiveHz(display, mode)
        val attrs: WindowManager.LayoutParams = activity.window.attributes
        attrs.preferredRefreshRate = hz ?: 0f
        attrs.preferredDisplayModeId = closestModeId(display, hz)
        activity.window.attributes = attrs
    }

    /**
     * 给 Surface 打帧率。CHANGE_FRAME_RATE_ALWAYS 表示内容静止也保持该帧率，
     * API 31 才有；API 30 只能声明兼容性。
     */
    fun applySurface(surface: Surface?, hz: Float) {
        val s = surface ?: return
        if (!s.isValid) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                s.setFrameRate(
                    hz,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else if (Build.VERSION.SDK_INT >= 30) {
                s.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }

    private fun displayOf(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
}
