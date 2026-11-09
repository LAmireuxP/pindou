package com.pindou.app.core.pattern

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions
import com.pindou.app.core.palette.BeadPalette
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * 像素画支路的量化器（与照片支路完全分开）。
 *
 * 为什么不能复用照片那套：照片支路把"每格"当成一个样本做 k-means，
 * 再按簇投票。像素画的每个"画像素"在源图上占几十个真实像素，面积差异
 * 巨大（一颗眼珠 1 个画像素，白底 2000 个画像素），按格计权会把簇心
 * 全部拉向浅色——脸被拉成更艳的珊瑚色、花瓣被拉成灰粉，正是"复刻不准"
 * 的来源。这里改成：
 *
 *  1. 按画像素聚类，样本权重 = 该颜色覆盖的画像素数（面积真实权重）；
 *  2. 簇心映射色卡时用同样权重投票，取加权众数；
 *  3. 目标格只做"已映射色号"的面积多数表决，绝不再引入新颜色。
 *
 * 这样整张图最多只有 maxColors 个色号，且每个色号在图上成块，不会
 * 出现同一片花瓣里夹三四种粉的情况。
 *
 * 复杂度：唯一色数 U ≤ 画像素数，U² 只在 k-means++ 初始化里出现；
 * 78 宽网格下 U 通常 < 200，整段毫秒级。
 */
object ArtQuantizer {

    private const val MAX_ITER = 30
    private const val TOLERANCE = 0.02f
    private const val SHORTLIST = 8
    private val SEEDS = longArrayOf(11L, 977L)

