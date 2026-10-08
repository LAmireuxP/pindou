package com.pindou.app.core.pattern

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions

/**
 * 相似色号合并：整图中 CIEDE2000 色差小于阈值的两个用色，把用量少的一方并入用量多的一方。
 *
 * 动机：渐变区域（深色背景、发丝过渡）的源色落在两个几乎相同的色卡色的分界附近，
 * 逐格独立映射会在它们之间来回跳——图案出现"像素不稳定"的噪点，采购清单多出幽灵色号。
 * 合并后同一区域色号唯一，视觉更稳，买豆也少买几种。
 *
 * 算法：反复合并当前用色集合中 ΔE 最小且 < [maxDeltaE] 的一对（稀有 → 常用），
 * 直到最小 ΔE 达不到阈值。用色数 ≤ 64，两两比较的代价可以忽略。空格（-1）不参与。
 *
 * @param paletteArgbs 色卡各色的 ARGB（按索引）
 * @param maxDeltaE 合并阈值；≤ 0 表示关闭
 */
object SimilarCodeMerger {

    fun merge(
        cells: IntArray,
        paletteArgbs: List<Int>,
        maxDeltaE: Double = 3.0,
    ): IntArray {
        if (maxDeltaE <= 0.0) return cells
        val out = cells.copyOf()

        val usage = HashMap<Int, Int>()
        for (c in out) if (c >= 0) usage.merge(c, 1, Int::plus)
        if (usage.size < 2) return out

        // 用色 Lab 预计算（ΔE 计算用）
        val lab = HashMap<Int, FloatArray>()
        for (idx in usage.keys) lab[idx] = ColorConversions.argbToLab(paletteArgbs[idx])

        while (true) {
            var bestA = -1
            var bestB = -1
            var bestDE = Double.MAX_VALUE
            val used = usage.keys.toList()
            for (i in used.indices) {
                for (j in i + 1 until used.size) {
                    val de = Ciede2000.deltaE(lab[used[i]]!!, lab[used[j]]!!)
                    if (de < bestDE) {
                        bestDE = de
                        bestA = used[i]
                        bestB = used[j]
                    }
                }
            }
            if (bestA < 0 || bestDE >= maxDeltaE) break

            val (rare, common) =
                if (usage.getValue(bestA) <= usage.getValue(bestB)) bestA to bestB else bestB to bestA
            val moved = usage.getValue(rare)
            for (k in out.indices) if (out[k] == rare) out[k] = common
            usage[common] = usage.getValue(common) + moved
            usage.remove(rare)
        }
        return out
    }
}
