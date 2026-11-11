package com.pindou.app.core.pattern

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 像素画（放大后的马赛克原图）检测：找出"一个画像素 = 源图 b×b 个真实像素"。
 *
 * 直接按目标网格做面积平均时，一个格子会跨两三个画像素，颜色被白底
 * 稀释成灰粉，量化后花瓣边缘碎成噪点。检测命中后走 ArtQuantizer：
 * 先把画像素还原成一张小图，再按面积多数落格，颜色取自源图中真实
 * 存在的色块。
 *
 * 检测分四步，全部只依赖源像素，不分配源图尺寸的副本：
 *
 * 1. **梯度自相关**：把每一列（行）的相邻像素差累加成一条曲线 g[i]。
 *    色块边界在曲线上是一串等距尖峰，g 与自身平移 b 的相关系数在 b 的
 *    整数倍处出现局部极大。取 6..36 内**最小**的合格极大值，避免把
 *    12px 的块认成 24px 的谐波。
 * 2. **双轴一致**：横竖两条曲线的基频必须接近（±25%）。照片里横向的
 *    键盘按键、栅栏、条纹只在一个方向上有周期。
 * 3. **平坦段集中度**：统计"相邻像素几乎同色"的连续长度直方图。像素画
 *    的平坦段集中在 b 附近；照片的边缘是零散长尾分布。
 * 4. **块内均匀度**：按 b 和相位切开后，每格内部亮度的中位标准差必须
 *    ≤ 1.6。像素画每格内部接近纯色，照片格内带渐变与噪声。
 *
 * 最后用"相位 + 整数基频"反推小数块长，避免整数块长在半幅之后累积漂移。
 */
object PixelArtDetector {

    /** 原生像素画的画布上限：再大就必须按放大后的周期网格检测 */
    private const val MAX_NATIVE_SIDE = 200

    /** 原生像素画的像素总量上限，避免把 200×200 照片误判成 1:1 */
    private const val MAX_NATIVE_PIXELS = 40_000

    /** 原生像素画的颜色桶上限（4bit/通道）。照片缩到 60×60 也远高于这个值 */
    private const val MAX_NATIVE_COLORS = 160

    /** 相邻像素同色占比：原生像素画几乎没有连续渐变 */
    private const val MIN_NATIVE_FLAT_SHARE = 0.55f

    /** 强边占比下限：纯色图和模糊照片都不该进入 1:1 支路 */
    private const val MIN_NATIVE_EDGE_SHARE = 0.015f

    /** 2×2 同色块占比：像素画的色块内部必须成片，噪点图不满足 */
    private const val MIN_NATIVE_BLOCK_SHARE = 0.70f

    /** 小于 6px 的块和 JPEG 压缩噪声无法区分 */
    private const val MIN_BLOCK = 6

    /** 大于 36px 的块说明这是被放大过的低分辨率图，按照片处理更稳 */
    private const val MAX_BLOCK = 36

    /** 相邻像素算"同色"的每通道容差：JPEG 振铃一般在 ±6 以内 */
    private const val FLAT_TOLERANCE = 6

    /** 单轴自相关峰下限 */
    private const val MIN_AUTOCORR = 0.55

    /** 双轴里较弱那条轴的自相关下限：挡住只有单轴有周期纹理的照片 */
    private const val MIN_AXIS_CORR = 0.70

    /** 横竖块尺寸允许的相对偏差（非整数倍缩放的像素画会差 1~2px） */
    private const val AXIS_TOLERANCE = 0.25

    /** 平坦段落在 b±3 内的比例下限 */
    private const val MIN_RUN_CONCENTRATION = 0.30

    /** 格内亮度标准差的中位数上限 */
    private const val MAX_CELL_STD = 1.6

    /** 画像素数上限：再大就说明检测把照片纹理当成了像素画 */
    private const val MAX_ART_CELLS = 15_000

    /** 梯度曲线抽样行/列上限：200 条足够让尖峰稳定，耗时与图片高度解耦 */
    private const val GRADIENT_LINES = 200

