package com.pindou.app.core.editor

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.palette.BeadPalette

/**
 * 颜色替换 / 相近色合并：纯 Kotlin，可脱离 Android 单测。
 *
 * 三种入口：
 * - [replaceExact]       单色替换（只动一个色号）
 * - [replaceSimilar]     把「源色 + 与源色 ΔE ≤ 阈值的色」一起替换成目标色
 * - [mergeSimilar]       不给目标色，自动把全图相近色并到用量最多的色
 *
 * 全部遵守锁定标记：锁定格不改色，会计入 [Outcome.lockedSkipped]。
 * 复杂度：统计 O(格数)；相近色两两比较 O(已用色数²)（色卡 ≤ 272，可忽略）。
 */
object ColorReplaceOps {

    /** 一条替换明细：旧色 → 新色（均为色卡索引），影响多少格、两色 ΔE */
    data class Move(
        val from: Int,
        val to: Int,
        val cells: Int,
        val deltaE: Double,
    )

    /** 替换结果：新页数据 + 明细；movedCells = 实际改动的格子数 */
    data class Outcome(
        val pages: List<IntArray>,
        val moves: List<Move>,
        val movedCells: Int,
        val lockedSkipped: Int,
    ) {
        val changed: Boolean get() = movedCells > 0
    }

    /** 统计各色号用量（跨页合计，空格 -1 不计） */
    fun usage(pages: List<IntArray>, paletteSize: Int): IntArray {
        val usage = IntArray(paletteSize)
        for (page in pages) for (v in page) if (v in usage.indices) usage[v]++
        return usage
    }

    /** 指定色替换：只把 [from] 改成 [to]，其余色不动 */
    fun replaceExact(
        pages: List<IntArray>,
        locks: List<BooleanArray>,
        from: Int,
        to: Int,
    ): Outcome {
        if (from == to) return emptyOutcome(pages)
        return applyMapping(
            pages = pages,
            locks = locks,
            mapping = mapOf(from to to),
            deltaBySource = mapOf(from to 0.0),
        )
    }

    /**
     * 替换「相似色」：[from] 与所有 ΔE(from, c) ≤ [maxDeltaE] 的已用色一起变成 [to]。
     * maxDeltaE ≤ 0 时退化为单色替换。目标色本身不会被当成源色。
     */
    fun replaceSimilar(
        pages: List<IntArray>,
        locks: List<BooleanArray>,
        palette: BeadPalette,
        from: Int,
        to: Int,
        maxDeltaE: Double,
    ): Outcome {
        if (from !in palette.colors.indices || to !in palette.colors.indices) {
            return emptyOutcome(pages)
        }
        val colors = palette.colors
        val usage = usage(pages, colors.size)
        val sourceLab = colors[from].lab
        val mapping = LinkedHashMap<Int, Int>()
        val deltaBySource = HashMap<Int, Double>()
        for (i in colors.indices) {
            if (i == to || usage[i] == 0) continue
            val d = if (i == from) 0.0 else Ciede2000.deltaE(sourceLab, colors[i].lab)
            if (i == from || (maxDeltaE > 0.0 && d <= maxDeltaE)) {
                mapping[i] = to
                deltaBySource[i] = d
            }
        }
        if (mapping.isEmpty()) return emptyOutcome(pages)
        return applyMapping(pages, locks, mapping, deltaBySource)
    }

    /**
     * 全图合并相近色：没有目标色，按「最相近的一对先并、少用的并入多用的」执行。
     *
     * 与简单的并查集传递合并不同，这里每次合并前都检查两个簇代表色的实际距离，
     * 避免 A~B、B~C 串成 A~C 的超长链把整张图的色相压平。
     */
    fun mergeSimilar(
        pages: List<IntArray>,
        locks: List<BooleanArray>,
        palette: BeadPalette,
        maxDeltaE: Double,
    ): Outcome {
        if (maxDeltaE <= 0.0) return emptyOutcome(pages)
        val colors = palette.colors
        val usage = usage(pages, colors.size)
        val used = colors.indices.filter { usage[it] > 0 }
        if (used.size <= 1) return emptyOutcome(pages)

        // 先把所有色对按 ΔE 升序排好，后面按顺序贪心合并
        val pairs = ArrayList<Pair<Double, Int>>(used.size * (used.size - 1) / 2)
        for (a in used.indices) {
            for (b in a + 1 until used.size) {
                val i = used[a]
                val j = used[b]
                pairs.add(Ciede2000.deltaE(colors[i].lab, colors[j].lab) to (i shl 16 or j))
            }
        }
        pairs.sortBy { it.first }

        val parent = IntArray(colors.size) { it }
        val weight = usage.copyOf()
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

        for ((d, packed) in pairs) {
            if (d > maxDeltaE) break
            val a = packed ushr 16
            val b = packed and 0xFFFF
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) continue
            // 簇代表色之间的真实距离；超过阈值说明这是链式合并，跳过
            val real = Ciede2000.deltaE(colors[ra].lab, colors[rb].lab)
            if (real > maxDeltaE) continue
            val winner = when {
                weight[ra] > weight[rb] -> ra
                weight[rb] > weight[ra] -> rb
                else -> minOf(ra, rb)
            }
            val loser = if (winner == ra) rb else ra
            parent[loser] = winner
            weight[winner] += weight[loser]
        }
        val mapping = LinkedHashMap<Int, Int>()
        val deltaBySource = HashMap<Int, Double>()
        for (i in used) {
            val rep = find(i)
            if (rep != i) {
                mapping[i] = rep
                deltaBySource[i] = Ciede2000.deltaE(colors[i].lab, colors[rep].lab)
            }
        }
        if (mapping.isEmpty()) return emptyOutcome(pages)
        return applyMapping(pages, locks, mapping, deltaBySource)
    }

    // ---------- 内部 ----------

    private fun emptyOutcome(pages: List<IntArray>): Outcome =
        Outcome(pages.map { it.copyOf() }, emptyList(), 0, 0)

    private fun applyMapping(
        pages: List<IntArray>,
        locks: List<BooleanArray>,
        mapping: Map<Int, Int>,
        deltaBySource: Map<Int, Double>,
    ): Outcome {
        val moves = LinkedHashMap<Int, Int>() // from → 实际改动格数
        var lockedSkipped = 0
        val outPages = ArrayList<IntArray>(pages.size)
        for (p in pages.indices) {
            val page = pages[p]
            val lock = locks.getOrNull(p)
            val out = IntArray(page.size)
            for (i in page.indices) {
                val v = page[i]
                val to = mapping[v]
                if (to != null && to != v) {
                    if (lock != null && lock[i]) {
                        lockedSkipped++
                        out[i] = v
                    } else {
                        out[i] = to
                        moves[v] = (moves[v] ?: 0) + 1
                    }
                } else {
                    out[i] = v
                }
            }
            outPages.add(out)
        }
        val records = moves.entries
            .sortedByDescending { it.value }
            .map { (from, cells) ->
                Move(from, mapping.getValue(from), cells, deltaBySource[from] ?: 0.0)
            }
        return Outcome(
            pages = outPages,
            moves = records,
            movedCells = records.sumOf { it.cells },
            lockedSkipped = lockedSkipped,
        )
    }
}
