package com.pindou.app.core.pattern

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions
import kotlin.math.roundToInt

/**
 * 图纸质量报告：一张图纸"像不像原图"的量化结果。
 *
 * 只比颜色是不够的——同一张图换个过滤档位，平均色差几乎不变（都在 4 左右），
 * 但看起来一个碎成胡椒面、一个糊成色块。真正决定观感的是**结构与原图是否一致**：
 *
 * - [breakRate]：原图里本来就同色的一对相邻格（ΔE < 4），输出却被拆成两种颜色
 *   → 碎点、棋盘格、噪点。无过滤档位的主要失分项。
 * - [flattenRate]：原图里有明确边界的一对相邻格（ΔE > 15），输出却压成同色
 *   → 五官糊掉、花瓣粘成一片。高过滤档位的主要失分项。
 *
 * 这两个指标互相对冲，最优点落在中间：既保留边界，又不在平面里撒噪点。
 * 这是"自动优选"能选出人眼认可参数的原因。
 */
data class QualityReport(
    val score: Int,
    val meanDeltaE: Double,
    val p90DeltaE: Double,
    val colorCount: Int,
    val breakRate: Double,
    val flattenRate: Double,
    val emptyRatio: Double,
) {
    val label: String
        get() = when {
            score >= 86 -> "几乎还原原图"
            score >= 74 -> "还原良好"
            score >= 62 -> "略有走形"
            else -> "失真明显"
        }

    fun summary(): String =
        "还原度 $score · 均差 ${"%.1f".format(meanDeltaE)} · 碎点 ${"%.1f".format(breakRate * 100)}%" +
            " · 糊边 ${"%.1f".format(flattenRate * 100)}%"

    companion object {
        val EMPTY = QualityReport(0, 0.0, 0.0, 0, 0.0, 0.0, 0.0)
    }
}

object QualityScorer {

    /** "原图这两格本来同色"的 ΔE 上限；CIEDE2000 下 2~4 是大色块的可辨阈值 */
    private const val SAME_AREA_DE = 4.0

    /** 输出侧"已经明显不同色"的 ΔE 下限 */
    private const val BROKEN_DE = 8.0

    /**
     * 原图侧"明确是边界"的 ΔE 下限。
     *
     * 30 只能抓到五官这种硬边，花瓣层次、裙摆褶皱这类 ΔE 18~28 的软边
     * 全被漏掉——于是"限 20 色把整朵花糊成一块"在评分里不扣分，自动优选
     * 反而偏向糊的档位。降到 18，软边也参与糊边统计。
     */
    private const val EDGE_DE = 18.0

    /** 输出侧"边界被抹平"的 ΔE 上限 */
    private const val FLATTENED_DE = 3.0

    /**
     * @param ref 未平滑的面积平均采样（FilterLevel.NONE），代表"原图在目标网格上真实的样子"
     * @param cells 最终色号索引，-1 = 空格
     * 复杂度 O(格数)：每格一次 CIEDE2000，每对相邻格两次。
     */
    fun score(
        ref: GridSample,
        width: Int,
        height: Int,
        cells: IntArray,
        paletteArgbs: IntArray,
        pixelArt: Boolean = false,
    ): QualityReport {
        if (width <= 0 || height <= 0 || cells.size != width * height) return QualityReport.EMPTY

        // 调色板 Lab 预先算好：评分要给每格找"最接近的色号"作为基准误差
        val paletteLab = Array(paletteArgbs.size) { ColorConversions.argbToLab(paletteArgbs[it]) }
        val used = BooleanArray(paletteArgbs.size)
        // 逐格"超出基准的误差"直方图（0~40，0.1 分桶）求均值与 p90，避免排序
        val hist = IntArray(401)
        var counted = 0
        var sum = 0.0
        var excessSum = 0.0

        // 每格输出色的 Lab 缓存：相邻格比较要复用，重复转换会翻倍开销
        val outLab = arrayOfNulls<FloatArray>(cells.size)

        fun outLabAt(i: Int): FloatArray? {
            val c = cells[i]
            if (c < 0 || c >= paletteArgbs.size) return null
            outLab[i]?.let { return it }
            val lab = paletteLab[c]
            outLab[i] = lab
            return lab
        }

        for (i in cells.indices) {
            val c = cells[i]
            if (c < 0 || c >= paletteArgbs.size) continue
            // 源图透明格没有"应该是什么颜色"，参与评分只会稀释误差
            if ((ref.argb[i] ushr 24) < 8) continue
            val refLab = floatArrayOf(ref.lab[i * 3], ref.lab[i * 3 + 1], ref.lab[i * 3 + 2])
            val dstLab = paletteLab[c]
            val de = Ciede2000.deltaE(refLab, dstLab)
            sum += de
            counted++
            used[c] = true

            // 基准误差：这张图用这套色卡"最好也只能到"的色差。
            // 色卡覆盖不到的颜色（比如原图渐变中间色）不该算在转换器头上，
            // 否则同一张图换个色卡分数会整体漂移，跨图比较就没意义了。
            // 先用 Lab 欧氏距离挑最近色号（快），再对它算一次 CIEDE2000（准）。
            val baseDe = nearestDeltaE(refLab, paletteLab)
            val excess = (de - baseDe).coerceAtLeast(0.0)
            excessSum += excess
            hist[(excess * 10).toInt().coerceIn(0, 400)]++
        }
        if (counted == 0) return QualityReport.EMPTY

        var broke = 0
        var sameArea = 0
        var flattened = 0
        var edges = 0

        fun comparePair(a: Int, b: Int) {
            val la = outLabAt(a) ?: return
            val lb = outLabAt(b) ?: return
            val refA = floatArrayOf(ref.lab[a * 3], ref.lab[a * 3 + 1], ref.lab[a * 3 + 2])
            val refB = floatArrayOf(ref.lab[b * 3], ref.lab[b * 3 + 1], ref.lab[b * 3 + 2])
            val refDe = Ciede2000.deltaE(refA, refB)
            if (refDe < SAME_AREA_DE) {
                sameArea++
                if (Ciede2000.deltaE(la, lb) > BROKEN_DE) broke++
            }
            if (refDe > EDGE_DE) {
                edges++
                if (Ciede2000.deltaE(la, lb) < FLATTENED_DE) flattened++
            }
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (x < width - 1) comparePair(i, i + 1)
                if (y < height - 1) comparePair(i, i + width)
            }
        }