    /**
     * @param blockW/blockH 画像素边长（源图像素，小数：801/66 = 12.076）
     * @param artW/artH     还原出来的画像素网格尺寸
     * @param ox/oy         画像素网格相对源图左上角的偏移，放大过的像素画
     *                      常带白边，不校正偏移会让每一格都压在块边界上
     */
    data class Result(
        val blockW: Double,
        val blockH: Double,
        val artW: Int,
        val artH: Int,
        val ox: Double,
        val oy: Double,
    )

    // 单条缓存：同一次生成里 sample() 会被调用两次（主采样 + 未平滑参照），
    // autoTune 还要跑 4~5 组参数，不缓存就会把同一张图分析十遍。
    // 只认同一个数组实例（裁剪/旋转会换数组，自然失效），不做内容哈希。
    private var cachedPixels: IntArray? = null
    private var cachedW = 0
    private var cachedH = 0
    private var cachedValue: Result? = null
    private var cachedValid = false
    private var cachedRelaxed = false
    private var cachedNativeValid = false
    private var cachedNativeValue: Result? = null

    /**
     * 原生 1:1 像素图检测。
     *
     * 60×60 的 PNG 只有一个画像素占一个真实像素，不能用 6..36px 的
     * 「放大块」思路去猜；否则 1px 的格线会被当成噪声，最后掉进 32×32
     * 兜底。这里直接验证「小画布 + 离散色 + 成片色块」三个特征：
     *
     * - 小画布：长边 ≤200、总像素 ≤40000；
     * - 离散色：4bit 色桶 ≤160；
     * - 成片：相邻同色 ≥55%，2×2 同色 ≥70%；
     * - 有内容：强边 ≥1.5%，排除纯色底图。
     *
     * 命中后网格就是源图本身，一格一像素，偏移为 0。
     */
    @Synchronized
    fun detectNative(pixels: IntArray, srcW: Int, srcH: Int): Result? {
        if (cachedNativeValid && cachedPixels === pixels &&
            cachedW == srcW && cachedH == srcH
        ) {
            return cachedNativeValue
        }
        val value = detectNativeUncached(pixels, srcW, srcH)
        cachedPixels = pixels
        cachedW = srcW
        cachedH = srcH
        cachedNativeValue = value
        cachedNativeValid = true
        return value
    }

    private fun detectNativeUncached(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
    ): Result? {
        if (srcW < 8 || srcH < 8) return null
        if (srcW > MAX_NATIVE_SIDE || srcH > MAX_NATIVE_SIDE) return null
        if (srcW * srcH > MAX_NATIVE_PIXELS) return null
        if (pixels.size != srcW * srcH) return null

        var opaque = 0
        var flatPairs = 0
        var pairs = 0
        var strongEdges = 0
        var blockPairs = 0
        var blockTotal = 0
        val colors = HashSet<Int>(256)

        for (y in 0 until srcH) {
            val row = y * srcW
            for (x in 0 until srcW) {
                val p = pixels[row + x]
                val transparent = (p ushr 24) < 32
                if (!transparent) {
                    opaque++
                    colors.add(p and 0xF0F0F0)
                } else {
                    colors.add(0)
                }

                if (x > 0) {
                    pairs++
                    val q = pixels[row + x - 1]
                    if (samePixel(p, q, FLAT_TOLERANCE)) flatPairs++
                    if (edgeDiff(p, q) >= 24) strongEdges++
                }
                if (y > 0) {
                    pairs++
                    val q = pixels[row - srcW + x]
                    if (samePixel(p, q, FLAT_TOLERANCE)) flatPairs++
                    if (edgeDiff(p, q) >= 24) strongEdges++
                }
                if (x > 0 && y > 0) {
                    blockTotal++
                    val a = pixels[row + x - 1]
                    val b = pixels[row - srcW + x - 1]
                    val c = pixels[row - srcW + x]
                    if (samePixel(p, a, 8) && samePixel(p, b, 8) && samePixel(p, c, 8)) {
                        blockPairs++
                    }
                }
            }
        }

        // 全透明图没有可拼豆的内容；占比太低说明只是一张带透明通道的图标。
        if (opaque < srcW * srcH / 4) return null
        if (colors.size > MAX_NATIVE_COLORS) return null
        val flatShare = if (pairs == 0) 0f else flatPairs.toFloat() / pairs
        val edgeShare = if (pairs == 0) 0f else strongEdges.toFloat() / pairs
        val blockShare = if (blockTotal == 0) 0f else blockPairs.toFloat() / blockTotal
        if (flatShare < MIN_NATIVE_FLAT_SHARE) return null
        if (edgeShare < MIN_NATIVE_EDGE_SHARE) return null
        if (blockShare < MIN_NATIVE_BLOCK_SHARE) return null

        return Result(
            blockW = 1.0,
            blockH = 1.0,
            artW = srcW,
            artH = srcH,
            ox = 0.0,
            oy = 0.0,
        )
    }

