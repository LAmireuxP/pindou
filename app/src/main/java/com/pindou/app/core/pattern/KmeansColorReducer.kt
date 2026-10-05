package com.pindou.app.core.pattern

import com.pindou.app.core.color.ColorConversions
import com.pindou.app.core.color.Ciede2000
import kotlin.math.sqrt

/**
 * K-means 颜色数压缩（Lab 空间）：把网格颜色聚成最多 [maxColors] 类，
 * 每格替换为其聚类中心的颜色。用户设置"最大颜色数"时启用。
 */
object KmeansColorReducer {

    fun reduce(grid: IntArray, maxColors: Int, onProgress: (Float) -> Unit = {}): IntArray {
        if (maxColors !in 1..64) return grid
        val distinct = grid.distinct()
        if (distinct.size <= maxColors) return grid

        val labs = Array(distinct.size) { i -> ColorConversions.argbToLab(distinct[i]) }
        val k = maxColors
        // 初始化：从去重颜色里等距取样（确定性，避免随机导致测试不稳定）
        val centers = Array(k) { i ->
            labs[(i.toLong() * (labs.size - 1) / (k - 1).coerceAtLeast(1)).toInt()].copyOf()
        }
        val assign = IntArray(labs.size)

        val iterations = 10
        for (iter in 0 until iterations) {
            var moved = false
            for (i in labs.indices) {
                var best = 0
                var bestD = Double.MAX_VALUE
                for (c in 0 until k) {
                    val d = Ciede2000.deltaE(labs[i], centers[c])
                    if (d < bestD) {
                        bestD = d
                        best = c
                    }
                }
                if (assign[i] != best) {
                    assign[i] = best
                    moved = true
                }
            }
            // 更新中心（Lab 均值；空簇保留原中心）
            val sumL = DoubleArray(k)
            val sumA = DoubleArray(k)
            val sumB = DoubleArray(k)
            val cnt = IntArray(k)
            for (i in labs.indices) {
                val c = assign[i]
                sumL[c] += labs[i][0]
                sumA[c] += labs[i][1]
                sumB[c] += labs[i][2]
                cnt[c]++
            }
            for (c in 0 until k) {
                if (cnt[c] > 0) {
                    centers[c][0] = (sumL[c] / cnt[c]).toFloat()
                    centers[c][1] = (sumA[c] / cnt[c]).toFloat()
                    centers[c][2] = (sumB[c] / cnt[c]).toFloat()
                }
            }
            onProgress((iter + 1).toFloat() / iterations)
            if (!moved && iter > 0) break
        }

        // 聚类中心 Lab → ARGB（简单反解：转 XYZ 近似即可，色差误差远小于色卡步距）
        val centerArgb = Array(k) { c -> labToApproxArgb(centers[c]) }
        val argbToIndex = HashMap<Int, Int>(distinct.size)
        distinct.forEachIndexed { i, argb -> argbToIndex[argb] = assign[i] }
        return IntArray(grid.size) { i -> centerArgb[argbToIndex[grid[i]]!!] }
    }

    /** Lab → sRGB（D65），钳位处理越界。仅用于聚类中心显示，精度要求不高。 */
    fun labToApproxArgb(lab: FloatArray): Int {
        val fy = (lab[0] + 16.0) / 116.0
        val fx = fy + lab[1] / 500.0
        val fz = fy - lab[2] / 200.0
        val xr = if (fx.pow3() > 0.008856) fx.pow3() else (116.0 * fx - 16) / 903.3 * 0.95047
        val yr = if (lab[0] > 8) ((lab[0] + 16.0) / 116.0).pow3() else lab[0] / 903.3
        val zr = if (fz.pow3() > 0.008856) fz.pow3() else (116.0 * fz - 16) / 903.3 * 1.08883
        val x = xr * 0.95047
        val y = yr
        val z = zr * 1.08883
        // XYZ → linear RGB
        val rl = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val gl = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        fun gamma(c: Double): Int {
            val v = if (c <= 0.0031308) 12.92 * c else 1.055 * sqrt(c) - 0.055
            return (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        }
        return (0xFF shl 24) or (gamma(rl) shl 16) or (gamma(gl) shl 8) or gamma(bl)
    }

    private fun Double.pow3(): Double = this * this * this
}
