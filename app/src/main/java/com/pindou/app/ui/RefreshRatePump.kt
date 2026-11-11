package com.pindou.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pindou.app.core.display.RefreshRateController
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 刷新率驻留层。
 *
 * 只写窗口的 preferredDisplayModeId 不够用：DisplayManager 记着 120，
 * 但画面一静止 SurfaceFlinger 就把合成降到 60，用户在设置里看到 120、
 * 实际滑动以外的场景全是 60。
 *
 * 这里挂一块 1dp 的透明 SurfaceView，按目标帧率持续提交空帧，再用
 * setFrameRate(CHANGE_FRAME_RATE_ALWAYS) 声明“内容静止也保持该帧率”。
 * 合成器有持续的新帧就会把面板钉在目标档位。
 *
 * 提交放在独立线程：lockCanvas 会等空闲 buffer，放主线程会反过来拖慢 UI。
 * buffer 只有几个像素，成本在 submit 本身，不在绘制。
 */
@Composable
fun RefreshRatePump(mode: String) {
    val view = LocalView.current
    val hz = RefreshRateController.effectiveHz(view.display, mode) ?: return
    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { ctx -> PumpView(ctx).also { it.hz = hz } },
    )
}

private class PumpView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    @Volatile
    var hz: Float = 120f
        set(value) {
            field = value
            RefreshRateController.applySurface(holder.surface, value)
        }

    private val running = AtomicBoolean(false)

    init {
        // onTop 让这块 surface 盖在窗口之上，窗口不用挖洞，透明像素直接透出内容
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        RefreshRateController.applySurface(h.surface, hz)
        startLoop()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        RefreshRateController.applySurface(h.surface, hz)
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        running.set(false)
    }

    override fun onDetachedFromWindow() {
        running.set(false)
        super.onDetachedFromWindow()
    }

    private fun startLoop() {
        if (!running.compareAndSet(false, true)) return
        Thread(::loop, "refresh-pump").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        var next = System.nanoTime()
        while (running.get()) {
            val h = holder
            if (!h.surface.isValid) break
            val posted = try {
                val canvas = h.lockCanvas()
                if (canvas == null) {
                    false
                } else {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    h.unlockCanvasAndPost(canvas)
                    true
                }
            } catch (_: Throwable) {
                false
            }
            if (!posted) break

            val period = (1_000_000_000.0 / hz).toLong()
            next += period
            val wait = next - System.nanoTime()
            if (wait > 1_000_000L) {
                try {
                    Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    break
                }
            } else if (wait < -period * 3) {
                // 落后超过三帧就不补了，直接重新对时
                next = System.nanoTime()
            }
        }
        running.set(false)
    }
}