    private fun samePixel(a: Int, b: Int, tolerance: Int): Boolean {
        val at = (a ushr 24) < 32
        val bt = (b ushr 24) < 32
        if (at || bt) return at && bt
        return channelDiff(a, b) <= tolerance
    }

    private fun edgeDiff(a: Int, b: Int): Int {
        val at = (a ushr 24) < 32
        val bt = (b ushr 24) < 32
        if (at || bt) return if (at == bt) 0 else 255
        return channelDiff(a, b)
    }

    /**
     * @param relaxed 宽松档：用户显式选了「像素画生成」时用。放宽自相关、
     *   平坦段集中度和格内均匀度门槛，代价是可能把「很像像素画」的照片
     *   也认成像素画。自动识别永远用严格档。
     */
    @Synchronized
    fun detect(pixels: IntArray, srcW: Int, srcH: Int, relaxed: Boolean = false): Result? {
        if (cachedValid && cachedPixels === pixels && cachedW == srcW &&
            cachedH == srcH && cachedRelaxed == relaxed
        ) {
            return cachedValue
        }
        val value = detectUncached(pixels, srcW, srcH, relaxed)
        cachedPixels = pixels
        cachedW = srcW
        cachedH = srcH
        cachedValue = value
        cachedValid = true
        cachedRelaxed = relaxed
        return value
    }

    private fun detectUncached(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        relaxed: Boolean,
    ): Result? {
        if (srcW < 32 || srcH < 32) return null
        val minAutocorr = if (relaxed) 0.40 else MIN_AUTOCORR
        val minAxisCorr = if (relaxed) 0.55 else MIN_AXIS_CORR
        val minRunConc = if (relaxed) 0.18 else MIN_RUN_CONCENTRATION
        val maxCellStd = if (relaxed) 2.8 else MAX_CELL_STD
        val gx = gradientProfile(pixels, srcW, srcH, horizontal = true)
        val gy = gradientProfile(pixels, srcW, srcH, horizontal = false)
        val corrX = autocorrelation(gx)
        val corrY = autocorrelation(gy)
        val fx = fundamentalLag(corrX, minAutocorr) ?: return null
        val fy = fundamentalLag(corrY, minAutocorr) ?: return null
        if (abs(fx - fy).toDouble() / max(fx, fy) > AXIS_TOLERANCE) return null
        if (min(corrX[fx], corrY[fy]) < minAxisCorr) return null

        val b0 = ((fx + fy) / 2.0).roundToInt()
        val runX = runHistogram(pixels, srcW, srcH, horizontal = true)
        val runY = runHistogram(pixels, srcW, srcH, horizontal = false)
        if (concentration(runX, b0) < minRunConc) return null
        if (concentration(runY, b0) < minRunConc) return null

        // 相位只在整数基频上估计：块长本身是小数时，相位与块长互相补偿，
        // 同时搜索会产生多个等价解，先固定相位再搜块长。
        val ox = bestPhase(gx, b0)
        val oy = bestPhase(gy, b0)
        val artW = ((srcW - ox) / b0).roundToInt()
        val artH = ((srcH - oy) / b0).roundToInt()
        if (artW < 8 || artH < 8) return null
        if (artW > 200 || artH > 200) return null
        if (artW * artH > MAX_ART_CELLS) return null

        // 小数块长 = 总跨度 / 格数，行/列各算一份：像素画被等比缩放过之后
        // 横竖块长会差千分之几，共用一个值会在一侧累积漂移。
        val blockW = (srcW - ox) / artW
        val blockH = (srcH - oy) / artH
        if (blockW <= 0.0 || blockH <= 0.0) return null
        if (abs(blockW - blockH) / max(blockW, blockH) > AXIS_TOLERANCE) return null
        if (cellStdMedian(pixels, srcW, srcH, blockW, blockH, ox, oy, artW, artH) > maxCellStd) {
            return null
        }
        return Result(blockW, blockH, artW, artH, ox, oy)
    }

