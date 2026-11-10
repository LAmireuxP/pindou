package com.pindou.app.core.pattern

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions

/**
 * 孤立像素清理：某格与 4 邻域色号均不同、且该色号在整图出现 ≤ [maxCountForOrphan] 次，
 * 替换为邻域色号的众数。锁定格不动。只跑一遍，不做级联。
 *
 * 色差保护：提供 [paletteArgbs] 时，孤立点与替换色的 CIEDE2000 色差 ≥ [keepDeltaE]
 * 则保留原色——那是与周围本质不同的真细节（眼睛、高光），不当作噪点清掉；
 * 色差小的近同色跳变才清理，配合 [SimilarCodeMerger] 消除"像素不稳定"。
 *
 * @param cells 色卡索引矩阵，-1 = 空格
 * @param paletteArgbs 色卡各色 ARGB（按索引）；null = 关闭色差保护（旧行为）
 */
object IsolatedPixelCleaner {

    fun clean(
        cells: IntArray,
        width: Int,
        height: Int,
        locked: Set<Int> = emptySet(),
        maxCountForOrphan: Int = 3,
        paletteArgbs: List<Int>? = null,
        keepDeltaE: Double = 12.0,
    ): IntArray {
        if (width < 3 || height < 3) return cells
        val out = cells.copyOf()

        // 全图色号计数
        val counts = HashMap<Int, Int>()
        for (c in cells) if (c >= 0) counts.merge(c, 1, Int::plus)

        val labCache = HashMap<Int, FloatArray>()
        fun labOf(idx: Int): FloatArray = labCache.getOrPut(idx) {
            ColorConversions.argbToLab(paletteArgbs!![idx])
        }

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val cur = cells[idx]
                if (cur < 0 || idx in locked) continue
                if (counts.getOrDefault(cur, 0) > maxCountForOrphan) continue

                val up = cells[idx - width]
                val down = cells[idx + width]
                val left = cells[idx - 1]
                val right = cells[idx + 1]
                val neighbors = intArrayOf(up, down, left, right)
                if (neighbors.any { it == cur }) continue

                // 邻域众数（忽略 -1）
                val freq = HashMap<Int, Int>()
                for (n in neighbors) if (n >= 0) freq.merge(n, 1, Int::plus)
                val mode = freq.maxByOrNull { it.value }?.key ?: continue

                if (paletteArgbs != null &&
                    Ciede2000.deltaE(labOf(cur), labOf(mode)) >= keepDeltaE
                ) {
                    continue // 与邻域颜色本质不同：真细节，保留
                }
                out[idx] = mode
            }
        }
        return out
    }
}
