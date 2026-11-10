package com.pindou.app.core.pattern

import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.color.Ciede2000
import java.util.Random
import kotlin.math.sqrt

/**
 * Lab 空间 k-means 量化。
 *
 * 为什么不再逐格映射色卡：一张照片在 78 宽网格上仍有两千多个格子，
 * 每格独立取最近色会把同一片皮肤、同一片布料拆成十几个相邻色号，
 * 形成"撒胡椒面"的棋盘格。先让 k-means 把整张网格归成 K 个色簇，
 * 同一片区域的所有格子带同一个簇标签，再逐簇映射到最近色卡——
 * 块面天然连成一片，这才是颂拼豆导入后图面干净的来源。
 *
 * 复杂度：训练点上限 [MAX_TRAIN_POINTS]，K ≤ 64，迭代 ≤ 40，
 * 最坏约 12000 × 64 × 40 ≈ 3×10^7 次距离计算，手机上几十毫秒级。
 * 最终每簇只做一次 CIEDE2000 查色卡，比逐格查表省两个数量级。
 */
object LabQuantizer {

    /** 训练采样上限：k-means 的簇心对采样量不敏感，1.2 万点足够收敛 */
    private const val MAX_TRAIN_POINTS = 12_000

    /** 迭代上限：正常图片 15~25 轮内收敛，40 轮是保险丝 */
    private const val MAX_ITER = 40

    /** 簇心最大位移（Lab 单位）；小于它继续迭代没有视觉收益 */
    private const val TOLERANCE = 0.02f

    /** 两套初始化取惯性更低者，降低 k-means++ 抽到坏起点的概率 */
    private val SEEDS = longArrayOf(7L, 1234L)