        val mean = sum / counted
        val meanExcess = excessSum / counted
        val p90 = percentile(hist, counted, 0.90)
        val breakRate = if (sameArea == 0) 0.0 else broke.toDouble() / sameArea
        val flattenRate = if (edges == 0) 0.0 else flattened.toDouble() / edges
        val colorCount = used.count { it }

        return QualityReport(
            score = if (pixelArt) {
                artScoreOf(mean, p90, colorCount)
            } else {
                scoreOf(meanExcess, p90, breakRate, flattenRate, colorCount)
            },
            meanDeltaE = mean,
            p90DeltaE = p90,
            colorCount = colorCount,
            breakRate = breakRate,
            flattenRate = flattenRate,
            emptyRatio = 1.0 - counted.toDouble() / cells.size,
        )
    }

    /**
     * 像素画口径：源图本来就是离散色块，没有"照片纹理要不要抹平"这回事。
     * 逐画像素映射后仍扣碎点/糊边，等于把"色卡里没有这个豆色"的客观
     * 限制算成转换器的错——一张还原得很准的原画会被打成 36 分"失真明显"。
     * 这里只按逐格色差打分：均差 5.6、p90 12.7 对应 79 分（还原良好）。
     *
     * 超出 40 色的部分不再扣分：像素画原图的配色数由作者决定，压色才是失真。
     */
    private fun artScoreOf(mean: Double, p90: Double, colorCount: Int): Int {
        val colorLoss = mean * 3.0 + p90 * 0.3
        val paletteLoss = if (colorCount > 40) ((colorCount - 40) * 0.2).coerceAtMost(4.0) else 0.0
        return (100.0 - colorLoss - paletteLoss).roundToInt().coerceIn(0, 99)
    }

    /** 色卡里离 ref 最近的色号，返回其 CIEDE2000；调色板为空返回 0 */
    private fun nearestDeltaE(refLab: FloatArray, paletteLab: Array<FloatArray>): Double {
        if (paletteLab.isEmpty()) return 0.0
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in paletteLab.indices) {
            val l = paletteLab[i]
            val dl = refLab[0] - l[0]
            val da = refLab[1] - l[1]
            val db = refLab[2] - l[2]
            val d = (dl * dl + da * da + db * db).toDouble()
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return Ciede2000.deltaE(refLab, paletteLab[best])
    }

    private fun percentile(hist: IntArray, total: Int, q: Double): Double {
        val target = (total * q).toInt().coerceAtLeast(1)
        var acc = 0
        for (b in hist.indices) {
            acc += hist[b]
            if (acc >= target) return b / 10.0
        }
        return 0.0
    }

    /**
     * 合成打分：100 分起扣，四类失分互不掩盖。
     *
     * 颜色：超出基准的均差系数 6.0（多偏 ΔE 3 扣 18），p90 系数 0.35（惩罚局部崩坏）。
     * 破碎：比例 × 2.0，满 25 分封顶——碎点是"拼到一半想放弃"的头号原因。
     * 糊边：比例 × 1.6，满 22 分封顶——糊边让五官消失，但比碎点稍可接受。
     * 用色：20~34 色是拼豆常规区间，不扣分；超过 34 色每色 0.5 分，封顶 6。
     * 早先按 20 色起扣，把"30 色、结构更准"的组合硬压 5 分，自动优选
     * 于是永远落在糊一点但省豆的 20 色版本上——用户看到的就是"不像"。
     */
    private fun scoreOf(
        mean: Double,
        p90: Double,
        breakRate: Double,
        flattenRate: Double,
        colorCount: Int,
    ): Int {
        val colorLoss = (mean * 6.0).coerceAtMost(35.0) + (p90 * 0.35).coerceAtMost(8.0)
        val breakLoss = (breakRate * 100.0 * 1.5).coerceAtMost(22.0)
        val flatLoss = (flattenRate * 100.0 * 0.55).coerceAtMost(18.0)
        val paletteLoss = if (colorCount > 34) ((colorCount - 34) * 0.5).coerceAtMost(6.0) else 0.0
        return (100.0 - colorLoss - breakLoss - flatLoss - paletteLoss).roundToInt().coerceIn(0, 100)
    }
}
