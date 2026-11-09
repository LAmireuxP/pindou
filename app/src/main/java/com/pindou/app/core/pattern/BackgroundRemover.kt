package com.pindou.app.core.pattern

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 背景移除：只删「图像主体之外」的背景，主体内部同色区域不动。
 *
 * 旧实现是"从四边 BFS，每步拿当前格颜色当参考"，等于允许颜色逐格漂移：
 * 白 → 淡粉 → 花瓣，一圈圈腐蚀进画面，一张 5382 格的花环猫只剩 1136 格。
 *
 * 现在的判定分三层：
 *  1) 背景色模型固定：取四边窄带（band）的主色簇心，整张图都拿它做参考，
 *     不允许逐格改参考色，颜色漂移链直接断掉；
 *  2) 主体剪影保护：与背景色差远大于容差的格子算"确定主体"，
 *     先膨胀 2 格再补洞，得到主体剪影。落在剪影里的格子一律不删——
 *     猫脸内部的白、花瓣之间的淡色、细描边都靠这一层保住；
 *  3) 去掉之后前景不足全图 3% 时视为误判（纯色图/背景图），整体放弃移除。
 *
 * 复杂度：O(n) 两遍泛洪 + O(band·k) 边界聚类，78×69 网格 1ms 量级。
 */
object BackgroundRemover {

    /** 边界取样带宽上限（格）；过宽会把贴着边缘的主体色算成背景 */
    private const val MAX_BAND = 4

    /** 主体剪影膨胀半径（格）：盖住花瓣的淡色描边和抗锯齿过渡 */
    private const val SILHOUETTE_GROW = 2

    /** 前景低于全图该比例 → 判定为误删，放弃移除 */
    private const val MIN_FOREGROUND_SHARE = 0.03

    /**
     * 允许比背景"更彩"多少（Lab 彩度差）。
     *
     * 白底 + 一层淡粉光晕的图（#F6EAEE，彩度 3.6）和白底只差 ΔE 6，单看
     * 色差会被整片判成背景铲掉——用户说的"右下角粉色变成背景白"就是这条。
     * 加上这条之后：格子必须和背景一样"没颜色"才可能被删。淡粉比白底彩
     * 2.5 以上就被当成前景保留，真有彩色背景（蓝底、粉底）时背景本身彩度
     * 高，容差跟着背景走，照样删得掉。
     */
    private const val BG_CHROMA_SLACK = 2.5

