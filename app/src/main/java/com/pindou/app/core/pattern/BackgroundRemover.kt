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
 * 判定分三层：
 *  1) 背景色模型固定：取四边窄带的主色簇心，整张图都拿它做参考，
 *     不逐格更新参考色，避免颜色沿渐变一路漂移进主体；
 *  2) 主体剪影保护：与背景色差远大于容差的格子算"确定主体"，
 *     先膨胀 2 格再补洞，得到主体剪影。落在剪影里的格子一律不删；
 *  3) 去掉之后前景不足全图 3% 时视为误判，整体放弃移除。
 *
 * 复杂度：O(n) 两遍泛洪 + O(band·k) 边界聚类。
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
     * 白底 + 一层淡粉光晕的图与白底色差很小，单看色差会被整片判成背景。
     * 加上这条之后：格子必须和背景一样"没颜色"才可能被删。淡粉比白底
     * 彩度高 2.5 以上就保留；真有彩色背景（蓝底、粉底）时背景本身彩度
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
        // 会从对角缝钻进主体内部，把环内的同色区域一起清掉。
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
     * 高分辨率检测背景，再落回目标网格。
     *
     * 白主体 + 黑色细描边的插画，在目标网格上描边往往不到 1 格宽：
     * 4 连通泛洪会从描边的断口钻进主体内部，把主体里的同色区域一起清掉。
     *
     * 这里先在 [scale] 倍网格上求掩码——描边变成 2~3 格宽，剪影能闭合，
     * 补洞阶段就能把主体内部保护住；再按「子格全部是背景才算背景」降采样。
     * 跨描边的目标格永远不会被判成背景，描边不会缺角。
     *
     * 复杂度：高分辨率采样 O(像素) + 两次 O(子格数)；78×78 图纸约 20ms。
     */
    fun applyToCellsFromPixels(
        cells: IntArray,
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        w: Int,
        h: Int,
        tolerance: Double = 12.0,
        scale: Int = 3,
        grid: IntArray? = null,
    ): IntArray {
        if (scale <= 1) return applyToCells(cells, grid ?: return cells.copyOf(), w, h, tolerance)
        val mask = detectFromPixels(pixels, srcW, srcH, w, h, tolerance) ?: return cells.copyOf()
        if (mask.none { it }) return cells.copyOf()
        val out = cells.copyOf()
        for (i in 0 until min(out.size, mask.size)) if (mask[i]) out[i] = -1
        return out
    }

        /**
     * 掩码在源图尺度上计算。
     *
     * 78 格对应 1280px 时一格约 16px，而黑描边只有几个像素宽，目标网格上
     * 看不出闭合轮廓。掩码在源图尺度上算，才能沿描边把内外分开，避免
     * 泛洪顺着伪背景钻进主体。
     */
    private fun detectFromPixels(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        w: Int,
        h: Int,
        tolerance: Double,
    ): BooleanArray? {
        if (w <= 0 || h <= 0 || srcW <= 0 || srcH <= 0) return null
        if (pixels.size < srcW * srcH) return null
        // ImagePreprocessor.sample 的网格上限是 500×500，工作图按长边收进来
        var rw = max(w * 4, 320).coerceAtMost(srcW).coerceAtLeast(8)
        var rh = ((rw.toLong() * srcH) / srcW).toInt().coerceAtLeast(8)
        val fit = minOf(1.0, 500.0 / rw, 500.0 / rh)
        if (fit < 1.0) {
            rw = max(8, (rw * fit).toInt())
            rh = max(8, (rh * fit).toInt())
        }
        val work = ImagePreprocessor.sample(pixels, srcW, srcH, rw, rh, FilterLevel.NONE).argb
        val n = rw * rh
        val lab = FloatArray(n * 3)
        for (i in 0 until n) {
            val c = work[i]
            toLab(
                ((c shr 16) and 0xFF) / 255f,
                ((c shr 8) and 0xFF) / 255f,
                (c and 0xFF) / 255f,
                lab, i * 3,
            )
        }
        val band = (min(rw, rh) / 50).coerceIn(2, 12)
        val border = ArrayList<Int>(n / 3)
        for (y in 0 until rh) for (x in 0 until rw) {
            if (x < band || y < band || x >= rw - band || y >= rh - band) border.add(y * rw + x)
        }
        if (border.size < 8) return null
        val bgLab = dominantBorderColor(lab, border)
        val bgChroma = sqrt(bgLab[1] * bgLab[1] + bgLab[2] * bgLab[2])
        val chromaCut = bgChroma + BG_CHROMA_SLACK
        val strongCut = tolerance * 2.2

        val bgLike = BooleanArray(n)
        val strong = BooleanArray(n)
        for (i in 0 until n) {
            val a = lab[i * 3 + 1]
            val b = lab[i * 3 + 2]
            val d = deltaE76(lab, i * 3, bgLab, 0)
            if (d > strongCut) {
                strong[i] = true
                continue
            }
            if (sqrt(a * a + b * b) > chromaCut) continue
            bgLike[i] = d <= tolerance
        }

        val bgMask = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (!bgMask[i] && bgLike[i]) {
                bgMask[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until rw) {
            push(x)
            push((rh - 1) * rw + x)
        }
        for (y in 0 until rh) {
            push(y * rw)
            push(y * rw + rw - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % rw
            val y = i / rw
            if (x > 0) push(i - 1)
            if (x < rw - 1) push(i + 1)
            if (y > 0) push(i - rw)
            if (y < rh - 1) push(i + rw)
        }
        val removed = bgMask.count { it }
        // 几乎全被删 = 背景模型吃掉了整张图（纯色底/无主体），宁可不删
        if (removed == 0 || removed > (n * 0.98).toInt()) return BooleanArray(w * h)

        val total = IntArray(w * h)
        val hits = IntArray(w * h)
        val hard = IntArray(w * h)
        for (y in 0 until rh) {
            val ty = (y.toLong() * h / rh).toInt().coerceIn(0, h - 1)
            val row = y * rw
            val trow = ty * w
            for (x in 0 until rw) {
                val tx = (x.toLong() * w / rw).toInt().coerceIn(0, w - 1)
                val t = trow + tx
                total[t]++
                if (bgMask[row + x]) hits[t]++
                if (strong[row + x]) hard[t]++
            }
        }
        val out = BooleanArray(w * h)
        for (i in out.indices) {
            val t = total[i]
            if (t <= 0) continue
            val mostlyBg = hits[i] * 5 >= t * 4
            val hasEdge = hard[i] * 50 >= t
            out[i] = mostlyBg && !hasEdge
        }
        // 背景里的 JPEG 噪点、半透明水印会让个别格子满足"格内有强边缘"，留成孤岛。
        // 4 连通扫一遍，面积低于阈值的碎块直接判成背景。
        // 阈值 max(4, 全图 0.8%)：78×78 → 48 格，水印残块和边缘噪点都在此列；
        // 主体是同一个连通块（同一张图 2683 格），不会被误删。
        val minKeep = max(4, (w * h * 0.008).toInt())
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val comp = ArrayList<Int>(64)
        for (start in out.indices) {
            if (seen[start] || out[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            comp.clear()
            while (sp > 0) {
                val i = stack[--sp]
                comp.add(i)
                val x = i % w
                val y = i / w
                if (x > 0 && !seen[i - 1] && !out[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && !seen[i + 1] && !out[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && !seen[i - w] && !out[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && !seen[i + w] && !out[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            if (comp.size < minKeep) for (i in comp) out[i] = true
        }
        return out
    }

    /**
     * 高分辨率背景掩码 → 目标网格（子格全为背景才是背景）。
     * 采样失败返回 null；返回空数组表示没检出背景。
     */
    fun detectAtScale(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        w: Int,
        h: Int,
        tolerance: Double,
        scale: Int,
    ): BooleanArray? {
        if (w <= 0 || h <= 0 || scale <= 1) return null
        val sw = w * scale
        val sh = h * scale
        // ImagePreprocessor.sample 的网格上限 500；超了退回纯网格判定
        if (sw > 500 || sh > 500) return null
        // 子网格上限：再大只是重复计算，描边早已闭合
        if (sw.toLong() * sh > 1024L * 1024L) return null
        val hi = ImagePreprocessor.sample(pixels, srcW, srcH, sw, sh, FilterLevel.NONE)
        val hiMask = detect(hi.argb, sw, sh, tolerance)
        if (hiMask.none { it }) return hiMask
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var all = true
                for (dy in 0 until scale) {
                    if (!all) break
                    val sy = y * scale + dy
                    for (dx in 0 until scale) {
                        if (!hiMask[sy * sw + x * scale + dx]) {
                            all = false
                            break
                        }
                    }
                }
                out[y * w + x] = all
            }
        }
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
