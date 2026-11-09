package com.pindou.app.core.pattern

import com.pindou.app.core.palette.BeadPalette
import java.util.Random

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

        // 每簇映射一次色卡：K ≤ 64 次 CIEDE2000，替代逐格几千次查询
        val clusterPalette = IntArray(k)
        for (c in 0 until k) {
            val center = floatArrayOf(centers[c * 3], centers[c * 3 + 1], centers[c * 3 + 2])
            var idx = palette.nearestIndex(center, excludedCodes)
            if (idx < 0) idx = palette.nearestIndex(center) // 全被排除时的兜底
            clusterPalette[c] = idx
        }

        val cells = IntArray(n)
        for (i in 0 until n) cells[i] = clusterPalette[labels[i]]

        // 细节回填：只对掩码格做原始色映射，并按 argb 去重缓存
        if (detailMask != null) {
            val cache = HashMap<Int, Int>()
            for (i in 0 until n) {
                if (!detailMask[i]) continue
                val argb = sample.argb[i]
                cells[i] = cache.getOrPut(argb) {
                    val l = floatArrayOf(lab[i * 3], lab[i * 3 + 1], lab[i * 3 + 2])
                    var idx = palette.nearestIndex(l, excludedCodes)
                    if (idx < 0) idx = palette.nearestIndex(l)
                    idx
                }
            }
        }
        return cells
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