    /**
     * 计算背景掩码（true = 该格是背景，应清空）。
     *
     * @param grid 网格采样色（ARGB），长度 ≥ w*h
     * @param tolerance 与背景主色的 Lab ΔE 容差（UI 滑杆 4~30）
     */
    fun detect(grid: IntArray, w: Int, h: Int, tolerance: Double = 12.0): BooleanArray {
        val n = w * h
        val empty = BooleanArray(n.coerceAtLeast(0))
        if (n <= 0 || w < 5 || h < 5 || grid.size < n) return empty

        val lab = FloatArray(n * 3)
        for (i in 0 until n) {
            val c = grid[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            toLab(r, g, b, lab, i * 3)
        }

        // ① 边界窄带主色：取中位色 + 邻域簇，避免把一条边上的主体当成背景
        val band = (min(w, h) / 16).coerceIn(1, MAX_BAND)
        val border = ArrayList<Int>(n / 2)
        for (y in 0 until h) for (x in 0 until w) {
            if (x < band || y < band || x >= w - band || y >= h - band) border.add(y * w + x)
        }
        if (border.size < 8) return empty
        val bgLab = dominantBorderColor(lab, border)
        val bgChroma = sqrt(bgLab[1] * bgLab[1] + bgLab[2] * bgLab[2])
        val chromaCut = bgChroma + BG_CHROMA_SLACK

        // ② 背景相似 + 确定主体（ΔE 超过 2 倍容差）
        val bgLike = BooleanArray(n)
        val confident = BooleanArray(n)
        val confidentCut = tolerance * 2.0
        for (i in 0 until n) {
            val d = deltaE76(lab, i * 3, bgLab, 0)
            val a = lab[i * 3 + 1]
            val b = lab[i * 3 + 2]
            val chroma = sqrt(a * a + b * b)
            bgLike[i] = d <= tolerance && chroma <= chromaCut
            confident[i] = d > confidentCut
        }

        // ③ 主体剪影：膨胀 confident + 补洞（洞 = 剪影内部，不外泄到画面边缘）
        val grown = dilate(confident, w, h, SILHOUETTE_GROW)
        val silhouette = fillHoles(grown, w, h)

        // ③b 前景闭合：所有"非背景色"的格子先向外膨胀 1 格，泛洪不许穿过。
        //
        // 画像素之间常有一格宽的白缝（花瓣只在一个角上相接），8 邻域泛洪
        // 会从对角缝钻进花环内部，把主体环内的白色一起清掉——用户看到的
        // "和内背景同样的白也被去掉了"就是这条路径。先把前景缝焊死，
        // 泛洪就只能沿真正的外部背景走。
        val closedFg = dilate(BooleanArray(n) { !bgLike[it] }, w, h, 1)

        // ④ 只从边界泛洪，且只走 bgLike、不进剪影、不穿闭合后的前景。
        //    邻域用 4 连通：对角相接的前景已经挡住，不需要再给泛洪留斜穿口。
        val mask = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (!mask[i] && bgLike[i] && !silhouette[i] && !closedFg[i]) {
                mask[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }

        // ⑤ 前景外圈「纯背景色」清理：膨胀保护带会留下 1~2 格白晕，
        //    只吃与背景几乎同色（ΔE ≤ 容差×0.35）且与已移除区相连的格子，
        //    淡粉花瓣（ΔE 通常 6~12）不会被这一步吃掉。
        val strictCut = max(2.0, tolerance * 0.35)
        val queue2 = IntArray(n)
        var h2 = 0
        var t2 = 0
        fun pushHalo(i: Int) {
            if (mask[i] || confident[i]) return
            if (deltaE76(lab, i * 3, bgLab, 0) > strictCut) return
            mask[i] = true
            queue2[t2++] = i
        }
        for (i in 0 until n) {
            if (mask[i] || confident[i]) continue
            val x = i % w
            val y = i / w
            val touching = (x > 0 && mask[i - 1]) || (x < w - 1 && mask[i + 1]) ||
                (y > 0 && mask[i - w]) || (y < h - 1 && mask[i + w])
            if (touching) pushHalo(i)
        }
        while (h2 < t2) {
            val i = queue2[h2++]
            val x = i % w
            val y = i / w
            // 4 连通：斜角不算相连，避免白晕从对角缝重新渗回主体内部
            if (x > 0) pushHalo(i - 1)
            if (x < w - 1) pushHalo(i + 1)
            if (y > 0) pushHalo(i - w)
            if (y < h - 1) pushHalo(i + w)
        }

        // ⑥ 焊缝残留清理：前景闭合会在主体外沿留下一些到不了边界的
        //    背景色小岛（几格白点）。按 4 连通分块，面积小于全图 1% 的
        //    背景色碎块直接清掉；花环内部的大片白区面积远大于阈值，
        //    且落在剪影里，不会被这一刀误伤。
        val islandMax = max(4, (n * 0.01).toInt())
        val seen = BooleanArray(n)
        val stack = IntArray(n)
        for (start in 0 until n) {
            if (seen[start] || mask[start] || !bgLike[start] || silhouette[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            val comp = ArrayList<Int>()
            while (sp > 0) {
                val i = stack[--sp]
                comp.add(i)
                val x = i % w
                val y = i / w
                if (x > 0) {
                    val j = i - 1
                    if (!seen[j] && !mask[j] && bgLike[j] && !silhouette[j]) {
                        seen[j] = true; stack[sp++] = j
                    }
                }
                if (x < w - 1) {
                    val j = i + 1
                    if (!seen[j] && !mask[j] && bgLike[j] && !silhouette[j]) {
                        seen[j] = true; stack[sp++] = j
                    }
                }
                if (y > 0) {
                    val j = i - w
                    if (!seen[j] && !mask[j] && bgLike[j] && !silhouette[j]) {
                        seen[j] = true; stack[sp++] = j
                    }
                }
                if (y < h - 1) {
                    val j = i + w
                    if (!seen[j] && !mask[j] && bgLike[j] && !silhouette[j]) {
                        seen[j] = true; stack[sp++] = j
                    }
                }
            }
            if (comp.size <= islandMax) for (i in comp) mask[i] = true
        }

        val removed = mask.count { it }
        if (removed == 0) return empty
        val foreground = n - removed
        // 前景太小 = 背景模型吃掉了整张图（纯色底、无主体），宁可不动
        if (foreground < max(4, (n * MIN_FOREGROUND_SHARE).toInt())) return empty
        return mask
    }

    /** 把背景格对应的色卡索引置 -1，返回新数组（不改入参） */
    fun applyToCells(
        cells: IntArray,
        grid: IntArray,
        w: Int,
        h: Int,
        tolerance: Double = 12.0,
    ): IntArray {
        val mask = detect(grid, w, h, tolerance)
        if (mask.none { it }) return cells.copyOf()
        val out = cells.copyOf()
        for (i in 0 until min(out.size, mask.size)) if (mask[i]) out[i] = -1
        return out
    }

    /**
     * 边界主色：把边界带按 Lab 亮度分成 3 段取中位样本，
     * 再选覆盖格数最多的一段作背景参考。只保留一个参考色是刻意的——
     * 多个参考色会把贴着边缘的淡色花瓣也算成背景。
     */
    private fun dominantBorderColor(lab: FloatArray, border: List<Int>): FloatArray {
        val lum = border.map { lab[it * 3] }.sorted()
        val cuts = floatArrayOf(
            lum[lum.size / 8], lum[lum.size / 2], lum[lum.size * 7 / 8],
        )
        val buckets = Array(3) { ArrayList<Int>() }
        for (i in border) {
            val l = lab[i * 3]
            val b = when {
                abs(l - cuts[0]) <= abs(l - cuts[1]) && abs(l - cuts[0]) <= abs(l - cuts[2]) -> 0
                abs(l - cuts[1]) <= abs(l - cuts[2]) -> 1
                else -> 2
            }
            buckets[b].add(i)
        }
        val main = buckets.maxByOrNull { it.size } ?: return floatArrayOf(100f, 0f, 0f)
        val out = FloatArray(3)
        for (i in main) {
            out[0] += lab[i * 3]
            out[1] += lab[i * 3 + 1]
            out[2] += lab[i * 3 + 2]
        }
        val inv = 1f / main.size
        out[0] *= inv
        out[1] *= inv
        out[2] *= inv
        return out
    }

    /** 方形核膨胀（可分离两遍，避免 (2r+1)^2 扫描） */
    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val n = w * h
        val dilated = BooleanArray(n)
        if (r <= 0) {
            src.copyInto(dilated)
        } else {
            val tmp = BooleanArray(n)
            for (y in 0 until h) for (x in 0 until w) {
                if (!src[y * w + x]) continue
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                for (xx in x0..x1) tmp[y * w + xx] = true
            }
            for (y in 0 until h) for (x in 0 until w) {
                if (!tmp[y * w + x]) continue
                val y0 = max(0, y - r)
                val y1 = min(h - 1, y + r)
                for (yy in y0..y1) dilated[yy * w + x] = true
            }
        }
        return dilated
    }

    /**
     * 补洞：从四边对 !mask 泛洪，没被泛到的非掩码格就是被包住的内部。
     * 猫脸白、花瓣之间的淡色都靠这一步保住。
     */
    private fun fillHoles(maskIn: BooleanArray, w: Int, h: Int): BooleanArray {
        val n = w * h
        val dilated = maskIn.copyOf()
        // 从边界泛洪所有非剪影格，剩下的空洞并进剪影
        val reach = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (!reach[i] && !dilated[i]) {
                reach[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        for (i in 0 until n) if (!dilated[i] && !reach[i]) dilated[i] = true
        return dilated
    }

    private fun toLab(r: Float, g: Float, b: Float, out: FloatArray, o: Int) {
        fun lin(c: Float): Float =
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        val lr = lin(r)
        val lg = lin(g)
        val lb = lin(b)
        var x = (0.4124f * lr + 0.3576f * lg + 0.1805f * lb) / 0.95047f
        var y = (0.2126f * lr + 0.7152f * lg + 0.0722f * lb) / 1.00000f
        var z = (0.0193f * lr + 0.1192f * lg + 0.9505f * lb) / 1.08883f
        x = f(x)
        y = f(y)
        z = f(z)
        out[o] = 116f * y - 16f
        out[o + 1] = 500f * (x - y)
        out[o + 2] = 200f * (y - z)
    }

    private fun f(t: Float): Float = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f

    private fun deltaE76(a: FloatArray, ao: Int, b: FloatArray, bo: Int): Double {
        val dl = (a[ao] - b[bo]).toDouble()
        val da = (a[ao + 1] - b[bo + 1]).toDouble()
        val db = (a[ao + 2] - b[bo + 2]).toDouble()
        return sqrt(dl * dl + da * da + db * db)
    }
}
