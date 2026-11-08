package com.pindou.app.core.pattern

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions

/**
 * 最终限色：把图纸实际使用的色号数压到 [maxColors] 以内。
 *
 * K-means 的 K 只约束聚类数，聚类中心各自映射到色卡后色号数会重新膨胀
 * （K=24 可映射出 40 种色号），用户"限 24 色"必须在此兜底。
 * 策略：反复取用量最少的色号，并入 CIEDE2000 最近的其他色号，
 * 直到 ≤ maxColors —— 最冷门颜色牺牲掉，主体大色块不动。
 * 复杂度 O(distinct² × 迭代)，distinct ≤ 数十，可忽略。
 */
object ColorLimiter {

    fun limit(cells: IntArray, paletteArgbs: List<Int>, maxColors: Int): IntArray {
        if (maxColors <= 0 || cells.isEmpty() || paletteArgbs.isEmpty()) return cells
        val counts = HashMap<Int, Int>()
        for (c in cells) if (c >= 0) counts.merge(c, 1, Int::plus)
        if (counts.size <= maxColors) return cells

        val labCache = HashMap<Int, FloatArray>()
        fun labOf(idx: Int): FloatArray = labCache.getOrPut(idx) {
            ColorConversions.argbToLab(paletteArgbs[idx])
        }

        val out = cells.copyOf()
        while (counts.size > maxColors) {
            // 用量最少的色号为牺牲方（平局取索引小者，保证确定性）
            val victim = counts.entries.minWithOrNull(
                compareBy({ it.value }, { it.key }),
            )?.key ?: break
            var best = -1
            var bestDe = Double.MAX_VALUE
            var bestCount = -1
            val victimLab = labOf(victim)
            for ((other, otherCount) in counts) {
                if (other == victim) continue
                val de = Ciede2000.deltaE(victimLab, labOf(other))
                // ΔE 最近者胜；平局并入用量更大的一方（色号稳定）
                if (de < bestDe || (de == bestDe && otherCount > bestCount)) {
                    bestDe = de
                    best = other
                    bestCount = otherCount
                }
            }
            if (best < 0) break
            for (i in out.indices) if (out[i] == victim) out[i] = best
            counts[best] = counts.getValue(best) + counts.getValue(victim)
            counts.remove(victim)
        }
        return out
    }
}