    /**
     * 强制档：用户在「像素画生成」入口导入时调用。
     *
     * 逐级放宽：严格 → 宽松 → 平坦段众数兜底。兜底这一级只看"最常见的
     * 同色游程长度"，不做自相关和格内均匀度——一张被二次压缩、带缩放
     * 模糊的像素画，自相关峰会掉到 0.3 以下，但平坦段长度仍然聚在块长上。
     */
    @Synchronized
    fun detectForced(pixels: IntArray, srcW: Int, srcH: Int): Result? {
        detect(pixels, srcW, srcH, relaxed = false)?.let { return it }
        detect(pixels, srcW, srcH, relaxed = true)?.let { return it }
        return detectByRuns(pixels, srcW, srcH)
    }

    private fun detectByRuns(pixels: IntArray, srcW: Int, srcH: Int): Result? {
        if (srcW < 32 || srcH < 32) return null
        val runX = runHistogram(pixels, srcW, srcH, horizontal = true)
        val runY = runHistogram(pixels, srcW, srcH, horizontal = false)
        val bx = modalRun(runX) ?: return null
        val by = modalRun(runY) ?: return null
        if (abs(bx - by) > max(2, max(bx, by) / 4)) return null
        val b = ((bx + by) / 2.0).roundToInt()
        if (b < 4 || b > 64) return null
        val gx = gradientProfile(pixels, srcW, srcH, horizontal = true)
        val gy = gradientProfile(pixels, srcW, srcH, horizontal = false)
        val ox = bestPhase(gx, b)
        val oy = bestPhase(gy, b)
        val artW = ((srcW - ox) / b).roundToInt()
        val artH = ((srcH - oy) / b).roundToInt()
        if (artW < 8 || artH < 8 || artW > 200 || artH > 200) return null
        if (artW * artH > MAX_ART_CELLS) return null
        val blockW = (srcW - ox) / artW
        val blockH = (srcH - oy) / artH
        if (blockW <= 0.0 || blockH <= 0.0) return null
        // 兜底档只挡"格内明显是连续渐变"的照片（方差大），不再要求纯色
        if (cellStdMedian(pixels, srcW, srcH, blockW, blockH, ox, oy, artW, artH) > 4.0) {
            return null
        }
        return Result(blockW, blockH, artW, artH, ox, oy)
    }

    /** 平坦段直方图里计数最高的长度（加权中位数附近取峰），要求占比 ≥ 10% */
    private fun modalRun(run: Pair<IntArray, Int>): Int? {
        val (hist, total) = run
        if (total < 40) return null
        var bestLen = 0
        var bestCnt = 0
        for (l in 3 until hist.size) {
            if (hist[l] > bestCnt) {
                bestCnt = hist[l]
                bestLen = l
            }
        }
        if (bestLen == 0) return null
        // 合并相邻 ±1 的计数：块长常是 12.5，直方图会劈成 12 和 13 两个峰
        var merged = bestCnt
        if (bestLen > 3) merged += hist[bestLen - 1]
        if (bestLen + 1 < hist.size) merged += hist[bestLen + 1]
        if (merged.toDouble() / total < 0.10) return null
        return bestLen
    }

