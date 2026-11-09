package com.pindou.app.core.pattern

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 像素画（马赛克原图）检测。
 *
 * 用户导入的图里有一大类本身就是放大后的像素画：每个"画出来的像素"在
 * 源图上占 b×b 个真实像素（本例 b≈12）。对这种图直接做面积平均重采样，
 * 每个格子会横跨两三个画像素，颜色被白底稀释成灰粉，量化后整圈花瓣碎成
 * 胡椒面——这是"一比一复刻却完全不像"的根因。
 *
 * 检测办法：统计水平相邻像素"几乎同色"（每通道差 ≤ [FLAT_TOLERANCE]）的
 * 连续长度直方图。像素画的直方图会在 b、2b、3b 处堆出尖峰；照片的连续长度
 * 服从长尾分布，没有集中峰。取峰值集中度 ≥ [MIN_CONCENTRATION] 且横竖方向
 * 一致的结果作为块尺寸，其余情况返回 null 走原来的面积平均。
 *
 * 复杂度 O(源像素抽样)，1200 万像素的图也只扫 ~40 万像素。
 */
object PixelArtDetector {

    /** 小于 6px 的块和 JPEG 压缩噪声无法区分，不启用 */
    private const val MIN_BLOCK = 6
    private const val MAX_BLOCK = 35

    /** 相邻像素算"同色"的每通道容差：JPEG 振铃一般在 ±5 以内 */
    private const val FLAT_TOLERANCE = 6

    /** 峰值集中度下限：三格窗口内的 run 数占总数的比例 */
    private const val MIN_CONCENTRATION = 0.33

    /** 横竖块尺寸允许的相对偏差（非整数倍缩放会让两者差 1~2px） */
    private const val AXIS_TOLERANCE = 0.25

    data class Result(val block: Int, val artW: Int, val artH: Int)

    fun detect(pixels: IntArray, srcW: Int, srcH: Int): Result? {
        if (srcW < 32 || srcH < 32) return null
        // 长图不逐行扫：400 行足以让直方图稳定，耗时与图片高度解耦
        // 只抽线、不跳点：run 长度必须按真实像素数统计，跳点会把长度单位
        // 变成"步长"而让 b 被低估 step 倍
        val rowStep = max(1, srcH / 400)
        val colStep = max(1, srcW / 400)
        val hRuns = runHistogram(pixels, srcW, srcH, rowStep, horizontal = true)
        val vRuns = runHistogram(pixels, srcW, srcH, colStep, horizontal = false)
        val bx = peakBlock(hRuns.first, hRuns.second) ?: return null
        val by = peakBlock(vRuns.first, vRuns.second) ?: return null
        val diff = abs(bx - by).toDouble() / max(bx, by)
        if (diff > AXIS_TOLERANCE) return null
        val block = ((bx + by) / 2.0).roundToInt().coerceIn(MIN_BLOCK, MAX_BLOCK)
        val artW = (srcW.toDouble() / block).roundToInt()
        val artH = (srcH.toDouble() / block).roundToInt()
        if (artW < 8 || artH < 8) return null
        return Result(block, artW, artH)
    }

    /** 连续同色长度直方图；只按 [lineStep] 抽线，线内逐像素比较 */
    private fun runHistogram(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        lineStep: Int,
        horizontal: Boolean,
    ): Pair<IntArray, Int> {
        val hist = IntArray(MAX_BLOCK * 3 + 2)
        var total = 0
        val lines = if (horizontal) srcH else srcW
        val len = if (horizontal) srcW else srcH
        var line = 0
        while (line < lines) {
            var run = 1
            var prev = sample(pixels, srcW, srcH, horizontal, line, 0)
            var i = 1
            while (i < len) {
                val p = sample(pixels, srcW, srcH, horizontal, line, i)
                if (channelDiff(prev, p) <= FLAT_TOLERANCE) {
                    run++
                } else {
                    if (run in 3..(MAX_BLOCK * 3)) {
                        hist[run]++
                        total++
                    }
                    run = 1
                }
                prev = p
                i++
            }
            if (run in 3..(MAX_BLOCK * 3)) {
                hist[run]++
                total++
            }
            line += lineStep
        }
        return Pair(hist, total)
    }

    private fun sample(a: IntArray, w: Int, h: Int, horizontal: Boolean, line: Int, i: Int): Int =
        if (horizontal) a[line * w + min(i, w - 1)] else a[min(i, h - 1) * w + line]

    /** 直方图里最集中的 3 格窗口；集中度不足或样本太少返回 null */
    private fun peakBlock(hist: IntArray, total: Int): Int? {
        if (total < 50) return null
        var best = 0.0
        var bestB = 0
        for (b in MIN_BLOCK..MAX_BLOCK) {
            var c = 0
            for (d in -1..1) c += hist[b + d]
            val frac = c.toDouble() / total
            if (frac > best) {
                best = frac
                bestB = b
            }
        }
        return if (best >= MIN_CONCENTRATION) bestB else null
    }

    private fun channelDiff(p: Int, q: Int): Int {
        val dr = abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
        val dg = abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
        val db = abs((p and 0xFF) - (q and 0xFF))
        return max(dr, max(dg, db))
    }
}
