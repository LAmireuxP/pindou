package com.pindou.app.core.pattern

import com.pindou.app.core.color.ColorConversions
import com.pindou.app.core.color.Ciede2000
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * K-means 颜色量化（Lab 空间）：把网格颜色聚成最多 [maxColors] 类，
 * 每格替换为其聚类中心的颜色。
 *
 * 在管线中**始终启用**（未手动限色时按 [adaptiveClusterCount] 自适应 K）：
 * 这是"先聚成稳定的中间表示、再统一映射色号"思路的核心——同一聚类的格子
 * 必然映射到同一色号，小尺寸下图源块内颜色杂导致的跳号噪点从结构上消除。
 *
 * 性能：迭代用 Lab 欧氏距离（快），最终指派用 CIEDE2000（感知准确）；
 * 播种按明度排序后等距取样，确定且覆盖明暗全域。
 */
object KmeansColorReducer {

    /** 未手动限色时的自适应聚类数：√格数 × 1.2，钳制 10..32 */
    fun adaptiveClusterCount(cellCount: Int): Int =
        (sqrt(cellCount.toDouble()) * 1.2).roundToInt().coerceIn(10, 32)

    /**
     * @param salientColors 显著细节色集合（降采样标记的高对比少数派色，如眼睛）。
     *   聚类后若其与所属中心的 ΔE ≥ 12，恢复原色 —— 小面积高对比细节不被大簇吸收。
     */
    fun reduce(
        grid: IntArray,
        maxColors: Int,
        salientColors: Set<Int>? = null,
        onProgress: (Float) -> Unit = {},
    ): IntArray {
        if (maxColors !in 1..64) return grid
        val distinct = grid.distinct()
        if (distinct.size <= maxColors) return grid

        val labs = Array(distinct.size) { i -> ColorConversions.argbToLab(distinct[i]) }
        val k = maxColors
        // 初始化：按明度（L）排序后等距取样（确定性，覆盖明暗全域）
        val order = labs.withIndex().sortedBy { it.value[0] }
        val centers = Array(k) { i ->
            order[(i.toLong() * (order.size - 1) / (k - 1).coerceAtLeast(1)).toInt()].value.copyOf()
        }
        val assign = IntArray(labs.size)

        val iterations = 16
        for (iter in 0 until iterations) {
            var moved = false
            for (i in labs.indices) {
                var best = 0
                var bestD = Double.MAX_VALUE
                val li = labs[i]
                for (c in 0 until k) {
                    val lc = centers[c]
                    val dl = (li[0] - lc[0]).toDouble()
                    val da = (li[1] - lc[1]).toDouble()
                    val db = (li[2] - lc[2]).toDouble()
                    val d = dl * dl + da * da + db * db
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

        // 最终指派：CIEDE2000 感知最近中心（比欧氏更符合人眼分区）
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
            assign[i] = best
        }

        // 聚类中心 Lab → ARGB（简单反解：转 XYZ 近似即可，色差误差远小于色卡步距）
        val centerArgb = Array(k) { c -> labToApproxArgb(centers[c]) }

        // 显著色救援：显著细节色若被吸进 ΔE 很大的簇，恢复其原色（保住眼睛等真细节）
        val outColor = IntArray(distinct.size)
        distinct.forEachIndexed { i, argb ->
            val c = assign[i]
            val rescue = salientColors != null && argb in salientColors &&
                Ciede2000.deltaE(labs[i], centers[c]) >= 12.0
            outColor[i] = if (rescue) distinct[i] else centerArgb[c]
        }

        val argbToIndex = HashMap<Int, Int>(distinct.size)
        distinct.forEachIndexed { i, argb -> argbToIndex[argb] = i }
        return IntArray(grid.size) { i -> outColor[argbToIndex[grid[i]]!!] }
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
