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
    data class Result(
        val blockW: Double,
        val blockH: Double,
        val artW: Int,
        val artH: Int,
        val ox: Int,
        val oy: Int,
    )

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
        // 直方图峰值只是"块内平坦段"的长度：过渡带不计入 run，峰值系统性偏小。
        // 早先靠"块内纯度"回收真实块长，但软过渡下纯度对相位不敏感——72 种
        // 相位里最高与最低只差 2%，选出来的 ox 常常整块错位，取样窗口压在
        // 块边界上，花瓣里就全是 1 格跳色。
        //
        // 改用"边界梯度对齐"：块边界在源图上是阶跃，把每一列（行）的相邻
        // 像素差加起来得到梯度曲线，真实的 b 与偏移会让 ox+k·b 全部落在这条
        // 曲线的尖峰上。块长按 0.01px 搜索，相位按 0.5px 搜索——缩放后的
        // 块长本来就不是整数（本例 12.11），用整数永远对不齐。
        val gx = gradientProfile(pixels, srcW, srcH, horizontal = true)
        val gy = gradientProfile(pixels, srcW, srcH, horizontal = false)
        // 画像素是正方形：横竖两条曲线必须用**同一个**块长评分，否则 8px 的
        // 花纹周期会在一边胜出、12px 的真块长在另一边胜出，取平均就得到
        // 98×61 这种比例都不对的网格。
        var bestScore = Double.NEGATIVE_INFINITY
        var bestBlock = hint.toDouble()
        var bestOx = 0
        var bestOy = 0
        var b = MIN_BLOCK.toDouble()
        while (b <= MAX_BLOCK) {
            val sx = bestOffset(gx, b)
            val sy = bestOffset(gy, b)
            // 两轴同时对齐才算真网格；取较小值做惩罚，单轴命中不算数。
            // 只接受"明显更好（>5%）"的解：2b、3b 这些谐波与原块长得分相等，
            // 按升序搜索时保留先到的 b，才不会把 12px 的块认成 24px。
            val score = min(sx.second, sy.second)
            if (bestScore == Double.NEGATIVE_INFINITY || score > bestScore + abs(bestScore) * 0.05) {
                bestScore = score
                bestBlock = b
                bestOx = sx.first
                bestOy = sy.first
            }
            b += 0.02
        }
        if (bestScore == Double.NEGATIVE_INFINITY) return null
        val bestBlockW = bestBlock
        val bestBlockH = bestBlock
        val artW = ((srcW - bestOx) / bestBlockW).toInt()
        val artH = ((srcH - bestOy) / bestBlockH).toInt()
        if (artW < 8 || artH < 8) return null
        return Result(bestBlockW, bestBlockH, artW, artH, bestOx, bestOy)
    }

    /** 梯度曲线抽样行/列上限：200 条足够让尖峰稳定，耗时与图片高度解耦 */
    private const val GRADIENT_LINES = 200

    /**
     * 边界梯度曲线：`out[i]` = 第 i 列（行）与前一列（行）的三通道差之和。
     * 像素画的块边界在这里是一串等距尖峰，照片是平坦噪声。
     *
     * 用 RGB 三通道差而不是亮度差：浅粉和淡黄亮度几乎一样，按亮度算
     * 这条边界就是"看不见"的，网格会锁到 2 倍块长上（24px 而不是 12px）。
     */
    private fun gradientProfile(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        horizontal: Boolean,
    ): DoubleArray {
        val len = if (horizontal) srcW else srcH
        val lines = if (horizontal) srcH else srcW
        val out = DoubleArray(len)
        val lineStep = max(1, lines / GRADIENT_LINES)
        var line = 0
        while (line < lines) {
            var prev = pixels[if (horizontal) line * srcW else line]
            var i = 1
            while (i < len) {
                val cur = if (horizontal) pixels[line * srcW + i] else pixels[i * srcW + line]
                out[i] += abs((cur shr 16 and 0xFF) - (prev shr 16 and 0xFF)) +
                    abs((cur shr 8 and 0xFF) - (prev shr 8 and 0xFF)) +
                    abs((cur and 0xFF) - (prev and 0xFF))
                prev = cur
                i++
            }
            line += lineStep
        }
        return out
    }

    /**
     * 固定块长 [b]，在相位 0..b（步长 0.5）里找边界梯度均值最大的偏移。
     * 目标值减去整体均值，避免"采样点越多分越高"的偏好。
     */
    private fun bestOffset(profile: DoubleArray, b: Double): Pair<Int, Double> {
        val len = profile.size
        if (len < 16) return 0 to Double.NEGATIVE_INFINITY
        val baseline = profile.average()
        var bestScore = Double.NEGATIVE_INFINITY
        var bestOff = 0
        var off = 0.0
        while (off < b) {
            var sum = 0.0
            var cnt = 0
            var k = 1
            while (true) {
                val pos = off + k * b
                if (pos >= len - 1) break
                // 线性插值：块长是小数（801/66），四舍五入到整数会让整条曲线
                // 在 ±1px 上抖动，得分数值完全取决于取整运气。
                val i0 = pos.toInt()
                val f = pos - i0
                sum += profile[i0] * (1 - f) + profile[i0 + 1] * f - baseline
                cnt++
                k++
            }
            if (cnt >= 8) {
                val score = sum / cnt
                if (score > bestScore) {
                    bestScore = score
                    bestOff = off.roundToInt()
                }
            }
            off += 0.5
        }
        return bestOff to bestScore
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