    fun quantize(
        art: ArtGrid,
        gridW: Int,
        gridH: Int,
        palette: BeadPalette,
        maxColors: Int,
        excludedCodes: Set<String>,
    ): IntArray {
        val n = art.w * art.h
        if (n == 0) return IntArray(gridW * gridH) { -1 }

        // ① 唯一色统计。丢弃低 3 位做桶键：画像素内部本来就是同色，
        //    3×3 均值后仍有 ±2 的 JPEG 抖动，不并桶会让 45 色变成 120 色。
        //    两遍：先建桶索引确定唯一色数，再按实际大小分配累加数组。
        //    （一遍写死容量会在渐变图/噪点图上越界，桶数没有上界。）
        val bucketToUid = HashMap<Int, Int>(256)
        val uidOfPixel = IntArray(n)
        var uidCount = 0
        for (i in 0 until n) {
            val key = art.argb[i] and 0xF8F8F8
            val uid = bucketToUid.getOrPut(key) { uidCount++ }
            uidOfPixel[i] = uid
        }
        val sumR = DoubleArray(uidCount)
        val sumG = DoubleArray(uidCount)
        val sumB = DoubleArray(uidCount)
        val wgt = DoubleArray(uidCount)
        for (i in 0 until n) {
            val p = art.argb[i]
            val uid = uidOfPixel[i]
            sumR[uid] += ((p shr 16) and 0xFF).toDouble()
            sumG[uid] += ((p shr 8) and 0xFF).toDouble()
            sumB[uid] += (p and 0xFF).toDouble()
            wgt[uid] += 1.0
        }

        val lab = FloatArray(uidCount * 3)
        val totalW = DoubleArray(uidCount)
        for (u in 0 until uidCount) {
            val c = wgt[u]
            totalW[u] = c
            val r = (sumR[u] / c + 0.5).toInt().coerceIn(0, 255)
            val g = (sumG[u] / c + 0.5).toInt().coerceIn(0, 255)
            val b = (sumB[u] / c + 0.5).toInt().coerceIn(0, 255)
            val l = ColorConversions.argbToLab((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            lab[u * 3] = l[0]
            lab[u * 3 + 1] = l[1]
            lab[u * 3 + 2] = l[2]
        }

        // ② 抖动合并：源图是放大过的像素画，同一块色在 JPEG/缩放后会裂成
        //    十几种相邻色（#F8E8ED / #F8E7EF / #F9E9EE…）。这些多出来的颜色
        //    彼此色差不足 3，却会被映射到不同豆色，落格后就是满图胡椒面和
        //    背景上的一层灰。先按 ΔE ≤ 3 贪心合并（重色块优先当代表），
        //    合并后的色数才是这张图"真正的配色数"。
        //    复杂度 O(U²)，U 在 78 宽下通常 < 200。
        val order = (0 until uidCount).sortedByDescending { totalW[it] }
        val groupOf = IntArray(uidCount) { -1 }
        var groupCount = 0
        for (u in order) {
            if (groupOf[u] >= 0) continue
            val g = groupCount++
            groupOf[u] = g
            for (v in order) {
                if (groupOf[v] >= 0) continue
                if (labDistance(lab, u, v) <= NOISE_MERGE_DELTA) groupOf[v] = g
            }
        }
        val gLab = FloatArray(groupCount * 3)
        val gW = DoubleArray(groupCount)
        run {
            val gl = DoubleArray(groupCount)
            val ga = DoubleArray(groupCount)
            val gb = DoubleArray(groupCount)
            for (u in 0 until uidCount) {
                val g = groupOf[u]
                val w = totalW[u]
                gl[g] += lab[u * 3] * w
                ga[g] += lab[u * 3 + 1] * w
                gb[g] += lab[u * 3 + 2] * w
                gW[g] += w
            }
            for (g in 0 until groupCount) {
                val w = if (gW[g] > 0) gW[g] else 1.0
                gLab[g * 3] = (gl[g] / w).toFloat()
                gLab[g * 3 + 1] = (ga[g] / w).toFloat()
                gLab[g * 3 + 2] = (gb[g] / w).toFloat()
                gW[g] = w
            }
        }
        val gOfPixel = IntArray(n) { groupOf[uidOfPixel[it]] }

        // ② 画像素 → 色号。
        //
        //    "无过滤"直接逐色映射：像素画的每个画像素本来就是作者定好的颜色，
        //    聚类会把相邻的几个粉并掉，花环立刻从"层层花瓣"变成"一团珊瑚"。
        //    直接映射时颜色数天然 ≤ 源图色数，且每个色号都是真实最近豆色。
        //
        //    低/中/高才做加权聚类：K 就是用户要的"用色上限"，簇内仍按面积
        //    加权投票，保证簇心被大面积色块主导，不会被几个噪点画像素拉偏。
        val mapDirect = maxColors <= 0 || maxColors >= groupCount
        val artCode = IntArray(n)
        if (mapDirect) {
            val direct = IntArray(groupCount) { g ->
                nearestBead(palette, gLab, g, excludedCodes)
            }
            for (i in 0 until n) artCode[i] = direct[gOfPixel[i]]
        } else {
            val k = maxColors.coerceAtLeast(2).coerceAtMost(groupCount)
            val (labels, centers) = fit(gLab, gW, groupCount, k)

            // 每簇映射色卡：候选 8 个 + 簇内按面积加权投票。
            val shortlist = IntArray(k * SHORTLIST)
            for (c in 0 until k) {
                LabQuantizer.pickShortlist(
                    palette, floatArrayOf(centers[c * 3], centers[c * 3 + 1], centers[c * 3 + 2]),
                    excludedCodes, shortlist, c * SHORTLIST,
                )
            }
            val votes = DoubleArray(k * SHORTLIST)
            val cell = FloatArray(3)
            for (g in 0 until groupCount) {
                val c = labels[g]
                val base = c * SHORTLIST
                var best = 0
                var bestD = Double.MAX_VALUE
                cell[0] = gLab[g * 3]
                cell[1] = gLab[g * 3 + 1]
                cell[2] = gLab[g * 3 + 2]
                val wantChroma =
                    cell[1] * cell[1] + cell[2] * cell[2] > 64f
                for (s in 0 until SHORTLIST) {
                    val idx = shortlist[base + s]
                    if (idx < 0) continue
                    if (wantChroma) {
                        val ca = palette.colors[idx].lab[1]
                        val cb = palette.colors[idx].lab[2]
                        if (ca * ca + cb * cb < 25f) continue
                    }
                    val d = Ciede2000.deltaE(cell, palette.colors[idx].lab)
                    if (d < bestD) {
                        bestD = d
                        best = s
                    }
                }
                votes[base + best] += gW[g]
            }
            val clusterPalette = IntArray(k) { -1 }
            for (c in 0 until k) {
                val base = c * SHORTLIST
                var bestSlot = -1
                var bestVote = 0.0
                for (s in 0 until SHORTLIST) {
                    if (shortlist[base + s] >= 0 && votes[base + s] > bestVote) {
                        bestVote = votes[base + s]
                        bestSlot = s
                    }
                }
                clusterPalette[c] = if (bestSlot >= 0) {
                    shortlist[base + bestSlot]
                } else {
                    nearestCenter(palette, centers, c, excludedCodes)
                }
            }
            for (i in 0 until n) artCode[i] = clusterPalette[labels[gOfPixel[i]]]
        }

        // ④ 去孤点：合并抖动后仍会剩少量单格跳色（花瓣外沿、花枝交叉处）。
        //    只在"本像素源色和邻域平均色差 ≤ 8"时才替换——眼睛、嘴、高光
        //    的源色和周围差几十，不会被吃掉。
        val despeckled = despeckle(artCode, gLab, gOfPixel, art.w, art.h)

        // ⑤ 背景光晕归一：像素画的背景常带一层极淡的粉色渐变（#F8E7EF 这类），
        //    它会被映射成好几个浅灰粉，落到格子上就是四角和底部两条脏带。
        //
        //    只从四边向内做"浅色 + 局部连续"的洪水：渐变本身是连续的，能一路
        //    灌到花环外沿；花环的花瓣和光晕之间有色差跳变，洪水在这里停住。
        //    不用"离主体距离"判据——花环缝隙里的浅色花瓣离饱和色也远，会被
        //    一起吸白，整圈花瓣立刻塌成白色空洞。
        //
        //    阈值取"彩度 ≤ 10、L ≥ 88"：实测这张图的背景渐变彩度 4~7.3，
        //    而最浅的花瓣彩度 33，中间有 4 倍余量，不会误吞花瓣。旧的 ≤ 3
        //    把 #F8E8ED 全部漏掉，正是四角灰粉块一直清不掉的直接原因。
        val whiteIdx = purestWhite(palette, excludedCodes)
        if (whiteIdx >= 0) {
            val glow = borderGlow(gLab, gOfPixel, art.w, art.h, groupCount)
            for (i in 0 until n) if (glow[i]) despeckled[i] = whiteIdx
        }
        return assemble(despeckled, art, gridW, gridH)
    }

    /** 同一画像素抖动合并阈值（CIEDE2000） */
    private const val NOISE_MERGE_DELTA = 3.0

    /** 光晕判据：够亮、够中性 */
    private const val GLOW_MIN_L = 88f
    private const val GLOW_MAX_CHROMA2 = 100f // 彩度 ≤ 10

    /** 相邻画像素算"同一片渐变"的 Lab 距离上限 */
    private const val GLOW_STEP = 9.0

    /** 去孤点时，本像素与邻域均值色差超过该值即视为真实细节，不替换 */
    private const val DESPECKLE_GUARD = 8.0

    /**
     * 单轮 3×3 去孤点。
     *
     * 判据：8 邻域里同色号 < 3 个、且某个邻域色号占 ≥ 5 个，才替换成该色号。
     * 源色护栏保证 1 格宽的眼睛高光、黑色描边、深色花蕊不被抹掉。
     */
    private fun despeckle(
        artCode: IntArray,
        lab: FloatArray,
        uidOfPixel: IntArray,
        w: Int,
        h: Int,
    ): IntArray {
        val out = artCode.copyOf()
        val codes = IntArray(9)
        val counts = IntArray(9)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val code = artCode[i]
                if (code < 0) continue
                var used = 0
                var same = 0
                var sl = 0.0
                var sa = 0.0
                var sb = 0.0
                var nbr = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val j = yy * w + xx
                        val c = artCode[j]
                        if (c < 0) continue
                        if (c == code) same++
                        var slot = -1
                        for (s in 0 until used) if (codes[s] == c) { slot = s; break }
                        if (slot < 0) { slot = used++; codes[slot] = c; counts[slot] = 0 }
                        counts[slot]++
                        val g = uidOfPixel[j]
                        sl += lab[g * 3]
                        sa += lab[g * 3 + 1]
                        sb += lab[g * 3 + 2]
                        nbr++
                    }
                }
                if (nbr == 0 || same >= 3) continue
                var bestSlot = -1
                var bestCount = 0
                for (s in 0 until used) if (counts[s] > bestCount) {
                    bestCount = counts[s]
                    bestSlot = s
                }
                if (bestSlot < 0 || bestCount < 5 || codes[bestSlot] == code) continue
                val g = uidOfPixel[i]
                val dl = lab[g * 3] - sl / nbr
                val da = lab[g * 3 + 1] - sa / nbr
                val db = lab[g * 3 + 2] - sb / nbr
                if (dl * dl + da * da + db * db > DESPECKLE_GUARD * DESPECKLE_GUARD) continue
                out[i] = codes[bestSlot]
            }
        }
        return out
    }

    /**
     * 四边泛洪：从边界上符合"浅 + 中性"的画像素出发，只在同样浅且与当前
     * 像素 Lab 距离 ≤ [GLOW_STEP] 的邻居上继续。8 邻域，O(画像素数)。
     */
    private fun borderGlow(
        lab: FloatArray,
        uidOfPixel: IntArray,
        w: Int,
        h: Int,
        uidCount: Int,
    ): BooleanArray {
        val n = w * h
        val glowUid = BooleanArray(uidCount)
        for (u in 0 until uidCount) {
            val a = lab[u * 3 + 1]
            val b = lab[u * 3 + 2]
            glowUid[u] = lab[u * 3] >= GLOW_MIN_L && a * a + b * b <= GLOW_MAX_CHROMA2
        }
        val seen = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        fun seed(i: Int) {
            if (seen[i]) return
            if (!glowUid[uidOfPixel[i]]) return
            seen[i] = true
            queue[tail++] = i
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 1 until h - 1) { seed(y * w); seed(y * w + w - 1) }
        if (tail == 0) return seen
        while (head < tail) {
            val i = queue[head++]
            val u = uidOfPixel[i]
            val x = i % w
            val y = i / w
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    val j = yy * w + xx
                    if (seen[j]) continue
                    val v = uidOfPixel[j]
                    if (!glowUid[v]) continue
                    if (labDistance(lab, u, v) > GLOW_STEP) continue
                    seen[j] = true
                    queue[tail++] = j
                }
            }
        }
        return seen
    }

    private fun labDistance(lab: FloatArray, u: Int, v: Int): Double {
        val dl = (lab[u * 3] - lab[v * 3]).toDouble()
        val da = (lab[u * 3 + 1] - lab[v * 3 + 1]).toDouble()
        val db = (lab[u * 3 + 2] - lab[v * 3 + 2]).toDouble()
        return kotlin.math.sqrt(dl * dl + da * da + db * db)
    }

    /** 色卡里最纯的白（L 最高、彩度最低）；没有则 -1 */
    private fun purestWhite(palette: BeadPalette, excludedCodes: Set<String>): Int {
        var best = -1
        var bestL = -1f
        var bestChroma = Float.MAX_VALUE
        for (i in palette.colors.indices) {
            val e = palette.colors[i]
            if (e.code in excludedCodes) continue
            val chroma = kotlin.math.sqrt(e.lab[1] * e.lab[1] + e.lab[2] * e.lab[2])
            if (chroma > 3f || e.lab[0] < 92f) continue
            if (e.lab[0] > bestL + 0.01f || (kotlin.math.abs(e.lab[0] - bestL) <= 0.01f && chroma < bestChroma)) {
                best = i
                bestL = e.lab[0]
                bestChroma = chroma
            }
        }
        return best
    }

    /**
     * 单一色号的最近豆色，带彩度护栏。
     *
     * 色卡里有一串"接近白"的豆子（#FFFFFF、#FCF7F8、#FFF3EB…）。浅粉花瓣
     * 在 CIEDE2000 下离这些白豆比离真正的浅粉豆更近，结果整圈花瓣变成
     * 白豆，铺在白底图纸上等于消失。所以当源色有明显彩度时，禁掉无色号。
     */
    private fun nearestBead(
        palette: BeadPalette,
        lab: FloatArray,
        u: Int,
        excludedCodes: Set<String>,
    ): Int {
        val a = lab[u * 3 + 1]
        val b = lab[u * 3 + 2]
        val wantChroma = kotlin.math.sqrt(a * a + b * b) > 8f
        return nearestWithGuard(palette, lab[u * 3], a, b, wantChroma, excludedCodes)
    }

    private fun nearestCenter(
        palette: BeadPalette,
        centers: FloatArray,
        c: Int,
        excludedCodes: Set<String>,
    ): Int {
        val a = centers[c * 3 + 1]
        val b = centers[c * 3 + 2]
        val wantChroma = kotlin.math.sqrt(a * a + b * b) > 8f
        return nearestWithGuard(palette, centers[c * 3], a, b, wantChroma, excludedCodes)
    }

    private fun nearestWithGuard(
        palette: BeadPalette,
        l: Float,
        a: Float,
        b: Float,
        wantChroma: Boolean,
        excludedCodes: Set<String>,
    ): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in palette.colors.indices) {
            val e = palette.colors[i]
            if (e.code in excludedCodes) continue
            if (wantChroma) {
                val ca = e.lab[1]
                val cb = e.lab[2]
                if (ca * ca + cb * cb < 25f) continue // 彩度 < 5 的近白色不参选
            }
            val d = Ciede2000.deltaE(floatArrayOf(l, a, b), e.lab)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best >= 0) return best
        // 护栏把色卡清空时退回无约束最近色
        return palette.nearestIndex(floatArrayOf(l, a, b), excludedCodes)
    }

    /**
     * 目标格面积多数表决：每格累加覆盖到的画像素面积，取面积最大的色号。
     *
     * 取众数而不是平均：目标格比画像素大时（降采样），平均会把两种相邻
     * 色混成源图不存在的中间色；目标格比画像素小时（本例 78 > 68），
     * 众数保证每格输出的都是真实存在的色号，边缘不会出现过渡脏色。
     */
    private fun assemble(artCode: IntArray, art: ArtGrid, gridW: Int, gridH: Int): IntArray {
        val out = IntArray(gridW * gridH) { -1 }
        val codes = IntArray(16)
        val areas = DoubleArray(16)
        for (gy in 0 until gridH) {
            val y0 = gy.toDouble() / gridH * art.h
            val y1 = (gy + 1).toDouble() / gridH * art.h
            for (gx in 0 until gridW) {
                val x0 = gx.toDouble() / gridW * art.w
                val x1 = (gx + 1).toDouble() / gridW * art.w
                var used = 0
                val ay0 = y0.toInt().coerceIn(0, art.h - 1)
                val ay1 = (y1 - 1e-9).toInt().coerceIn(0, art.h - 1)
                val ax0 = x0.toInt().coerceIn(0, art.w - 1)
                val ax1 = (x1 - 1e-9).toInt().coerceIn(0, art.w - 1)
                for (ay in ay0..ay1) {
                    val oy = min(y1, ay + 1.0) - max(y0, ay.toDouble())
                    if (oy <= 0) continue
                    for (ax in ax0..ax1) {
                        val ox = min(x1, ax + 1.0) - max(x0, ax.toDouble())
                        if (ox <= 0) continue
                        val code = artCode[ay * art.w + ax]
                        var slot = -1
                        for (s in 0 until used) if (codes[s] == code) { slot = s; break }
                        if (slot < 0) {
                            if (used == codes.size) break
                            slot = used++
                            codes[slot] = code
                            areas[slot] = 0.0
                        }
                        areas[slot] += ox * oy
                    }
                }
                var best = -1
                var bestArea = -1.0
                for (s in 0 until used) if (areas[s] > bestArea) { bestArea = areas[s]; best = codes[s] }
                out[gy * gridW + gx] = best
            }
        }
        return out
    }

    /** 加权 k-means++；权重是画像素面积，不是格数 */
    private fun fit(lab: FloatArray, w: DoubleArray, n: Int, k: Int): Pair<IntArray, FloatArray> {
        var bestInertia = Double.MAX_VALUE
        var bestLabels = IntArray(n)
        var bestCenters = FloatArray(k * 3)
        for (seed in SEEDS) {
            val rnd = Random(seed)
            val centers = FloatArray(k * 3)
            // 第一个簇心按权重抽样
            var total = 0.0
            for (i in 0 until n) total += w[i]
            var r = rnd.nextDouble() * total
            var first = 0
            while (first < n - 1) {
                r -= w[first]
                if (r <= 0) break
                first++
            }
            centers[0] = lab[first * 3]
            centers[1] = lab[first * 3 + 1]
            centers[2] = lab[first * 3 + 2]
            val d2 = DoubleArray(n) { Double.MAX_VALUE }
            for (c in 1 until k) {
                var sum = 0.0
                for (i in 0 until n) {
                    val d = distSq(lab, i * 3, centers, (c - 1) * 3)
                    if (d < d2[i]) d2[i] = d
                    sum += d2[i] * w[i]
                }
                var pick = -1
                if (sum <= 1e-12) {
                    pick = rnd.nextInt(n)
                } else {
                    var rr = rnd.nextDouble() * sum
                    for (i in 0 until n) {
                        rr -= d2[i] * w[i]
                        if (rr <= 0) { pick = i; break }
                    }
                    if (pick < 0) pick = n - 1
                }
                centers[c * 3] = lab[pick * 3]
                centers[c * 3 + 1] = lab[pick * 3 + 1]
                centers[c * 3 + 2] = lab[pick * 3 + 2]
            }

            val labels = IntArray(n)
            val sumL = DoubleArray(k)
            val sumA = DoubleArray(k)
            val sumB = DoubleArray(k)
            val sumW = DoubleArray(k)
            var moved = true
            var iter = 0
            while (moved && iter < MAX_ITER) {
                moved = false
                iter++
                for (i in 0 until n) {
                    var bi = 0
                    var bd = Double.MAX_VALUE
                    for (c in 0 until k) {
                        val d = distSq(lab, i * 3, centers, c * 3)
                        if (d < bd) { bd = d; bi = c }
                    }
                    if (labels[i] != bi) { labels[i] = bi; moved = true }
                    sumL[bi] += lab[i * 3] * w[i]
                    sumA[bi] += lab[i * 3 + 1] * w[i]
                    sumB[bi] += lab[i * 3 + 2] * w[i]
                    sumW[bi] += w[i]
                }
                for (c in 0 until k) {
                    if (sumW[c] <= 0) continue
                    val nl = (sumL[c] / sumW[c]).toFloat()
                    val na = (sumA[c] / sumW[c]).toFloat()
                    val nb = (sumB[c] / sumW[c]).toFloat()
                    val dl = nl - centers[c * 3]
                    val da = na - centers[c * 3 + 1]
                    val db = nb - centers[c * 3 + 2]
                    if (dl * dl + da * da + db * db > TOLERANCE * TOLERANCE) moved = true
                    centers[c * 3] = nl
                    centers[c * 3 + 1] = na
                    centers[c * 3 + 2] = nb
                    sumL[c] = 0.0
                    sumA[c] = 0.0
                    sumB[c] = 0.0
                    sumW[c] = 0.0
                }
            }
            var inertia = 0.0
            for (i in 0 until n) inertia += distSq(lab, i * 3, centers, labels[i] * 3) * w[i]
            if (inertia < bestInertia) {
                bestInertia = inertia
                bestLabels = labels
                bestCenters = centers
            }
        }
        return Pair(bestLabels, bestCenters)
    }

    private fun distSq(a: FloatArray, ai: Int, b: FloatArray, bi: Int): Double {
        val dl = (a[ai] - b[bi]).toDouble()
        val da = (a[ai + 1] - b[bi + 1]).toDouble()
        val db = (a[ai + 2] - b[bi + 2]).toDouble()
        return dl * dl + da * da + db * db
    }
}