    /**
     * 边界梯度曲线：`out[i]` = 第 i 列（行）与前一列（行）的三通道差之和。
     *
     * 用 RGB 三通道差而不是亮度差：浅粉和淡黄亮度几乎一样，按亮度算
     * 这条边界就是"看不见"的，网格会锁到 2 倍块长上。
     */
    private fun gradientProfile(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        horizontal: Boolean,
    ): DoubleArray {
        val len = if (horizontal) srcW else srcH
        val lines = if (horizontal) srcH else srcW
        val out = DoubleArray(len)
        val lineStep = max(1, lines / GRADIENT_LINES)
        var line = 0
        while (line < lines) {
            var prev = pixels[if (horizontal) line * srcW else line]
            var i = 1
            while (i < len) {
                val cur = if (horizontal) pixels[line * srcW + i] else pixels[i * srcW + line]
                out[i] += abs((cur shr 16 and 0xFF) - (prev shr 16 and 0xFF)) +
                    abs((cur shr 8 and 0xFF) - (prev shr 8 and 0xFF)) +
                    abs((cur and 0xFF) - (prev and 0xFF))
                prev = cur
                i++
            }
            line += lineStep
        }
        return out
    }

    /**
     * Pearson 自相关：`r[lag]` = g 与平移 lag 后的相关系数。
     *
     * 去均值是必须的：梯度曲线绝大部分接近 0、尖峰只占少数位置，不去均值
     * 的话任何 lag 的相关系数都接近 1（直流分量主导），照片也会"有周期"。
     * 两段各自按自身方差归一化，长 lag 不会因为样本变少而系统性偏低。
     *
     * 复杂度 O(len × MAX_BLOCK)，len ≤ 2048，可忽略。
     */
    private fun autocorrelation(profile: DoubleArray): DoubleArray {
        val n = profile.size
        val out = DoubleArray(MAX_BLOCK + 8)
        if (n < MAX_BLOCK * 2) return out
        var mean = 0.0
        for (v in profile) mean += v
        mean /= n
        val p = DoubleArray(n) { profile[it] - mean }
        for (lag in 1 until out.size) {
            var sum = 0.0
            var a = 0.0
            var b = 0.0
            val end = n - lag
            var i = 0
            while (i < end) {
                val x = p[i]
                val y = p[i + lag]
                sum += x * y
                a += x * x
                b += y * y
                i++
            }
            out[lag] = if (a <= 1e-9 || b <= 1e-9) 0.0 else sum / sqrt(a * b)
        }
        return out
    }

    /**
     * 基频：6..36 内第一个"局部极大且 ≥ MAX(0.55, 全局峰 × 0.8)"的 lag。
     *
     * 取**最小**合格 lag 是关键：真块长 12 的图在 24 处也有一个几乎等高的
     * 峰（每个边界每隔两块重复一次），取大的会把网格直接减半。
     */
    private fun fundamentalLag(corr: DoubleArray, minAutocorr: Double): Int? {
        var best = 0.0
        for (l in MIN_BLOCK..MAX_BLOCK) {
            if (!isLocalMax(corr, l)) continue
            if (corr[l] > best) best = corr[l]
        }
        if (best < minAutocorr) return null
        val floor = max(minAutocorr, best * 0.80)
        for (l in MIN_BLOCK..MAX_BLOCK) {
            if (isLocalMax(corr, l) && corr[l] >= floor) return l
        }
        return null
    }

    private fun isLocalMax(corr: DoubleArray, lag: Int): Boolean =
        lag + 1 < corr.size && corr[lag] >= corr[lag - 1] && corr[lag] >= corr[lag + 1]

    /**
     * 固定块长 [b]，在 0..b（步长 0.25）里找边界梯度均值最大的相位。
     * 线性插值：块长常常是小数，四舍五入到整数会让整条曲线在 ±1px 抖动。
     */
    private fun bestPhase(profile: DoubleArray, b: Int): Double {
        val len = profile.size
        var bestScore = Double.NEGATIVE_INFINITY
        var bestPhase = 0.0
        var off = 0.0
        while (off < b) {
            var sum = 0.0
            var cnt = 0
            var k = 1
            while (true) {
                val pos = off + k * b
                if (pos >= len - 1) break
                val i0 = pos.toInt()
                val f = pos - i0
                sum += profile[i0] * (1 - f) + profile[i0 + 1] * f
                cnt++
                k++
            }
            if (cnt >= 8) {
                val score = sum / cnt
                if (score > bestScore) {
                    bestScore = score
                    bestPhase = off
                }
            }
            off += 0.25
        }
        return bestPhase
    }

