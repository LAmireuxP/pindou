package com.pindou.app.core.pattern

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions

/** 降采样结果：每格颜色 + 是否为"显著细节格"（块内高对比少数派色块，如眼睛、高光） */
class DownsampleResult(val colors: IntArray, val salient: BooleanArray)

/**
 * 主导色降采样：把源图像素压缩为 W×H 网格色块。
 *
 * 每格取块内主导色：像素按每通道 4-bit 量化分桶（4096 桶），取众数桶内像素的均值。
 * 相比最近邻采样可显著减少噪点（Zippland 项目验证过的结论）。
 *
 * 显著细节检测：小尺寸时每个格子的采样块很大，眼睛、高光等小面积高对比色块
 * 会被"取众数"直接吞掉。若块内存在与主导色 ΔE ≥ [saliencyDeltaE]、且像素占比
 * ≥ [saliencyFraction] 的少数派色桶，则该格改用少数派色并标记 salient ——
 * 下游 K-means 据此做"显著色救援"，避免小细节被大簇吸收。
 */
object DominantColorDownsampler {

    fun downsample(pixels: IntArray, srcW: Int, srcH: Int, gridW: Int, gridH: Int): IntArray =
        downsampleDetailed(pixels, srcW, srcH, gridW, gridH).colors

    fun downsampleDetailed(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        gridW: Int,
        gridH: Int,
        saliencyDeltaE: Double = 15.0,
        saliencyFraction: Double = 0.15,
    ): DownsampleResult {
        require(gridW in 1..500 && gridH in 1..500) { "网格尺寸越界: ${gridW}x$gridH" }
        val colors = IntArray(gridW * gridH)
        val salient = BooleanArray(gridW * gridH)
        val blockW = srcW.toDouble() / gridW
        val blockH = srcH.toDouble() / gridH

        // 桶计数复用（4096 桶：每通道高 4 位）
        val counts = IntArray(4096)
        val sumsR = IntArray(4096)
        val sumsG = IntArray(4096)
        val sumsB = IntArray(4096)
        // 本格触碰过的桶列表（复用，避免每格分配 ArrayList；最多 4096 个）
        val touched = IntArray(4096)
        var touchedCount = 0

        for (gy in 0 until gridH) {
            val y0 = (gy * blockH).toInt()
            val y1 = (((gy + 1) * blockH).toInt()).coerceAtMost(srcH)
            for (gx in 0 until gridW) {
                val x0 = (gx * blockW).toInt()
                val x1 = (((gx + 1) * blockW).toInt()).coerceAtMost(srcW)
                var best = 0xFF shl 24
                var isSalient = false
                if (y1 > y0 && x1 > x0) {
                    // 只重置上一格触碰过的桶（比每格 Arrays.fill 全量清零快一个量级）
                    for (i in 0 until touchedCount) {
                        val b = touched[i]
                        counts[b] = 0
                        sumsR[b] = 0
                        sumsG[b] = 0
                        sumsB[b] = 0
                    }
                    touchedCount = 0
                    var maxCount = 0
                    var bestBucket = -1
                    var blockPixels = 0
                    for (y in y0 until y1) {
                        val row = y * srcW
                        for (x in x0 until x1) {
                            val p = pixels[row + x]
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                            val c = ++counts[bucket]
                            if (c == 1) touched[touchedCount++] = bucket
                            sumsR[bucket] += r
                            sumsG[bucket] += g
                            sumsB[bucket] += b
                            if (c > maxCount) {
                                maxCount = c
                                bestBucket = bucket
                            }
                            blockPixels++
                        }
                    }
                    if (bestBucket >= 0) {
                        best = meanOf(bestBucket, maxCount, sumsR, sumsG, sumsB)
                        // 显著细节检测：在"占比达标"的少数派桶里找与主导色 ΔE 达标者
                        if (saliencyFraction > 0.0 && saliencyDeltaE > 0.0) {
                            val minCount = (blockPixels * saliencyFraction).toInt().coerceAtLeast(1)
                            val dominantLab = ColorConversions.argbToLab(
                                meanOf(bestBucket, maxCount, sumsR, sumsG, sumsB),
                            )
                            var candBucket = -1
                            var candCount = 0
                            for (ti in 0 until touchedCount) {
                                val b2 = touched[ti]
                                val c2 = counts[b2]
                                if (b2 == bestBucket || c2 < minCount) continue
                                val de = Ciede2000.deltaE(
                                    dominantLab,
                                    ColorConversions.argbToLab(meanOf(b2, c2, sumsR, sumsG, sumsB)),
                                )
                                if (de >= saliencyDeltaE && c2 > candCount) {
                                    candBucket = b2
                                    candCount = c2
                                }
                            }
                            if (candBucket >= 0) {
                                best = meanOf(candBucket, candCount, sumsR, sumsG, sumsB)
                                isSalient = true
                            }
                        }
                    }
                }
                colors[gy * gridW + gx] = best
                salient[gy * gridW + gx] = isSalient
            }
        }
        return DownsampleResult(colors, salient)
    }

    private fun meanOf(bucket: Int, count: Int, sumsR: IntArray, sumsG: IntArray, sumsB: IntArray): Int {
        // 注意：不能用 -1 作"未赋值"哨兵——白色 0xFFFFFFFF 恰好等于 -1！
        val r = sumsR[bucket] / count
        val g = sumsG[bucket] / count
        val b = sumsB[bucket] / count
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