    /**
     * 量化网格并映射到色卡。
     *
     * @param sample      采样结果（argb + Lab）
     * @param width       网格宽
     * @param height      网格高
     * @param palette     目标色卡
     * @param clusterK    目标簇数（过滤等级决定；实际会按格数/去重色数收敛）
     * @param excludedCodes 用户排除的色号
     * @param detailMask  细节掩码为真的格子回填"原始采样色→色卡"的结果，
     *                    保证眼睛、高光、首饰不被簇心平均掉；可空
     * @return 每格的色卡索引（-1 = 无可用色）
     */
    fun quantize(
        sample: GridSample,
        width: Int,
        height: Int,
        palette: BeadPalette,
        clusterK: Int,
        excludedCodes: Set<String>,
        detailMask: BooleanArray? = null,
    ): IntArray {
        val n = width * height
        require(sample.size >= n) { "采样格数 ${sample.size} < $n" }
        if (n == 0) return IntArray(0)

        val lab = sample.lab
        val fitted = fit(lab, n, clusterK)
        val labels = fitted.labels
        val k = fitted.k
        val centers = fitted.centers

        // 近白背景归一：证件照、商品图、贴纸图的边缘是浅色渐变，逐簇映射
        // 会把渐变拆成"白 + 浅灰 + 浅粉"几块，拼出来像脏三角。边缘本身
        // 就以近白为主时，把所有近白簇统一吸到色卡里最纯的白，图面干净，
        // 也不影响主体（主体簇的 L/彩度不在近白区间）。
        val whiteIdx = neutralWhiteIndex(sample, width, height, palette, excludedCodes)

        // 近黑归一：抗锯齿会把纯黑描边稀释成 #2F2B2F 这类"深灰黑"，而色卡里
        // 往往同时存在 #000000 和 #2F2B2F 两个近中性深色，逐格取最近色会在
        // 同一条描边上跳色——线条一段纯黑、一段灰，眼睛和轮廓看着脏。
        // 中性且极暗（L ≤ 22、彩度 ≤ 6.3）的格子统一归到色卡最深的那个中性色。
        val inkIdx = neutralInkIndex(palette, excludedCodes)

        // 每簇映射一次色卡。不能只取"离簇心最近的那个色号"：一个簇里
        // 混进少量邻近色（脸部混入深红花瓣）时，簇心会被拉偏，最近色跟着
        // 跳到更艳的色号，整块脸就偏色。做法是给每簇挑 [SHORTLIST] 个
        // 候选色号，再让簇内每格投自己最近的那个，取票数最高者——
        // 等价于簇内众数色，代价 O(K×色卡 + 格数×候选数)。
        val clusterPalette = IntArray(k)
        run {
            val shortlist = IntArray(k * SHORTLIST)
            for (c in 0 until k) {
                val center = floatArrayOf(centers[c * 3], centers[c * 3 + 1], centers[c * 3 + 2])
                pickShortlist(palette, center, excludedCodes, shortlist, c * SHORTLIST)
            }
            val votes = IntArray(k * SHORTLIST)
            val cell = FloatArray(3)
            for (i in 0 until n) {
                val c = labels[i]
                val base = c * SHORTLIST
                var best = 0
                var bestD = Double.MAX_VALUE
                cell[0] = lab[i * 3]
                cell[1] = lab[i * 3 + 1]
                cell[2] = lab[i * 3 + 2]
                for (s in 0 until SHORTLIST) {
                    val idx = shortlist[base + s]
                    if (idx < 0) continue
                    val d = palette.matchScore(cell, palette.colors[idx])
                    if (d < bestD) {
                        bestD = d
                        best = s
                    }
                }
                votes[base + best]++
            }
            for (c in 0 until k) {
                val base = c * SHORTLIST
                var bestSlot = 0
                for (s in 1 until SHORTLIST) if (votes[base + s] > votes[base + bestSlot]) bestSlot = s
                var idx = shortlist[base + bestSlot]
                if (idx < 0) idx = palette.nearestIndex(
                    floatArrayOf(centers[c * 3], centers[c * 3 + 1], centers[c * 3 + 2]),
                )
                clusterPalette[c] = idx
            }
        }

        // 逐格近白归一：证件照/贴纸图的背景是"白 + 浅粉渐变"，量化后会被拆成
        // 白、浅灰、浅粉三块，拼出来是脏三角；而不做归一又会把整圈花托
        // （同样是浅粉）一起吸成白。所以判据必须带空间信息：离任何高彩度
        // 格子（花瓣、叶子）都超过 [BG_RADIUS] 格的浅色格子才算背景。
        val bgDistance = distanceToSaturated(lab, width, height)
        val cells = IntArray(n)
        for (i in 0 until n) {
            val l = lab[i * 3]
            val a = lab[i * 3 + 1]
            val b = lab[i * 3 + 2]
            val chroma2 = a * a + b * b
            // 两级判据：真正的中性白（口鼻、眼白、高光）不看位置一律吸白；
            // 带轻微粉调的浅色（背景光晕）只有离主体足够远才算背景。
            //
            // bgWhite 的下限从 88 收到 94：贴纸图的内耳、脸颊阴影常是 L≈88~92
            // 的中性灰，88 会让整块灰先被吸成白，只剩描边旁几个零散灰点，
            // 再被色卡映射成冷灰/蓝灰——就是"左耳那几颗蓝点"。收到 94 之后
            // 只有接近纯白的才吸，灰色整片进色卡映射，形状才留得住。
            val pureWhite = l >= 96f && chroma2 <= 36f
            // 上限从 chroma²≤256（彩度 16）收到 ≤36（彩度 6）：腮红、内耳、
            // 翅膀这类浅彩度平涂色（彩度 8~14）过去整片被吸成白，形状就丢了。
            // 背景渐变本身是中性灰白（彩度 ≤4），收到 6 仍然能吃掉背景。
            val bgWhite = bgDistance[i] > BG_RADIUS && l >= WHITE_FLOOR && chroma2 <= 36f
            val pureInk = inkIdx >= 0 && l <= INK_FLOOR && chroma2 <= INK_CHROMA2
            cells[i] = if (pureInk) {
                inkIdx
            } else if (whiteIdx >= 0 && (pureWhite || bgWhite)) {
                whiteIdx
            } else {
                clusterPalette[labels[i]]
            }
        }

        // 细节回填：只对掩码格做原始色映射，并按 argb 去重缓存
        if (detailMask != null) {
            val cache = HashMap<Int, Int>()
            for (i in 0 until n) {
                if (!detailMask[i]) continue
                val argb = sample.argb[i]
                cells[i] = cache.getOrPut(argb) {
                    val l = floatArrayOf(lab[i * 3], lab[i * 3 + 1], lab[i * 3 + 2])
                    // 细节格（描边、眼睛、高光）走原始色直接映射，绕过了上面的
                    // 近白归一。#F0F0F0 这种"抗锯齿过的白"离 #EDEDED 比离 #FFFFFF
                    // 还近 0.5 个 ΔE，会整圈描边镶上一圈浅灰豆——肉眼看就是脏边。
                    // 所以这里补一条：中性且足够亮的，直接归白。
                    val chroma2 = l[1] * l[1] + l[2] * l[2]
                    if (inkIdx >= 0 && l[0] <= INK_FLOOR && chroma2 <= INK_CHROMA2) {
                        inkIdx
                    } else if (whiteIdx >= 0 && l[0] >= 92.5f && chroma2 <= 30f) {
                        whiteIdx
                    } else {
                        var idx = palette.nearestIndex(l, excludedCodes)
                        if (idx < 0) idx = palette.nearestIndex(l)
                        idx
                    }
                }
            }
        }
        return cells
    }

