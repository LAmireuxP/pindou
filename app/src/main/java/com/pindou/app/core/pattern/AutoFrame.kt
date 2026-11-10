package com.pindou.app.core.pattern

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 主体取景：先裁掉四周空白边，再按目标网格比例补边，最后交给采样。
 *
 * 网格尺寸固定时，空白边同样占用格子。主体只占画面中央一部分时，
 * 先裁掉空白再撑满网格，可以在相同豆数下保留更多细节。
 *
 * 判定：取四边窄带的中位色当背景色，按每通道色差找前景包围盒；
 * 包围盒至少有一边能裁掉 5% 才动手。裁完按 gridW:gridH 居中补背景色，
 * 保证图案不被拉伸。
 *
 * 复杂度：O(源像素) 找包围盒 + O(包围盒) 搬像素。
 */
object AutoFrame {

    /** 包围盒探测的最长边：再大的图按比例缩到这个尺寸上找边界 */
    private const val PROBE_LONG = 192

    /** 与背景色的每通道最大差（0..255）：小于它算背景。16 能抓住淡粉/淡蓝的边 */
    private const val BG_THRESHOLD = 16

    /** 宽或高至少要裁掉这个比例才动手，避免整幅内容图被来回重采样 */
    private const val MIN_TRIM_SHARE = 0.05

    /**
     * 主体四周留白比例。0.02：探测图只有 192 长边，包围盒本身有 1~2 格的量化误差，
     * 留 2% 刚好盖住这个误差；留 4% 时主体只占到 56/60 格，白扔 4 格。
     */
    private const val MARGIN = 0.02

    /** 输出画布上限，防止超大图 + 极端比例把内存撑爆 */
    private const val MAX_SIDE = 4096

    data class Result(
        val pixels: IntArray,
        val w: Int,
        val h: Int,
        /** 是否真的裁过：false 时 pixels 就是入参本身 */
        val trimmed: Boolean,
        val subjectW: Int,
        val subjectH: Int,
    )

    fun apply(pixels: IntArray, srcW: Int, srcH: Int, gridW: Int, gridH: Int): Result {
        val plain = Result(pixels, srcW, srcH, false, srcW, srcH)
        if (srcW < 24 || srcH < 24) return plain
        if (gridW <= 0 || gridH <= 0) return plain
        // 像素画不裁：裁掉空白边会平移画像素网格，逐像素还原的分块对位就废了。
        if (PixelArtDetector.detect(pixels, srcW, srcH) != null) return plain

        val bg = borderMedian(pixels, srcW, srcH)
        val br = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bb = bg and 0xFF

        // 探测图：每个探测格取"离背景最远"的那个源像素，细描边不会被平均没了
        val s = min(1.0, PROBE_LONG.toDouble() / max(srcW, srcH))
        val pw = max(2, (srcW * s).roundToInt())
        val ph = max(2, (srcH * s).roundToInt())
        val dist = IntArray(pw * ph)
        val step = if (srcW.toLong() * srcH > 4_000_000L) 2 else 1
        var y = 0
        while (y < srcH) {
            val py = (y.toLong() * ph / srcH).toInt()
            val rowBase = py * pw
            var x = 0
            while (x < srcW) {
                val p = pixels[y * srcW + x]
                val d = max(
                    max(abs(((p shr 16) and 0xFF) - br), abs(((p shr 8) and 0xFF) - bgG)),
                    abs((p and 0xFF) - bb),
                )
                val i = rowBase + (x.toLong() * pw / srcW).toInt()
                if (d > dist[i]) dist[i] = d
                x += step
            }
            y += step
        }

        var x0 = pw
        var x1 = -1
        var y0 = ph
        var y1 = -1
        var fg = 0
        for (py in 0 until ph) {
            for (px in 0 until pw) {
                if (dist[py * pw + px] > BG_THRESHOLD) {
                    fg++
                    if (px < x0) x0 = px
                    if (px > x1) x1 = px
                    if (py < y0) y0 = py
                    if (py > y1) y1 = py
                }
            }
        }
        // 前景不到 2%：要么是纯色图，要么背景模型判错了，别裁
        if (x1 < x0 || fg * 50L < pw.toLong() * ph) return plain

        val sx0 = (x0.toLong() * srcW / pw).toInt()
        val sx1 = ((x1 + 1).toLong() * srcW / pw).toInt().coerceAtMost(srcW)
        val sy0 = (y0.toLong() * srcH / ph).toInt()
        val sy1 = ((y1 + 1).toLong() * srcH / ph).toInt().coerceAtMost(srcH)
        val sw = sx1 - sx0
        val sh = sy1 - sy0
        if (sw < 8 || sh < 8) return plain

        val keepW = sw.toDouble() / srcW
        val keepH = sh.toDouble() / srcH
        if (keepW > 1 - MIN_TRIM_SHARE && keepH > 1 - MIN_TRIM_SHARE) return plain

        // 目标比例：主体按比例撑满，剩下的用背景色补，图案本身不变形
        val aspect = gridW.toDouble() / gridH
        var boxW = max(sw.toDouble(), sh * aspect) * (1 + 2 * MARGIN)
        var boxH = boxW / aspect
        val f = min(1.0, MAX_SIDE.toDouble() / max(boxW, boxH))
        val ow = max(1, (boxW * f).roundToInt())
        val oh = max(1, (boxH * f).roundToInt())
        boxW = ow.toDouble()
        boxH = oh.toDouble()

        val out = IntArray(ow * oh) { bg }
        // 主体在输出画布里的左上角（居中）
        val dx = (boxW - sw) / 2.0
        val dy = (boxH - sh) / 2.0
        for (oy in 0 until oh) {
            val ly = ((oy + 0.5) * boxH / oh - dy).toInt()
            if (ly < 0 || ly >= sh) continue
            val srcRow = (sy0 + ly) * srcW
            val outRow = oy * ow
            for (ox in 0 until ow) {
                val lx = ((ox + 0.5) * boxW / ow - dx).toInt()
                if (lx < 0 || lx >= sw) continue
                out[outRow + ox] = pixels[srcRow + sx0 + lx]
            }
        }
        return Result(out, ow, oh, true, sw, sh)
    }

    /**
     * 四边窄带的中位色。
     * 用中位而不是均值：主体压到边上时均值会被主体色拽偏，中位仍是背景。
     */
    private fun borderMedian(pixels: IntArray, w: Int, h: Int): Int {
        val band = max(1, min(w, h) / 100)
        val cap = 4096
        val rs = IntArray(cap)
        val gs = IntArray(cap)
        val bs = IntArray(cap)
        var n = 0
        val stride = max(1, (w + h) / 512)
        fun take(x: Int, y: Int) {
            if (n >= cap) return
            val p = pixels[y * w + x]
            rs[n] = (p shr 16) and 0xFF
            gs[n] = (p shr 8) and 0xFF
            bs[n] = p and 0xFF
            n++
        }
        var x = 0
        while (x < w) {
            var k = 0
            while (k < band) {
                take(x, k)
                take(x, h - 1 - k)
                k++
            }
            x += stride
        }
        var y = 0
        while (y < h) {
            var k = 0
            while (k < band) {
                take(k, y)
                take(w - 1 - k, y)
                k++
            }
            y += stride
        }
        if (n == 0) return 0xFFFFFFFF.toInt()
        java.util.Arrays.sort(rs, 0, n)
        java.util.Arrays.sort(gs, 0, n)
        java.util.Arrays.sort(bs, 0, n)
        val m = n / 2
        return (0xFF shl 24) or (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
    }
}
