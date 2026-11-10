package com.pindou.app.core.pattern

/**
 * 线稿描边骨架化。
 *
 * 问题：一张 1162×1200 的线稿落在 58×58 网格上，一格 20px，描边本身 14px。
 * 按面积平均/覆盖率落格，同一条线在某些格子里占 70%（变黑）、在下一格只占
 * 30%（变浅灰），量化完就是虚线；转角处又会有两格同时变黑，看起来一块一块。
 *
 * 做法：把描边先细化成 1 像素中心线（Zhang-Suen），再把中心线上的像素按格
 * 累加。一格只要有中心线穿过就判为墨格，于是每条线恰好一格宽、且必然连续；
 * 实心黑块（眼睛、实心图案）不参与细化，按覆盖率单独保留。
 *
 * 复杂度：O(源像素 × 细化迭代) + O(源像素)。1162×1200 约 40ms。
 */
object StrokeSkeleton {

    /** 判"墨"的亮度上限（0..255）；再高会把深灰阴影也算进来 */
    private const val INK_L = 110.0

    /** 判"纸"的亮度下限；用于线稿判定，不做逐格处理 */
    private const val PAPER_L = 215.0

    /** 线稿判定：纸白像素占比下限 */
    private const val LINEART_PAPER_SHARE = 0.30

    /** 线稿判定：墨像素占比下限 */
    private const val LINEART_INK_SHARE = 0.01

    /** 线稿判定：墨像素占比上限——超过说明是大面积实心色块，不是线稿 */
    private const val LINEART_INK_MAX = 0.45

    /**
     * 线稿判定：中间调占比上限。
     *
     * 线稿只有"纸"和"墨"两种像素，抗锯齿带很窄；照片、厚涂插画有大量
     * 中间调（皮肤、渐变、阴影）。这一条是把照片挡在骨架化外面的主闸门——
     * 只靠纸白/墨两项，白底产品图和浅色背景插画都会误判，细化后会把
     * 主体内部的深色纹理抹成一条线。
     */
    private const val LINEART_MID_MAX = 0.40

    /** 细化迭代上限：笔画半径 + 余量，超过说明这张图不是线稿 */
    private const val THIN_MAX_ITER = 24

    class Result(
        /** 每格中心线像素数（0 = 没有线穿过） */
        val hits: IntArray,
        /** 每格墨色均值（无墨时为 0） */
        val inkArgb: IntArray,
        /** 每格墨覆盖率 0..1（实心黑块靠它保底） */
        val coverage: FloatArray,
        /** 图上确实是线稿：纸白与墨都占足够比例 */
        val lineArt: Boolean,
        /** 纸白占比，调试与阈值校准用 */
        val paperShare: Float = 0f,
        /** 墨占比，调试与阈值校准用 */
        val inkShare: Float = 0f,
    )

    fun analyze(pixels: IntArray, srcW: Int, srcH: Int, gridW: Int, gridH: Int): Result {
        val n = gridW * gridH
        val hits = IntArray(n)
        val inkArgb = IntArray(n)
        val coverage = FloatArray(n)
        val inkCount = IntArray(n)
        val cellCount = IntArray(n)
        val sumR = LongArray(n)
        val sumG = LongArray(n)
        val sumB = LongArray(n)

        val total = srcW * srcH
        val mask = BooleanArray(total)
        var inkTotal = 0
        var paperTotal = 0
        for (y in 0 until srcH) {
            val row = y * srcW
            for (x in 0 until srcW) {
                val p = pixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = 0.299 * r + 0.587 * g + 0.114 * b
                if (l < INK_L) { mask[row + x] = true; inkTotal++ } else if (l >= PAPER_L) paperTotal++
            }
        }
        val paperShare = paperTotal.toDouble() / total
        val inkShare = inkTotal.toDouble() / total
        val midShare = 1.0 - paperShare - inkShare
        val lineArt = paperShare >= LINEART_PAPER_SHARE &&
            inkShare >= LINEART_INK_SHARE &&
            inkShare <= LINEART_INK_MAX &&
            midShare <= LINEART_MID_MAX

        // 覆盖率与墨色：无论是否线稿都算，照片支路也会用到（实心深色块）
        for (y in 0 until srcH) {
            val rowBase = (y.toLong() * gridH / srcH).toInt() * gridW
            val row = y * srcW
            for (x in 0 until srcW) {
                val k = rowBase + (x.toLong() * gridW / srcW).toInt()
                cellCount[k]++
                if (!mask[row + x]) continue
                inkCount[k]++
                val p = pixels[row + x]
                sumR[k] += (p shr 16) and 0xFF
                sumG[k] += (p shr 8) and 0xFF
                sumB[k] += p and 0xFF
            }
        }
        for (i in 0 until n) {
            val ic = inkCount[i]
            if (ic == 0) continue
            coverage[i] = ic.toFloat() / cellCount[i].coerceAtLeast(1)
            inkArgb[i] = (0xFF shl 24) or
                ((sumR[i] / ic).toInt() shl 16) or
                ((sumG[i] / ic).toInt() shl 8) or
                (sumB[i] / ic).toInt()
        }
        if (!lineArt) {
            return Result(hits, inkArgb, coverage, false, paperShare.toFloat(), inkShare.toFloat())
        }

        val thin = mask.copyOf()
        zhangSuen(thin, srcW, srcH)
        for (y in 0 until srcH) {
            val rowBase = (y.toLong() * gridH / srcH).toInt() * gridW
            val row = y * srcW
            for (x in 0 until srcW) {
                if (thin[row + x]) hits[rowBase + (x.toLong() * gridW / srcW).toInt()]++
            }
        }
        return Result(hits, inkArgb, coverage, true, paperShare.toFloat(), inkShare.toFloat())
    }

    /** Zhang-Suen 细化：两子迭代交替删除边界像素，直到不动点 */
    private fun zhangSuen(m: BooleanArray, w: Int, h: Int) {
        val del = ArrayList<Int>(1024)
        var changed = true
        var iter = 0
        while (changed && iter < THIN_MAX_ITER) {
            changed = false
            for (step in 0..1) {
                del.clear()
                for (y in 1 until h - 1) {
                    val row = y * w
                    for (x in 1 until w - 1) {
                        val i = row + x
                        if (!m[i]) continue
                        val p2 = m[i - w]
                        val p3 = m[i - w + 1]
                        val p4 = m[i + 1]
                        val p5 = m[i + w + 1]
                        val p6 = m[i + w]
                        val p7 = m[i + w - 1]
                        val p8 = m[i - 1]
                        val p9 = m[i - w - 1]
                        var b = 0
                        if (p2) b++
                        if (p3) b++
                        if (p4) b++
                        if (p5) b++
                        if (p6) b++
                        if (p7) b++
                        if (p8) b++
                        if (p9) b++
                        if (b < 2 || b > 6) continue
                        // 8 邻域里 0→1 的跳变数：等于 1 才是边界点
                        var a = 0
                        if (!p2 && p3) a++
                        if (!p3 && p4) a++
                        if (!p4 && p5) a++
                        if (!p5 && p6) a++
                        if (!p6 && p7) a++
                        if (!p7 && p8) a++
                        if (!p8 && p9) a++
                        if (!p9 && p2) a++
                        if (a != 1) continue
                        if (step == 0) {
                            if (p2 && p4 && p6) continue
                            if (p4 && p6 && p8) continue
                        } else {
                            if (p2 && p4 && p8) continue
                            if (p2 && p6 && p8) continue
                        }
                        del.add(i)
                    }
                }
                for (i in del) m[i] = false
                if (del.isNotEmpty()) changed = true
            }
            iter++
        }
    }
}