    /**
     * 连续同色长度直方图；只按行抽样，线内逐像素比较。
     * 返回 `[hist, total]`，hist 下标即连续长度。
     */
    private fun runHistogram(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        horizontal: Boolean,
    ): Pair<IntArray, Int> {
        val cap = MAX_BLOCK * 3 + 4
        val hist = IntArray(cap)
        var total = 0
        val lines = if (horizontal) srcH else srcW
        val len = if (horizontal) srcW else srcH
        val lineStep = max(1, lines / 400)
        var line = 0
        while (line < lines) {
            var run = 1
            var prev = if (horizontal) pixels[line * srcW] else pixels[line]
            var i = 1
            while (i < len) {
                val p = if (horizontal) pixels[line * srcW + i] else pixels[i * srcW + line]
                if (channelDiff(prev, p) <= FLAT_TOLERANCE) {
                    run++
                } else {
                    if (run in 3 until cap) {
                        hist[run]++
                        total++
                    }
                    run = 1
                }
                prev = p
                i++
            }
            if (run in 3 until cap) {
                hist[run]++
                total++
            }
            line += lineStep
        }
        return hist to total
    }

    /** 平坦段落在 b±3 内的比例。JPEG 会把块边界糊成 3~4px 过渡带，真块长 12 的图峰值常在 9。 */
    private fun concentration(run: Pair<IntArray, Int>, b: Int): Double {
        val (hist, total) = run
        if (total < 50) return 0.0
        var c = 0
        for (l in max(3, b - 3)..min(hist.size - 1, b + 3)) c += hist[l]
        return c.toDouble() / total
    }

    /**
     * 块内均匀度：每格取中心 60% 的区域算亮度标准差，返回中位数。
     *
     * 用中位数而不是均值：像素画里本来就有大面积纯色背景，均值会被它拉低，
     * 照片只要花纹部分占一定比例就能蒙混过关。中位数要求"过半格子是纯色"。
     * 最多取 80×80 格（6400 个样本），复杂度 O(格数 × 格内像素)。
     */
    private fun cellStdMedian(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        blockW: Double,
        blockH: Double,
        ox: Double,
        oy: Double,
        artW: Int,
        artH: Int,
    ): Double {
        val cols = min(artW, 80)
        val rows = min(artH, 80)
        val stds = DoubleArray(cols * rows)
        var n = 0
        for (ay in 0 until rows) {
            val y0 = (oy + ay * blockH + blockH * 0.2).toInt().coerceIn(0, srcH - 1)
            val y1 = (oy + (ay + 1) * blockH - blockH * 0.2).toInt().coerceIn(y0 + 1, srcH)
            for (ax in 0 until cols) {
                val x0 = (ox + ax * blockW + blockW * 0.2).toInt().coerceIn(0, srcW - 1)
                val x1 = (ox + (ax + 1) * blockW - blockW * 0.2).toInt().coerceIn(x0 + 1, srcW)
                var sum = 0.0
                var sum2 = 0.0
                var cnt = 0
                var y = y0
                while (y < y1) {
                    val row = y * srcW
                    var x = x0
                    while (x < x1) {
                        val p = pixels[row + x]
                        val l = 0.299 * ((p shr 16) and 0xFF) +
                            0.587 * ((p shr 8) and 0xFF) +
                            0.114 * (p and 0xFF)
                        sum += l
                        sum2 += l * l
                        cnt++
                        x++
                    }
                    y++
                }
                if (cnt > 1) {
                    val m = sum / cnt
                    stds[n++] = sqrt(max(0.0, sum2 / cnt - m * m))
                }
            }
        }
        if (n == 0) return Double.MAX_VALUE
        stds.sort(0, n)
        return stds[n / 2]
    }

    private fun channelDiff(p: Int, q: Int): Int {
        val dr = abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
        val dg = abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
        val db = abs((p and 0xFF) - (q and 0xFF))
        return max(dr, max(dg, db))
    }
}
