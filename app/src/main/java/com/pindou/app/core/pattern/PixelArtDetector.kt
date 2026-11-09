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

    /**
     * @param block 画像素边长（源图像素）
     * @param ox/oy 画像素网格相对源图左上角的偏移。放大过的像素画常带白边或
     *              半格错位，不校正偏移会让每一格都压在块边界上。
     */
    data class Result(val block: Int, val artW: Int, val artH: Int, val ox: Int, val oy: Int)

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
        val hint = ((bx + by) / 2.0).roundToInt().coerceIn(MIN_BLOCK, MAX_BLOCK)
        // 直方图峰值只是"块内平坦段"的长度：块与块之间的过渡带（缩放/JPEG
        // 造成的 2~4px 斜坡）不计入 run，所以峰值系统性地偏小——本例真实
        // 块长 11.8px，峰值落在 9。用"块内纯度"把真实块长找回来：格子正好
        // 落在一个画像素里时，块内众数占比 ≈ 0.9；跨到边界时掉到 0.6 以下。
        var bestBlock = hint
        var bestOx = 0
        var bestOy = 0
        var bestScore = -1.0
        for (b in hint..(hint + 8)) {
            if (b > MAX_BLOCK) break
            for (ox in 0 until min(b, 7) step 2) {
                for (oy in 0 until min(b, 7) step 2) {
                    val score = gridPurity(pixels, srcW, srcH, b, ox, oy)
                    if (score > bestScore) {
                        bestScore = score
                        bestBlock = b
                        bestOx = ox
                        bestOy = oy
                    }
                }
            }
        }
        // 偏移精修到 1px
        for (dx in -1..1) for (dy in -1..1) {
            val ox = (bestOx + dx).coerceAtLeast(0)
            val oy = (bestOy + dy).coerceAtLeast(0)
            val score = gridPurity(pixels, srcW, srcH, bestBlock, ox, oy)
            if (score > bestScore) { bestScore = score; bestOx = ox; bestOy = oy }
        }
        val artW = (srcW - bestOx) / bestBlock
        val artH = (srcH - bestOy) / bestBlock
        if (artW < 8 || artH < 8) return null
        return Result(bestBlock, artW, artH, bestOx, bestOy)
    }

    /**
     * 网格纯度：抽样 [SAMPLE_CELLS] 个格子，统计"格内最多的那个颜色"
     * 占该格像素的比例，取平均。格子与画像素对齐度越高，这个值越接近 1。
     *
     * 只取格内缩 20% 的区域，边界过渡带本来就不该参与。
     */
    private fun gridPurity(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        block: Int,
        ox: Int,
        oy: Int,
    ): Double {
        val aw = (srcW - ox) / block
        val ah = (srcH - oy) / block
        if (aw < 8 || ah < 8) return -1.0
        val total = aw * ah
        val step = max(1, total / SAMPLE_CELLS)
        var sum = 0.0
        var used = 0
        var idx = 0
        while (idx < total) {
            val ax = idx % aw
            val ay = idx / aw
            idx += step
            val x0 = (ox + ax * block).toDouble()
            val x1 = x0 + block
            val y0 = (oy + ay * block).toDouble()
            val y1 = y0 + block
            val sx0 = (x0 + (x1 - x0) * 0.2).toInt().coerceIn(0, srcW - 1)
            val sx1 = (x1 - (x1 - x0) * 0.2).toInt().coerceIn(sx0, srcW - 1)
            val sy0 = (y0 + (y1 - y0) * 0.2).toInt().coerceIn(0, srcH - 1)
            val sy1 = (y1 - (y1 - y0) * 0.2).toInt().coerceIn(sy0, srcH - 1)
            val hist = HashMap<Int, Int>()
            var n = 0
            for (y in sy0..sy1) {
                for (x in sx0..sx1) {
                    val p = pixels[y * srcW + x] and 0xF0F0F0
                    hist.merge(p, 1, Int::plus)
                    n++
                }
            }
            if (n == 0) continue
            sum += (hist.values.maxOrNull() ?: 0).toDouble() / n
            used++
        }
        return if (used == 0) 0.0 else sum / used
    }

    /** 纯度评估的抽样格数：再多也只是让结果更稳，200 个已足够区分档位 */
    private const val SAMPLE_CELLS = 240

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