    /** 判断"背景"时与最近高彩度格子的距离下限（切比雪夫距离，格） */
    private const val BG_RADIUS = 3

    /** 浅色归一的白度下限（Lab L）：低于此值的灰要按色卡灰保留 */
    private const val WHITE_FLOOR = 95f

    /** 近黑归一的亮度上限（Lab L）：低于它的中性格视作描边墨色 */
    private const val INK_FLOOR = 22f

    /** 近黑归一的彩度平方上限（6.3²）：超过它说明是有色深色，不能当墨色 */
    private const val INK_CHROMA2 = 40f

    /** 每簇参与投票的候选色号数：8 个足够覆盖簇内混色的分裂，再多只是浪费 */
    private const val SHORTLIST = 8

    /** 取离 [center] 最近且未被排除的 [SHORTLIST] 个色号（插入式选择，O(色卡×候选)） */
    internal fun pickShortlist(
        palette: BeadPalette,
        center: FloatArray,
        excludedCodes: Set<String>,
        out: IntArray,
        offset: Int,
    ) {
        val dist = DoubleArray(SHORTLIST) { Double.MAX_VALUE }
        val idxs = IntArray(SHORTLIST) { -1 }
        for (i in palette.colors.indices) {
            if (palette.colors[i].code in excludedCodes) continue
            val d = palette.matchScore(center, palette.colors[i])
            if (d >= dist[SHORTLIST - 1]) continue
            var p = SHORTLIST - 1
            while (p > 0 && dist[p - 1] > d) {
                dist[p] = dist[p - 1]
                idxs[p] = idxs[p - 1]
                p--
            }
            dist[p] = d
            idxs[p] = i
        }
        for (s in 0 until SHORTLIST) out[offset + s] = idxs[s]
    }

    /** 高彩度阈值（Lab 彩度），花瓣、叶子、主体轮廓都在此之上 */
    private const val SATURATED_CHROMA = 22f

