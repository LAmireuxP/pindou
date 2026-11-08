package com.pindou.app.core.pattern

import kotlin.math.ln
import kotlin.math.pow

/**
 * 曝光修正：欠曝照片（手机暗光拍摄很常见）直接转图纸时主体颜色挤在暗端，
 * 映射出来的色号全堆在深色区，主体不可辨认。转换前先做亮度提升。
 *
 * 策略（确定性、O(n)、单 LUT 各通道共用，不产生偏色）：
 * 1. 亮度直方图取中位数，中位 ≥ 70 视为曝光正常，原样返回（不碰好图）；
 * 2. 窄动态范围（p99-p1 < 60，欠曝照片典型）：线性拉伸 p1..p99 → 0..255；
 * 3. 拉伸后中位仍 < 70，或动态范围本来就宽但整体偏暗：gamma 校正把中位拉到 ~115。
 */
object ExposureCorrector {

    private const val MEDIAN_DARK_THRESHOLD = 70
    private const val TARGET_MEDIAN = 115

    fun correct(pixels: IntArray, width: Int, height: Int): IntArray {
        if (pixels.isEmpty()) return pixels
        val hist = IntArray(256)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            hist[(r * 77 + g * 150 + b * 29) shr 8]++
        }
        val n = pixels.size
        fun quantile(q: Double): Int {
            var acc = 0
            val target = (n * q).toInt().coerceIn(1, n)
            for (v in 0..255) {
                acc += hist[v]
                if (acc >= target) return v
            }
            return 255
        }

        val median = quantile(0.5)
        if (median >= MEDIAN_DARK_THRESHOLD) return pixels
        val p99 = quantile(0.99)
        val p1 = quantile(0.01)
        val span = p99 - p1

        // 第一级 LUT：窄动态范围时线性拉伸，否则直通
        val levelLut = IntArray(256)
        if (span in 24..59) {
            // 拉伸下限钳 24 防止近似纯色图（span≈0）被拉成全黑
            for (v in 0..255) {
                levelLut[v] = ((v - p1).coerceAtLeast(0).toDouble() / span * 255.0 + 0.5)
                    .toInt().coerceIn(0, 255)
            }
        } else {
            for (v in 0..255) levelLut[v] = v
        }

        // 第二级 LUT：拉伸后中位仍暗 → gamma 校正；指数 < 1 即提亮
        val stretchedMedian = levelLut[median]
        val gammaLut: IntArray
        if (stretchedMedian < MEDIAN_DARK_THRESHOLD) {
            val exponent = (
                ln(TARGET_MEDIAN / 255.0) /
                    ln(stretchedMedian.coerceAtLeast(1) / 255.0)
                ).coerceIn(0.3, 1.0)
            gammaLut = IntArray(256) { v ->
                (255.0 * (v / 255.0).pow(exponent) + 0.5).toInt().coerceIn(0, 255)
            }
        } else {
            gammaLut = IntArray(256) { it }
        }

        val lut = IntArray(256) { v -> gammaLut[levelLut[v]] }
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            (p and 0xFF000000.toInt()) or
                (lut[(p shr 16) and 0xFF] shl 16) or
                (lut[(p shr 8) and 0xFF] shl 8) or
                lut[p and 0xFF]
        }
    }
}
