package com.pindou.app.core.pattern

/**
 * 主导色降采样：把源图像素压缩为 W×H 网格色块。
 *
 * 每格取块内主导色：像素按每通道 4-bit 量化分桶（4096 桶），取众数桶内像素的均值。
 * 相比最近邻采样可显著减少噪点（Zippland 项目验证过的结论）。
 */
object DominantColorDownsampler {

    /**
     * @param pixels 源图像素（ARGB，行优先）
     * @param srcW/srcH 源图尺寸
     * @param gridW/gridH 目标网格尺寸
     */
    fun downsample(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        gridW: Int,
        gridH: Int,
    ): IntArray {
        require(gridW in 1..500 && gridH in 1..500) { "网格尺寸越界: ${gridW}x$gridH" }
        val out = IntArray(gridW * gridH)
        val blockW = srcW.toDouble() / gridW
        val blockH = srcH.toDouble() / gridH

        // 桶计数复用（4096 桶：每通道高 4 位）
        val counts = IntArray(4096)
        val sumsR = IntArray(4096)
        val sumsG = IntArray(4096)
        val sumsB = IntArray(4096)

        for (gy in 0 until gridH) {
            val y0 = (gy * blockH).toInt()
            val y1 = (((gy + 1) * blockH).toInt()).coerceAtMost(srcH)
            for (gx in 0 until gridW) {
                val x0 = (gx * blockW).toInt()
                val x1 = (((gx + 1) * blockW).toInt()).coerceAtMost(srcW)

                // 默认兜底色（空块才会用到，合法输入不会发生）。
                // 注意：不能用 -1 作"未赋值"哨兵——白色 0xFFFFFFFF 恰好等于 -1！
                var best = 0xFF shl 24
                if (y1 > y0 && x1 > x0) {
                    java.util.Arrays.fill(counts, 0)
                    java.util.Arrays.fill(sumsR, 0)
                    java.util.Arrays.fill(sumsG, 0)
                    java.util.Arrays.fill(sumsB, 0)
                    var maxCount = 0
                    var bestBucket = -1
                    for (y in y0 until y1) {
                        val row = y * srcW
                        for (x in x0 until x1) {
                            val p = pixels[row + x]
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                            val c = ++counts[bucket]
                            sumsR[bucket] += r
                            sumsG[bucket] += g
                            sumsB[bucket] += b
                            if (c > maxCount) {
                                maxCount = c
                                bestBucket = bucket
                            }
                        }
                    }
                    if (bestBucket >= 0) {
                        val r = sumsR[bestBucket] / maxCount
                        val g = sumsG[bestBucket] / maxCount
                        val b = sumsB[bestBucket] / maxCount
                        best = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                out[gy * gridW + gx] = best
            }
        }
        return out
    }
}