    /**
     * 多源 BFS：每格到最近"高彩度格"的 8 邻域距离，超过 [BG_RADIUS] 即封顶。
     * 只算到半径内，复杂度 O(格数 × 半径²)，78×72 网格可忽略。
     *
     * 用距离而不是连通域：背景渐变会从花环缝隙漏进内圈，洪水填充挡不住；
     * 距离判据只看"离主体多远"，缝隙漏不漏都不影响结果。
     */
    private fun distanceToSaturated(lab: FloatArray, width: Int, height: Int): IntArray {
        val n = width * height
        val dist = IntArray(n) { -1 }
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        for (i in 0 until n) {
            val a = lab[i * 3 + 1]
            val b = lab[i * 3 + 2]
            if (a * a + b * b >= SATURATED_CHROMA * SATURATED_CHROMA) {
                dist[i] = 0
                queue[tail++] = i
            }
        }
        // 没有彩色主体（纯黑白线稿）时不做背景归一，避免把整图吸白
        if (tail == 0) return IntArray(n) { BG_RADIUS + 1 }
        while (head < tail) {
            val i = queue[head++]
            val d = dist[i]
            if (d >= BG_RADIUS) continue
            val x = i % width
            val y = i / width
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= height) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= width) continue
                    val j = yy * width + xx
                    if (dist[j] >= 0) continue
                    dist[j] = d + 1
                    queue[tail++] = j
                }
            }
        }
        for (i in 0 until n) if (dist[i] < 0) dist[i] = BG_RADIUS + 1
        return dist
    }

    /**
     * 若图片边缘以近白低彩度像素为主，返回色卡里最纯的白索引；否则 -1。
     *
     * 只看最外一圈而不是全图：白色主体（白裙、白墙）配深色背景时边缘
     * 不满足条件，不会被误吸成白。
     */
    private fun neutralWhiteIndex(
        sample: GridSample,
        width: Int,
        height: Int,
        palette: BeadPalette,
        excludedCodes: Set<String>,
    ): Int {
        if (width < 4 || height < 4) return -1
        var total = 0
        var neutral = 0
        fun check(i: Int) {
            val l = sample.lab[i * 3]
            val a = sample.lab[i * 3 + 1]
            val b = sample.lab[i * 3 + 2]
            if (l >= 85f && sqrt(a * a + b * b) <= 10f) neutral++
            total++
        }
        for (x in 0 until width) {
            check(x)
            check((height - 1) * width + x)
        }
        for (y in 1 until height - 1) {
            check(y * width)
            check(y * width + width - 1)
        }
        if (total == 0 || neutral.toDouble() / total < 0.5) return -1

        // 取"最亮的白"：近白背景要的是干净，L 最高的那个才不显脏。
        // 同等亮度再比彩度（越中性越像白纸）。
        var best = -1
        var bestChroma = Float.MAX_VALUE
        var bestL = -1f
        for (i in palette.colors.indices) {
            val e = palette.colors[i]
            if (e.code in excludedCodes) continue
            val chroma = sqrt(e.lab[1] * e.lab[1] + e.lab[2] * e.lab[2])
            if (chroma > 3f || e.lab[0] < 92f) continue
            val brighter = e.lab[0] > bestL + 0.01f
            val sameBright = kotlin.math.abs(e.lab[0] - bestL) <= 0.01f && chroma < bestChroma
            if (best < 0 || brighter || sameBright) {
                best = i
                bestChroma = chroma
                bestL = e.lab[0]
            }
        }
        return best
    }

    /**
     * 取色卡里最深的那个中性色（墨色）。
     *
     * 只有中性（彩度 ≤ 12）且够暗（L ≤ 30）才参与，避免把深蓝、深棕当成墨。
     * 多套色卡里同时有 #000000 与 #2F2B2F，这里取 L 最小的那个，让描边统一。
     * 找不到返回 -1（纯彩色色卡），调用方跳过归一。
     */
    private fun neutralInkIndex(palette: BeadPalette, excludedCodes: Set<String>): Int {
        var best = -1
        var bestL = Float.MAX_VALUE
        for (i in palette.colors.indices) {
            val e = palette.colors[i]
            if (e.code in excludedCodes) continue
            val chroma2 = e.lab[1] * e.lab[1] + e.lab[2] * e.lab[2]
            if (chroma2 > 144f || e.lab[0] > 30f) continue
            if (e.lab[0] < bestL) {
                bestL = e.lab[0]
                best = i
            }
        }
        return best
    }

    /**
     * k-means++ 初始化 + Lloyd 迭代，返回每格的簇标签。
     * 两套种子各跑一次，取簇内平方和（惯性）更小的结果。
     */
    private class FitResult(val labels: IntArray, val centers: FloatArray, val k: Int)

    private fun fit(lab: FloatArray, n: Int, requestedK: Int): FitResult {
        val k = requestedK.coerceIn(1, minOf(n, 64))
        val (trainIdx, trainN) = trainingSet(n)
        var bestInertia = Double.MAX_VALUE
        var bestCenters = FloatArray(0)

        for (seed in SEEDS) {
            val rnd = Random(seed)
            val centers = FloatArray(k * 3)
            initKMeansPlusPlus(lab, trainIdx, trainN, centers, k, rnd)
            val labels = IntArray(trainN)
            val inertia = iterate(lab, trainIdx, trainN, centers, labels, k)
            if (inertia < bestInertia) {
                bestInertia = inertia
                bestCenters = centers.copyOf()
            }
        }

        // 训练集可能被抽样过：用最终簇心给全部格重新分配标签，再用全量格
        // 重算簇心，修正抽样带来的偏移（一次遍历，O(N×K)）。
        val all = IntArray(n)
        assignAll(lab, n, bestCenters, k, all)
        return FitResult(all, centersOf(lab, n, all, k), k)
    }

    /**
     * 训练采样：均匀步长取点，既覆盖全图又保持顺序确定性。
     * 返回"源格下标数组"和实际点数。
     */
    private fun trainingSet(n: Int): Pair<IntArray, Int> {
        if (n <= MAX_TRAIN_POINTS) return Pair(IntArray(n) { it }, n)
        val step = (n + MAX_TRAIN_POINTS - 1) / MAX_TRAIN_POINTS
        val count = (n + step - 1) / step
        val idx = IntArray(count)
        var p = 0
        var i = 0
        while (i < n) {
            idx[p++] = i
            i += step
        }
        return Pair(idx, p)
    }

    /** k-means++：第一个簇心随机，后续按 D² 概率抽样，摊开初始簇心 */
    private fun initKMeansPlusPlus(
        lab: FloatArray,
        idx: IntArray,
        n: Int,
        centers: FloatArray,
        k: Int,
        rnd: Random,
    ) {
        val first = idx[rnd.nextInt(n)]
        centers[0] = lab[first * 3]
        centers[1] = lab[first * 3 + 1]
        centers[2] = lab[first * 3 + 2]
        val dist2 = DoubleArray(n) { Double.MAX_VALUE }
        for (c in 1 until k) {
            var sum = 0.0
            for (t in 0 until n) {
                val i = idx[t]
                val d = distSq(lab, i * 3, centers, (c - 1) * 3)
                if (d < dist2[t]) dist2[t] = d
                sum += dist2[t]
            }
            var pick = -1
            if (sum <= 1e-12) {
                pick = idx[rnd.nextInt(n)]
            } else {
                var r = rnd.nextDouble() * sum
                for (t in 0 until n) {
                    r -= dist2[t]
                    if (r <= 0.0) {
                        pick = idx[t]
                        break
                    }
                }
                if (pick < 0) pick = idx[n - 1]
            }
            centers[c * 3] = lab[pick * 3]
            centers[c * 3 + 1] = lab[pick * 3 + 1]
            centers[c * 3 + 2] = lab[pick * 3 + 2]
        }
    }

    /** Lloyd 迭代：分配 → 更新簇心 → 检查位移；返回惯性 */
    private fun iterate(
        lab: FloatArray,
        idx: IntArray,
        n: Int,
        centers: FloatArray,
        labels: IntArray,
        k: Int,
    ): Double {
        val sumL = DoubleArray(k)
        val sumA = DoubleArray(k)
        val sumB = DoubleArray(k)
        val count = IntArray(k)
        var moved = true
        var iter = 0
        while (moved && iter < MAX_ITER) {
            moved = false
            iter++
            for (t in 0 until n) {
                val i = idx[t]
                var best = 0
                var bestD = Double.MAX_VALUE
                for (c in 0 until k) {
                    val d = distSq(lab, i * 3, centers, c * 3)
                    if (d < bestD) {
                        bestD = d
                        best = c
                    }
                }
                if (labels[t] != best) {
                    labels[t] = best
                    moved = true
                }
                sumL[best] += lab[i * 3]
                sumA[best] += lab[i * 3 + 1]
                sumB[best] += lab[i * 3 + 2]
                count[best]++
            }
            for (c in 0 until k) {
                if (count[c] == 0) continue // 空簇保留原簇心，不重置，避免振荡
                val nl = (sumL[c] / count[c]).toFloat()
                val na = (sumA[c] / count[c]).toFloat()
                val nb = (sumB[c] / count[c]).toFloat()
                val dL = nl - centers[c * 3]
                val dA = na - centers[c * 3 + 1]
                val dB = nb - centers[c * 3 + 2]
                if (dL * dL + dA * dA + dB * dB > TOLERANCE * TOLERANCE) moved = true
                centers[c * 3] = nl
                centers[c * 3 + 1] = na
                centers[c * 3 + 2] = nb
                sumL[c] = 0.0
                sumA[c] = 0.0
                sumB[c] = 0.0
                count[c] = 0
            }
        }
        var inertia = 0.0
        for (t in 0 until n) {
            val i = idx[t]
            inertia += distSq(lab, i * 3, centers, labels[t] * 3)
        }
        return inertia
    }

    private fun assignAll(lab: FloatArray, n: Int, centers: FloatArray, k: Int, out: IntArray) {
        for (i in 0 until n) {
            var best = 0
            var bestD = Double.MAX_VALUE
            for (c in 0 until k) {
                val d = distSq(lab, i * 3, centers, c * 3)
                if (d < bestD) {
                    bestD = d
                    best = c
                }
            }
            out[i] = best
        }
    }

    /** 用全量格重算簇心，修正抽样训练带来的偏移（一次遍历，O(N×K)） */
    private fun centersOf(lab: FloatArray, n: Int, labels: IntArray, k: Int): FloatArray {
        val centers = FloatArray(k * 3)
        val count = IntArray(k)
        for (i in 0 until n) {
            val c = labels[i]
            centers[c * 3] += lab[i * 3]
            centers[c * 3 + 1] += lab[i * 3 + 1]
            centers[c * 3 + 2] += lab[i * 3 + 2]
            count[c]++
        }
        for (c in 0 until k) {
            if (count[c] == 0) continue
            centers[c * 3] /= count[c]
            centers[c * 3 + 1] /= count[c]
            centers[c * 3 + 2] /= count[c]
        }
        return centers
    }

    private fun distSq(a: FloatArray, ai: Int, b: FloatArray, bi: Int): Double {
        val dl = (a[ai] - b[bi]).toDouble()
        val da = (a[ai + 1] - b[bi + 1]).toDouble()
        val db = (a[ai + 2] - b[bi + 2]).toDouble()
        return dl * dl + da * da + db * db
    }
}
