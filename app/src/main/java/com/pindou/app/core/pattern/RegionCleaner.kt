package com.pindou.app.core.pattern

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions

/**
 * 区域清理：把面积过小的同色连通域（照片纹理残留的碎块）整块并入邻接色块。
 *
 * 与 [IsolatedPixelCleaner] 的分工：后者只处理"全图出现 ≤3 次"的孤立单格；
 * 本模块按连通域面积处理 2~N 格的碎块——这是照片转图纸噪感的主要来源。
 * 在色号域（色卡索引）上运行，放在相似色号合并之后，可吃到合并产生的级联效果。
 *
 * 算法：4 连通标记所有区域 → 面积 < [minArea] 的区域找"邻接面积最大的色块"
 * 整块并入（平局取 ΔE 最近者）。与邻域色差 ≥ [keepDeltaE] 的小区域是
 * 真细节（眼睛、高光），保留不并。锁定格所在区域不动。
 * 复杂度 O(格数)，单轮不级联。
 */
object RegionCleaner {

    fun clean(
        cells: IntArray,
        width: Int,
        height: Int,
        minArea: Int,
        paletteArgbs: List<Int>,
        locked: Set<Int> = emptySet(),
        keepDeltaE: Double = 14.0,
    ): IntArray {
        if (width < 2 || height < 2 || minArea <= 1) return cells
        val n = cells.size
        val labels = IntArray(n) { -1 } // -1 = 未标记
        val regions = ArrayList<Region>()

        // 4 连通 flood fill 标记（栈式，避免递归爆栈）
        val stack = IntArray(n)
        for (start in 0 until n) {
            if (labels[start] != -1 || cells[start] < 0) continue
            val id = regions.size
            val color = cells[start]
            var size = 0
            var hasLocked = start in locked
            stack[size++] = start
            labels[start] = id
            var sp = 0
            while (sp < size) {
                val cur = stack[sp++]
                val x = cur % width
                val y = cur / width
                if (x > 0 && cells[cur - 1] == color && labels[cur - 1] == -1) {
                    labels[cur - 1] = id; stack[size++] = cur - 1
                    if (cur - 1 in locked) hasLocked = true
                }
                if (x < width - 1 && cells[cur + 1] == color && labels[cur + 1] == -1) {
                    labels[cur + 1] = id; stack[size++] = cur + 1
                    if (cur + 1 in locked) hasLocked = true
                }
                if (y > 0 && cells[cur - width] == color && labels[cur - width] == -1) {
                    labels[cur - width] = id; stack[size++] = cur - width
                    if (cur - width in locked) hasLocked = true
                }
                if (y < height - 1 && cells[cur + width] == color && labels[cur + width] == -1) {
                    labels[cur + width] = id; stack[size++] = cur + width
                    if (cur + width in locked) hasLocked = true
                }
            }
            regions.add(Region(color, size, hasLocked))
        }
        if (regions.size <= 1) return cells

        // 每个区域的邻接区域计数（面积大者优先合并，平局再比色差）
        val adjacency = Array(regions.size) { HashMap<Int, Int>() }
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                val a = labels[y * width + x]
                val b = labels[y * width + x + 1]
                if (a >= 0 && b >= 0 && a != b) {
                    adjacency[a].merge(b, 1, Int::plus)
                    adjacency[b].merge(a, 1, Int::plus)
                }
            }
        }
        for (y in 0 until height - 1) {
            for (x in 0 until width) {
                val a = labels[y * width + x]
                val b = labels[(y + 1) * width + x]
                if (a >= 0 && b >= 0 && a != b) {
                    adjacency[a].merge(b, 1, Int::plus)
                    adjacency[b].merge(a, 1, Int::plus)
                }
            }
        }

        val out = cells.copyOf()
        val labCache = HashMap<Int, FloatArray>()
        fun labOf(color: Int): FloatArray = labCache.getOrPut(color) {
            ColorConversions.argbToLab(paletteArgbs[color])
        }

        var merged = false
        for (id in regions.indices) {
            val region = regions[id]
            if (region.size >= minArea || region.hasLocked || region.color < 0) continue
            if (adjacency[id].isEmpty()) continue // 包围全空格（无邻接色块）：不动
            // 邻接候选：面积最大者优先，平局取 ΔE 最近
            var bestLabel = -1
            var bestArea = -1
            var bestDe = Double.MAX_VALUE
            val curLab = labOf(region.color)
            for ((other, _) in adjacency[id]) {
                val ro = regions[other]
                if (ro.color < 0) continue
                if (ro.size > bestArea) {
                    bestArea = ro.size
                    bestLabel = other
                    bestDe = Ciede2000.deltaE(curLab, labOf(ro.color))
                } else if (ro.size == bestArea) {
                    val de = Ciede2000.deltaE(curLab, labOf(ro.color))
                    if (de < bestDe) {
                        bestLabel = other
                        bestDe = de
                    }
                }
            }
            if (bestLabel < 0) continue
            // 色差保护：与最大邻块本质不同 → 真细节，保留
            if (bestDe >= keepDeltaE) continue
            for (i in 0 until n) {
                if (labels[i] == id) out[i] = regions[bestLabel].color
            }
            merged = true
        }
        return if (merged) out else cells
    }

    /** 未手动指定时的自适应最小区域面积：格数的 0.4%，钳制 4..24（真细节由 keepDeltaE 保护） */
    fun adaptiveMinArea(cellCount: Int): Int =
        (cellCount * 0.004).toInt().coerceIn(4, 24)

    private class Region(val color: Int, val size: Int, val hasLocked: Boolean)
}
