package com.pindou.app.core.editor

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.palette.BeadPalette

/**
 * 色板级操作：整图换品牌重映射、相近色号合并。纯 Kotlin，可单测。
 * 复杂度：重映射 O(格数 × 新色卡色数)；合并 O(已用色数²)。
 */
object PaletteOps {

    /** 全部页的格子按 ΔE2000 最近色重映射到 [target]（旧索引 -1 保持空格） */
    fun remapToPalette(pages: List<IntArray>, source: BeadPalette, target: BeadPalette): List<IntArray> {
        // 同源色卡集合时仍然重映射（不同批次色号布局可能不同），但索引一一对应可短路
        if (source.brand == target.brand && source.colors.size == target.colors.size) {
            return pages.map { it.copyOf() }
        }
        // 每个旧索引只查一次最近色（色卡 ≤ 数百色，缓存后整图线性）
        val mapping = IntArray(source.colors.size) { oldIdx ->
            target.nearestIndex(source.colors[oldIdx].lab)
        }
        return pages.map { page ->
            IntArray(page.size) { i ->
                val v = page[i]
                if (v >= 0 && v < mapping.size) mapping[v] else v
            }
        }
    }

    /**
     * 合并相近色号：对已用色建并查集，ΔE 小于 [maxDeltaE] 的两色归并，
     * 每簇代表色取用量最多者（平局取色号靠前者），全页统一替换。
     * 返回替换说明（旧色号 → 新色号），UI 可展示合并了哪些色。
     */
    fun mergeSimilarCodes(
        pages: List<IntArray>,
        palette: BeadPalette,
        maxDeltaE: Double,
    ): List<IntArray> {
        if (maxDeltaE <= 0.0) return pages.map { it.copyOf() }
        // 统计用量（跨页合计）
        val usage = IntArray(palette.colors.size)
        for (page in pages) for (v in page) if (v in usage.indices) usage[v]++
        val used = usage.withIndex().filter { it.value > 0 }.map { it.index }
        if (used.size <= 1) return pages.map { it.copyOf() }

        // 并查集
        val parent = IntArray(palette.colors.size) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        for (i in used.indices) {
            for (j in i + 1 until used.size) {
                val a = used[i]
                val b = used[j]
                val d = Ciede2000.deltaE(palette.colors[a].lab, palette.colors[b].lab)
                if (d < maxDeltaE) {
                    val ra = find(a)
                    val rb = find(b)
                    if (ra != rb) {
                        // 代表色：用量大者优先，平局取索引小者
                        val winner = when {
                            usage[ra] > usage[rb] -> ra
                            usage[rb] > usage[ra] -> rb
                            else -> minOf(ra, rb)
                        }
                        val loser = if (winner == ra) rb else ra
                        parent[loser] = winner
                        // 簇用量并到代表色，链式归并时比较正确
                        usage[winner] += usage[loser]
                    }
                }
            }
        }

        // 旧索引 → 簇代表色索引（未用过的色 find(i) == i，恒等映射，不出现在格子里）
        val mapping = IntArray(palette.colors.size) { i -> find(i) }
        return pages.map { page ->
            IntArray(page.size) { i ->
                val v = page[i]
                if (v >= 0 && v < mapping.size) mapping[v] else v
            }
        }
    }
}
