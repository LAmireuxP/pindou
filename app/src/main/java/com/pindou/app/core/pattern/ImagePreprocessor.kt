package com.pindou.app.core.pattern

import com.pindou.app.core.color.ColorConversions
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 过滤等级：对齐颂拼豆「导入前处理」的四档（无/低/中/高）。
 *
 * 只影响格子层面的降噪强度，不改变采样分辨率：
 * - [clusterK]        Lab k-means 目标簇数（先聚类成大片再映射色卡）
 * - [modePasses]      邻域众数滤波轮数（消除单格跳色）
 * - [smoothPasses]    2× 中间图上 3×3 盒式平滑轮数（吃掉照片纹理高频）
 * - [regionFraction]  小连通块合并阈值（占格数比例）
 * - [regionKeepDeltaE] 合并时允许并入的色差上限（越大合得越狠）
 * - [detailThreshold] 细节掩码阈值（Lab ΔE），越低保护范围越大
 *
 * 之所以先在 2× 中间图上平滑、再 2×2 合到目标网格：盒式平均本身就是
 * 面积加权重采样，比"块内取众数"稳定——众数桶会在相邻块之间来回翻转，
 * 这正是旧管线"撒胡椒面"噪点的来源。
 */
enum class FilterLevel(
    val label: String,
    val clusterK: Int,
    val modePasses: Int,
    val smoothPasses: Int,
    val regionFraction: Double,
    val regionKeepDeltaE: Double,
    val detailThreshold: Double,
    /** 像素画支路的用色上限：源图本身已是离散色块，按面积加权聚类压到这个数 */
    val artMaxColors: Int,
) {
    // 簇数按"图面还剩下多少独立色块"递减：照片的纹理色在 64 簇下已
    // 明显成片，再少就会把相邻材质的边界也吞掉。
    // regionKeepDeltaE 放得比较宽：真实细节已由细节掩码锁定，
    // 小区域阶段的任务就是把跨色阶的碎点也并掉（颂拼豆同样不按色差豁免）。
    // 像素画支路 0 = 不做聚类，逐画像素直接映射色卡（保留原图每一层配色）
    NONE("无过滤", 64, 0, 0, 0.00035, 45.0, 22.0, 0),
    LOW("低过滤", 44, 1, 1, 0.0016, 35.0, 20.0, 36),
    MEDIUM("中过滤", 30, 1, 2, 0.0030, 30.0, 18.0, 28),
    HIGH("高过滤", 20, 2, 3, 0.0045, 25.0, 16.0, 22),
}

/**
 * 像素画还原出来的画像素网格。
 *
 * 与目标网格分开保存：目标是"豆子网格"，画像素是"源图的真实色块"。
 * 量化要按画像素的**面积权重**做，而不是按目标格数——目标格是均匀的，
 * 一颗只占 2 格的深色眼珠和一片 2000 格的白底在 k-means 里权重相同，
 * 簇心就会被大片浅色拉走。
 */
class ArtGrid(val argb: IntArray, val w: Int, val h: Int)

/**
 * 采样结果：每格 ARGB + 每格 Lab（三个 float 一组，下游免重复转换）。
 *
 * [edge] 标记"这一格跨在真实边界上"（格内源像素亮度双峰明显）。下游的
 * 量化回填、众数滤波、小区域合并都靠它锁定边界格，避免把刚锐化出来的
 * 边缘又糊回去。像素画支路没有这个概念，保持 null。
 */
class GridSample(
    val argb: IntArray,
    val lab: FloatArray,
    val art: ArtGrid? = null,
    val edge: BooleanArray? = null,
    /**
     * 每格"近白像素"占比（0..100）。眼睛高光、牙齿这类嵌在深色块里的小亮斑
     * 面积不足一格，面积平均后只剩一格浅灰；下游 [HighlightRescue] 靠这个
     * 数值把高光格重新点成白豆。
     */
    val spot: ByteArray? = null,
) {
    val size: Int get() = argb.size
}

/**
 * 图像 → 网格采样。
 *
 * 流程：源像素面积累加到 2× 目标网格 → 按等级做 3×3 盒式平滑 →
 * 2×2 平均落到目标网格 → 逐格转 Lab。全程 O(源像素 + 网格格数)，
 * 不分配源图尺寸的副本，12MP 图在手机上也是百毫秒级。
 *
 * 采样用累加平均而不是最近邻/众数：一个格子覆盖上千像素时，平均值
 * 天然抑制纹理噪声，同时保留区域边界的过渡；众数/最近邻会把同一区域
 * 内相邻格子映射到不同色号，形成棋盘格噪点。
 */
object ImagePreprocessor {

    private const val SCALE = 2

    /** 单块众数统计的桶数上限：一块里出现 24 种以上颜色说明检测本身不可信 */
    private const val MODE_BUCKETS = 24

    fun sample(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        gridW: Int,
        gridH: Int,
        level: FilterLevel,
    ): GridSample {
        require(gridW in 1..500 && gridH in 1..500) { "网格尺寸越界: ${gridW}x$gridH" }
        require(srcW > 0 && srcH > 0) { "源图尺寸非法: ${srcW}x$srcH" }
        require(pixels.size >= srcW * srcH) { "像素数 ${pixels.size} < ${srcW}x$srcH" }

        // 像素画支路：源图本身是放大过的像素画时，先还原画像素网格再落格。
        // 旧检测器把 801×743 认成 24px 块（谐波），2026-11 改成自相关基频 +
        // 平坦段集中度 + 块内均匀度三重判据，评测集 3 张像素画全部命中、
        // 5 张照片全部拒绝。命中后颜色只取源图里真实存在的那些，不会被
        // 白底稀释成灰粉。
        val art = PixelArtDetector.detect(pixels, srcW, srcH)
        if (art != null) {
            return sampleArt(pixels, srcW, srcH, gridW, gridH, art)
        }
        val iw = gridW * SCALE
        val ih = gridH * SCALE
        val n = iw * ih
        val accR = DoubleArray(n)
        val accG = DoubleArray(n)
        val accB = DoubleArray(n)
        val counts = IntArray(n)

        // 每个源像素按中心落入的中间格累加：等价于面积加权重采样，
        // 比逐格算覆盖比例简单一个量级，且不会在格子边界漏掉像素。
        for (y in 0 until srcH) {
            val iy = (y.toLong() * ih / srcH).toInt()
            val row = y * srcW
            val rowBase = iy * iw
            for (x in 0 until srcW) {
                val ix = (x.toLong() * iw / srcW).toInt()
                val p = pixels[row + x]
                val k = rowBase + ix
                accR[k] += (p shr 16) and 0xFF
                accG[k] += (p shr 8) and 0xFF
                accB[k] += p and 0xFF
                counts[k]++
            }
        }

        val r = DoubleArray(n)
        val g = DoubleArray(n)
        val b = DoubleArray(n)
        for (i in 0 until n) {
            val c = counts[i]
            if (c > 0) {
                r[i] = accR[i] / c
                g[i] = accG[i] / c
                b[i] = accB[i] / c
            } else {
                // 源图比 2× 中间图还小的时候（例如 8×8 图配 8×8 网格，中间图 16×16），
                // 只有偶数下标落到源像素上，奇数下标的格子计数为 0。
                // 留 0 会被当成黑色参与 2×2 平均，整格颜色被拉暗——直接按中心点
                // 回源图取色补上，等价于最近邻放大。
                val sx = ((i % iw) * srcW / iw).coerceIn(0, srcW - 1)
                val sy = ((i / iw) * srcH / ih).coerceIn(0, srcH - 1)
                val p = pixels[sy * srcW + sx]
                r[i] = ((p shr 16) and 0xFF).toDouble()
                g[i] = ((p shr 8) and 0xFF).toDouble()
                b[i] = (p and 0xFF).toDouble()
            }
        }

        repeat(level.smoothPasses) {
            edgeAwareBlur3(r, g, b, iw, ih)
        }

        val argb = IntArray(gridW * gridH)
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val i00 = (gy * SCALE) * iw + gx * SCALE
                val i01 = i00 + 1
                val i10 = i00 + iw
                val i11 = i10 + 1
                // 目标图可能小于 2×（例如 1×1 网格时 iw=2），边界索引已在
                // 累加阶段映射到合法范围，这里不会越界。
                val rr = ((r[i00] + r[i01] + r[i10] + r[i11]) * 0.25 + 0.5).toInt().coerceIn(0, 255)
                val gg = ((g[i00] + g[i01] + g[i10] + g[i11]) * 0.25 + 0.5).toInt().coerceIn(0, 255)
                val bb = ((b[i00] + b[i01] + b[i10] + b[i11]) * 0.25 + 0.5).toInt().coerceIn(0, 255)
                argb[gy * gridW + gx] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }

        // ①b 边界格锐化：一格覆盖几十个源像素时，格子常常一半是人脸一半是头发。
        //     面积平均把两侧混成"中间色"，相邻两格都是模糊过渡色，量化后并成
        //     同一色号——这就是观感上最刺眼的"糊边"。这里只对格内亮度双峰明显
        //     的格子改取多数侧颜色，平地区域一个像素都不动。
        val edgeMask = BooleanArray(gridW * gridH)
        val spot = ByteArray(gridW * gridH)
        sharpenEdgeCells(pixels, srcW, srcH, gridW, gridH, argb, edgeMask, spot)
        // ①c 墨线优先：低网格（29×29）下一条 8px 描边只覆盖一格 20%，
        //     面积平均后是浅灰，量化完就是断断续续的虚线。凡是"格内有墨、
        //     但大部分面积仍是纸白"的格子，直接取墨色，线才连得起来。
        inkLineCells(pixels, srcW, srcH, gridW, gridH, argb, edgeMask)

        val lab = FloatArray(argb.size * 3)
        for (i in argb.indices) {
            val l = ColorConversions.argbToLab(argb[i])
            lab[i * 3] = l[0]
            lab[i * 3 + 1] = l[1]
            lab[i * 3 + 2] = l[2]
        }
        return GridSample(argb, lab, null, edgeMask, spot)
    }

    /** 格内亮度双峰判据：标准差下限（挡住均匀纹理） */
    private const val EDGE_MIN_STD = 14.0

    /** 格内亮度双峰判据：两侧平均亮度差下限（挡住渐变与噪点） */
    private const val EDGE_MIN_SEPARATION = 24.0

    /** 少数侧最低占比：低于它说明只是零星噪点或一根发丝，不值得翻转整格 */
    private const val EDGE_MIN_MINORITY = 0.12

    /** 细深色结构保留：深浅两侧亮度差下限（轮廓线、睫毛、描边） */
    private const val EDGE_DARK_KEEP_SEPARATION = 34.0

    /** 细深色结构保留：深色侧最低占比（低于它仍按多数侧取色，避免噪点翻黑） */
    private const val EDGE_DARK_KEEP_SHARE = 0.22

    /** "近白"亮度下限（0..255）：白豆、纸白底、眼球高光都落在这条线以上 */
    private const val BRIGHT_L = 225.0

    /** 细线桥接：深色占比下限（低于它连"擦到一格"都算不上） */
    private const val BRIDGE_MIN_SHARE = 0.10f

    /** 细线桥接：深色侧亮度上限（L 高于它的深色是阴影，不是墨线） */
    private const val BRIDGE_INK_L = 120f

    /**
     * 边界格锐化：把"跨边界格子"的颜色换成占多数那一侧的平均色。
     *
     * 三步判据全部只看格内源像素，复杂度 O(源像素 + 格数)，两次遍历：
     * 1. 格内亮度标准差 > [EDGE_MIN_STD]（格子里确实有两种明暗）；
     * 2. 按格内亮度均值二分后，两侧平均亮度差 > [EDGE_MIN_SEPARATION]（是双峰，不是渐变）；
     * 3. 少数侧占比 > [EDGE_MIN_MINORITY]（不是噪点）。
     *
     * 命中后 argb 与 edgeMask 就地改写。多数侧取"颜色多的那一侧"而不是
     * "更亮的那一侧"：深色眉毛落在浅色额头上时，多数侧是额头，格子就该是肤色，
     * 眉毛由相邻格去表达——这样边界是刀切的，而不是两边都糊成灰。
     */
    private fun sharpenEdgeCells(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        gridW: Int,
        gridH: Int,
        argb: IntArray,
        edgeMask: BooleanArray,
        spot: ByteArray,
    ) {
        val n = gridW * gridH
        val count = IntArray(n)
        val brightCount = IntArray(n)
        val sumL = DoubleArray(n)
        val sumL2 = DoubleArray(n)
        for (y in 0 until srcH) {
            val rowBase = (y.toLong() * gridH / srcH).toInt() * gridW
            val row = y * srcW
            for (x in 0 until srcW) {
                val p = pixels[row + x]
                val l = 0.299 * ((p shr 16) and 0xFF) +
                    0.587 * ((p shr 8) and 0xFF) +
                    0.114 * (p and 0xFF)
                val k = rowBase + (x.toLong() * gridW / srcW).toInt()
                count[k]++
                if (l >= BRIGHT_L) brightCount[k]++
                sumL[k] += l
                sumL2[k] += l * l
            }
        }

        val meanL = DoubleArray(n)
        val candidate = BooleanArray(n)
        var any = false
        for (i in 0 until n) {
            val c = count[i]
            if (c < 4) continue
            spot[i] = (brightCount[i] * 100 / c).toByte()
            val m = sumL[i] / c
            meanL[i] = m
            val varr = sumL2[i] / c - m * m
            if (varr > EDGE_MIN_STD * EDGE_MIN_STD) {
                candidate[i] = true
                any = true
            }
        }
        if (!any) return

        // 第二遍：候选格按亮度均值二分，累计两侧的像素数与颜色
        val loCnt = IntArray(n)
        val hiCnt = IntArray(n)
        val loL = DoubleArray(n)
        val hiL = DoubleArray(n)
        val loR = DoubleArray(n); val loG = DoubleArray(n); val loB = DoubleArray(n)
        val hiR = DoubleArray(n); val hiG = DoubleArray(n); val hiB = DoubleArray(n)
        // 深色侧统计：低网格下细线只占一格的一小半，按"多数侧"取色会判成白，
        // 整条轮廓断成虚线。这里把深色侧的占比与颜色留下来，供第三遍桥接用。
        val darkShare = FloatArray(n)
        val darkArgb = IntArray(n)
        val darkL = FloatArray(n) { Float.MAX_VALUE }
        for (y in 0 until srcH) {
            val rowBase = (y.toLong() * gridH / srcH).toInt() * gridW
            val row = y * srcW
            for (x in 0 until srcW) {
                val k = rowBase + (x.toLong() * gridW / srcW).toInt()
                if (!candidate[k]) continue
                val p = pixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = 0.299 * r + 0.587 * g + 0.114 * b
                if (l >= meanL[k]) {
                    hiCnt[k]++; hiL[k] += l; hiR[k] += r; hiG[k] += g; hiB[k] += b
                } else {
                    loCnt[k]++; loL[k] += l; loR[k] += r; loG[k] += g; loB[k] += b
                }
            }
        }

        for (i in 0 until n) {
            if (!candidate[i]) continue
            val lc = loCnt[i]
            val hc = hiCnt[i]
            if (lc == 0 || hc == 0) continue
            val loMeanL = loL[i] / lc
            val hiMeanL = hiL[i] / hc
            if (hiMeanL - loMeanL < EDGE_MIN_SEPARATION) continue
            val total = lc + hc
            val minority = min(lc, hc).toDouble() / total
            if (minority < EDGE_MIN_MINORITY) continue
            // 多数侧取色
            var useHi = hc >= lc
            // 细深色结构保留：轮廓线/睫毛这类 1 格宽深色线条，面积永远小于
            // 浅色底。只按"多数侧"取色会把它们整条抹掉（耳朵轮廓消失就是
            // 这么来的）。对比够强（亮度差 ≥ 34）且深色侧占到 22% 时改取
            // 深色侧，线条在量化后能留下一格，不会被背景吃掉。
            //
            // 注意方向：hi 分组是"亮度 ≥ 格均值"的那一半，天然更亮；深色组是 lo。
            // 早前这里写成 darkIsHi = hiMeanL > loMeanL 并在随后取 hiMeanL 当
            // darkMeanL，判断恒为反向——细线保留规则反而把深色格刷成纯白，
            // 眼睛、耳朵描边被整片吃掉。判据必须是"哪一组更暗"。
            val lightMeanL = max(loMeanL, hiMeanL)
            val darkMeanL = min(loMeanL, hiMeanL)
            val darkCnt = if (loMeanL <= hiMeanL) lc else hc

            // 记录深色侧（不论最终选没选它）
            val darkIsLo = loMeanL <= hiMeanL
            val dR = ((if (darkIsLo) loR[i] else hiR[i]) / darkCnt + 0.5).toInt().coerceIn(0, 255)
            val dG = ((if (darkIsLo) loG[i] else hiG[i]) / darkCnt + 0.5).toInt().coerceIn(0, 255)
            val dB = ((if (darkIsLo) loB[i] else hiB[i]) / darkCnt + 0.5).toInt().coerceIn(0, 255)
            darkArgb[i] = (0xFF shl 24) or (dR shl 16) or (dG shl 8) or dB
            darkShare[i] = darkCnt.toFloat() / total
            darkL[i] = darkMeanL.toFloat()

            if (lightMeanL - darkMeanL >= EDGE_DARK_KEEP_SEPARATION &&
                darkCnt.toDouble() / total >= EDGE_DARK_KEEP_SHARE
            ) {
                useHi = hiMeanL < loMeanL
            }
            val c = if (useHi) hc else lc
            val rr = ((if (useHi) hiR[i] else loR[i]) / c + 0.5).toInt().coerceIn(0, 255)
            val gg = ((if (useHi) hiG[i] else loG[i]) / c + 0.5).toInt().coerceIn(0, 255)
            val bb = ((if (useHi) hiB[i] else loB[i]) / c + 0.5).toInt().coerceIn(0, 255)
            argb[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            edgeMask[i] = true
        }

        // 第三遍：细线桥接。
        //
        // 29×29 这类低网格上，一条 8px 宽的轮廓线只覆盖一格 10%~20%，
        // 上面"深色占比 ≥ 22%"的判据会让它判成白，整条线断成虚线。
        // 对"深色占比 ≥ [BRIDGE_MIN_SHARE]、深色侧确实是墨（L < [BRIDGE_INK_L]）"
        // 的格子看 8 邻域：上下或左右两侧都已判成深色，就把这格补成墨色，
        // 把线接起来。只补被墨色夹住的格，不会把浅色区域涂黑。
        for (y in 0 until gridH) {
            for (x in 0 until gridW) {
                val i = y * gridW + x
                if (edgeMask[i]) continue
                if (darkShare[i] < BRIDGE_MIN_SHARE || darkL[i] >= BRIDGE_INK_L) continue
                val up = y > 0 && edgeMask[i - gridW]
                val down = y < gridH - 1 && edgeMask[i + gridW]
                val left = x > 0 && edgeMask[i - 1]
                val right = x < gridW - 1 && edgeMask[i + 1]
                if (!(up && down) && !(left && right)) continue
                argb[i] = darkArgb[i]
                edgeMask[i] = true
            }
        }
    }

    /**
     * 墨线优先：格内同时有"墨"和"纸"时，格子取墨色。
     *
     * 判据只认两类像素：亮度 < [INK_DARK_L] 的墨、亮度 ≥ [PAPER_L] 的纸白。
     * 两者都达到比例才动手，等于要求"这是一条画在白纸上的线"，照片里
     * 的深色块和半调阴影不会命中（它们旁边没有成片的纸白）。
     * 墨占比上限挡住"整格都是墨"的情况，避免重复计算。
     *
     * 复杂度 O(源像素 + 格数)，只加一次遍历。
     */
    private fun inkLineCells(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        gridW: Int,
        gridH: Int,
        argb: IntArray,
        edgeMask: BooleanArray,
    ) {
        val n = gridW * gridH
        val inkCnt = IntArray(n)
        val paperCnt = IntArray(n)
        val sR = LongArray(n)
        val sG = LongArray(n)
        val sB = LongArray(n)
        for (y in 0 until srcH) {
            val rowBase = (y.toLong() * gridH / srcH).toInt() * gridW
            val row = y * srcW
            for (x in 0 until srcW) {
                val p = pixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = 0.299 * r + 0.587 * g + 0.114 * b
                val k = rowBase + (x.toLong() * gridW / srcW).toInt()
                if (l < INK_DARK_L) {
                    inkCnt[k]++
                    sR[k] += r; sG[k] += g; sB[k] += b
                } else if (l >= PAPER_L) {
                    paperCnt[k]++
                }
            }
        }
        val inkShare = FloatArray(n)
        for (i in 0 until n) {
            val ic = inkCnt[i]
            if (ic == 0) continue
            val pc = paperCnt[i]
            val total = ic + pc
            if (total == 0) continue
            val share = ic.toFloat() / total
            inkShare[i] = share
            if (pc.toFloat() / total < PAPER_MIN_SHARE) continue
            // 整格以墨为主：直接取墨色（眼睛、实心黑块、粗描边）
            if (share < INK_SOLID_SHARE) continue
            val rr = (sR[i] / ic).toInt().coerceIn(0, 255)
            val gg = (sG[i] / ic).toInt().coerceIn(0, 255)
            val bb = (sB[i] / ic).toInt().coerceIn(0, 255)
            argb[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            edgeMask[i] = true
        }

        // 第二轮：细线桥接。线只擦到格子一小角时面积平均是浅灰，量化后
        // 断成虚线。看四邻：上下或左右都已判成墨，就把这格补成墨色。
        // 迭代两遍，让 45° 斜线也能沿对角线接上。
        val ink = BooleanArray(n) { i -> inkShare[i] >= INK_SOLID_SHARE || edgeMask[i] }
        repeat(2) {
            for (y in 0 until gridH) {
                for (x in 0 until gridW) {
                    val i = y * gridW + x
                    if (ink[i]) continue
                    if (inkShare[i] < INK_MIN_SHARE) continue
                    if (paperCnt[i].toFloat() / (inkCnt[i] + paperCnt[i]).coerceAtLeast(1) < PAPER_MIN_SHARE) continue
                    val up = y > 0 && ink[i - gridW]
                    val down = y < gridH - 1 && ink[i + gridW]
                    val left = x > 0 && ink[i - 1]
                    val right = x < gridW - 1 && ink[i + 1]
                    if (!(up && down) && !(left && right)) continue
                    val ic = inkCnt[i]
                    val rr = (sR[i] / ic).toInt().coerceIn(0, 255)
                    val gg = (sG[i] / ic).toInt().coerceIn(0, 255)
                    val bb = (sB[i] / ic).toInt().coerceIn(0, 255)
                    argb[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                    edgeMask[i] = true
                    ink[i] = true
                }
            }
        }
    }

    /** 墨线优先：被视为"墨"的亮度上限 */
    private const val INK_DARK_L = 110.0

    /** 墨线优先：被视为"纸白"的亮度下限 */
    private const val PAPER_L = 205.0

    /** 墨线优先：墨占（墨+纸）的最低比例 */
    private const val INK_MIN_SHARE = 0.12f

    /** 墨线优先：整格取墨的墨占比下限 */
    private const val INK_SOLID_SHARE = 0.15f

    /** 墨线优先：纸白占比下限（挡住"深色块旁边只有一点点白"的假边界） */
    private const val PAPER_MIN_SHARE = 0.25f

    /**
     * 像素画采样：还原画像素 → 目标格按覆盖面积取"多数色"。
     *
     * 取多数色而不是平均色是关键：一格跨两个画像素时，平均会产生源图里
     * 根本不存在的过渡色（浅粉被白底一稀释就成了灰），色卡只能取到最近的
     * 脏色；多数表决保证每格输出的都是源图里真实存在的颜色。
     *
     * 复杂度 O(画像素数 + 目标格数 × 4)，78×72 网格在毫秒级。
     */
    private fun sampleArt(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        gridW: Int,
        gridH: Int,
        art: PixelArtDetector.Result,
    ): GridSample {
        val aw = art.artW
        val ah = art.artH
        // 真实块尺寸是小数（本例 801px / 66 格 = 12.11），检测器只能给出整数 12。
        // 按整数推进会在 66 列后累积 ~7px 漂移，右半边的取样窗口就压到隔壁
        // 画像素上——花瓣里那些 1 格跳色、花蕊消失全是这么来的。这里按实际
        // 跨度还原小数块尺寸，行/列各算一份。
        val bx = art.blockW
        val by = art.blockH
        val artR = IntArray(aw * ah)
        // 每个画像素取"内部区域众数"，不是均值。
        //
        // 为什么：源图是放大过的像素画，缩放会把块边界糊成 4~6px 的过渡带；
        // 块尺寸又常常不是整数（本例 801/68 = 11.78），中心取样会周期性落到
        // 过渡带上，均值把相邻两色混成源图不存在的灰粉，整圈花瓣随之发白、
        // 变薄。众数只会选中块内真正占多数的那个颜色，过渡带再宽也不会污染。
        // 只取块内缩 25% 的区域，边界过渡带直接不参与统计。
        for (ay in 0 until ah) {
            val y0 = art.oy + ay * by
            val y1 = y0 + by
            val sy0 = (y0 + (y1 - y0) * 0.25).toInt().coerceIn(0, srcH - 1)
            val sy1 = (y1 - (y1 - y0) * 0.25).toInt().coerceIn(sy0, srcH - 1)
            for (ax in 0 until aw) {
                val x0 = art.ox + ax * bx
                val x1 = x0 + bx
                val sx0 = (x0 + (x1 - x0) * 0.25).toInt().coerceIn(0, srcW - 1)
                val sx1 = (x1 - (x1 - x0) * 0.25).toInt().coerceIn(sx0, srcW - 1)
                // 桶 = 每通道丢低 3 位；同色块的 JPEG 抖动落在同一桶
                val keys = IntArray(MODE_BUCKETS)
                val bc = IntArray(MODE_BUCKETS)
                var used = 0
                for (yy in sy0..sy1) {
                    for (xx in sx0..sx1) {
                        val p = pixels[yy * srcW + xx]
                        val key = p and 0xF8F8F8
                        var slot = -1
                        for (s in 0 until used) if (keys[s] == key) { slot = s; break }
                        if (slot < 0) {
                            if (used == MODE_BUCKETS) break
                            slot = used++
                            keys[slot] = key
                        }
                        bc[slot]++
                    }
                }
                var best = 0
                for (s in 1 until used) if (bc[s] > bc[best]) best = s
                // 胜出桶内取均值：块内本来同色，均值只是把抖动收回到真实值
                val key = keys[best]
                var sr = 0
                var sg = 0
                var sb = 0
                var cnt = 0
                for (yy in sy0..sy1) {
                    for (xx in sx0..sx1) {
                        val p = pixels[yy * srcW + xx]
                        if ((p and 0xF8F8F8) != key) continue
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                        cnt++
                    }
                }
                if (cnt == 0) cnt = 1
                artR[ay * aw + ax] =
                    (0xFF shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
            }
        }

        val argb = IntArray(gridW * gridH)
        // 每格最多记录 MAX_VOTES 个不同颜色的累计面积；超出时并入最接近的桶，
        // 避免为一格分配 32768 长的直方图
        val MAX_VOTES = 8
        val keys = IntArray(MAX_VOTES)
        val wsum = DoubleArray(MAX_VOTES)
        val rsum = DoubleArray(MAX_VOTES)
        val gsum = DoubleArray(MAX_VOTES)
        val bsum = DoubleArray(MAX_VOTES)
        for (gy in 0 until gridH) {
            val y0 = gy * srcH.toDouble() / gridH
            val y1 = (gy + 1) * srcH.toDouble() / gridH
            for (gx in 0 until gridW) {
                val x0 = gx * srcW.toDouble() / gridW
                val x1 = (gx + 1) * srcW.toDouble() / gridW
                var used = 0
                java.util.Arrays.fill(rsum, 0.0)
                java.util.Arrays.fill(gsum, 0.0)
                java.util.Arrays.fill(bsum, 0.0)
                java.util.Arrays.fill(wsum, 0.0)
                val ay0 = (((y0 - art.oy) / by).toInt()).coerceIn(0, ah - 1)
                val ay1 = (((y1 - art.oy) / by - 1e-9).toInt()).coerceIn(0, ah - 1)
                val ax0 = (((x0 - art.ox) / bx).toInt()).coerceIn(0, aw - 1)
                val ax1 = (((x1 - art.ox) / bx - 1e-9).toInt()).coerceIn(0, aw - 1)
                for (ay in ay0..ay1) {
                    val oy = min(y1, art.oy + (ay + 1.0) * by) -
                        max(y0, art.oy + ay * by)
                    if (oy <= 0) continue
                    for (ax in ax0..ax1) {
                        val ox = min(x1, art.ox + (ax + 1.0) * bx) -
                            max(x0, art.ox + ax * bx)
                        if (ox <= 0) continue
                        val wgt = ox * oy
                        val p = artR[ay * aw + ax]
                        // 每通道丢低 3 位做桶键：同色画像素的 JPEG 抖动落在同一桶
                        val key = p and 0xF8F8F8
                        var slot = -1
                        for (s in 0 until used) if (keys[s] == key) { slot = s; break }
                        if (slot < 0) {
                            if (used < MAX_VOTES) {
                                slot = used++
                                keys[slot] = key
                            } else {
                                slot = 0 // 极端跨色情况并入第一桶，不新增分配
                            }
                        }
                        wsum[slot] += wgt
                        rsum[slot] += ((p shr 16) and 0xFF) * wgt
                        gsum[slot] += ((p shr 8) and 0xFF) * wgt
                        bsum[slot] += (p and 0xFF) * wgt
                    }
                }
                var best = 0
                for (s in 1 until used) if (wsum[s] > wsum[best]) best = s
                val w = if (wsum[best] > 0) wsum[best] else 1.0
                val rr = (rsum[best] / w + 0.5).toInt().coerceIn(0, 255)
                val gg = (gsum[best] / w + 0.5).toInt().coerceIn(0, 255)
                val bb = (bsum[best] / w + 0.5).toInt().coerceIn(0, 255)
                argb[gy * gridW + gx] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }

        val lab = FloatArray(argb.size * 3)
        for (i in argb.indices) {
            val l = ColorConversions.argbToLab(argb[i])
            lab[i * 3] = l[0]
            lab[i * 3 + 1] = l[1]
            lab[i * 3 + 2] = l[2]
        }
        return GridSample(argb, lab, ArtGrid(artR, aw, ah))
    }

    /**
     * 边沿保持的 3×3 平滑：只平均与中心色差 ≤ [EDGE_THRESHOLD] 的邻居。
     *
     * 为什么不用纯盒式：盒式会把 1~2 格宽的五官/首饰和背景平均掉，
     * 高过滤档位下眼睛直接糊成肤色。按色差加权（二值权重）后，
     * 平坦区域的纹理照常被抹平，跨界处保持原色，等价于一次快速双边滤波。
     * 复杂度 O(像素 × 9)，2× 中间图尺寸下可忽略。
     */
    private fun edgeAwareBlur3(r: DoubleArray, g: DoubleArray, b: DoubleArray, w: Int, h: Int) {
        val tr = r.copyOf()
        val tg = g.copyOf()
        val tb = b.copyOf()
        val outR = DoubleArray(r.size)
        val outG = DoubleArray(g.size)
        val outB = DoubleArray(b.size)
        for (y in 0 until h) {
            val y0 = max(0, y - 1)
            val y1 = min(h - 1, y + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - 1)
                val x1 = min(w - 1, x + 1)
                val i = y * w + x
                val cr = tr[i]
                val cg = tg[i]
                val cb = tb[i]
                var sr = 0.0
                var sg = 0.0
                var sb = 0.0
                var count = 0
                for (yy in y0..y1) {
                    for (xx in x0..x1) {
                        val j = yy * w + xx
                        val dr = tr[j] - cr
                        val dg = tg[j] - cg
                        val db = tb[j] - cb
                        // 只统计同色域邻居：跨边界的邻居不参与平均
                        if (dr * dr + dg * dg + db * db <= EDGE_THRESHOLD * EDGE_THRESHOLD) {
                            sr += tr[j]
                            sg += tg[j]
                            sb += tb[j]
                            count++
                        }
                    }
                }
                outR[i] = sr / count
                outG[i] = sg / count
                outB[i] = sb / count
            }
        }
        outR.copyInto(r)
        outG.copyInto(g)
        outB.copyInto(b)
    }

    private const val EDGE_THRESHOLD = 36.0

    /**
     * 细节保护掩码：与 5×5 邻域均值的色差 ≥ [threshold] 的格子判为真细节
     * （眼睛、嘴、高光、首饰）。这些格子在众数滤波和小区域合并中锁定，
     * 避免"高过滤"把五官一起抹掉。
     *
     * 用同一张网格的局部均值做参照，而不是源图固定阈值：网格越密，
     * 邻域窗口自动变小，细节判定跟着分辨率走，不需要按尺寸调参。
     */
    fun detailMask(
        sample: GridSample,
        width: Int,
        height: Int,
        radius: Int = 2,
        threshold: Double = 20.0,
    ): BooleanArray {
        val n = width * height
        val mask = BooleanArray(n)
        if (n == 0) return mask
        val lab = sample.lab
        val dist = FloatArray(n)
        // 积分图：每通道一张 (w+1)×(h+1)，窗口和 O(1)
        val iw = width + 1
        val size = iw * (height + 1)
        val sumL = DoubleArray(size)
        val sumA = DoubleArray(size)
        val sumB = DoubleArray(size)
        for (y in 0 until height) {
            var rowL = 0.0
            var rowA = 0.0
            var rowB = 0.0
            for (x in 0 until width) {
                val i = y * width + x
                rowL += lab[i * 3]
                rowA += lab[i * 3 + 1]
                rowB += lab[i * 3 + 2]
                val o = (y + 1) * iw + (x + 1)
                sumL[o] = sumL[y * iw + (x + 1)] + rowL
                sumA[o] = sumA[y * iw + (x + 1)] + rowA
                sumB[o] = sumB[y * iw + (x + 1)] + rowB
            }
        }
        for (y in 0 until height) {
            val y0 = max(0, y - radius)
            val y1 = min(height - 1, y + radius)
            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = min(width - 1, x + radius)
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val mL = windowSum(sumL, iw, x0, y0, x1, y1) / area
                val mA = windowSum(sumA, iw, x0, y0, x1, y1) / area
                val mB = windowSum(sumB, iw, x0, y0, x1, y1) / area
                val i = y * width + x
                val dL = lab[i * 3] - mL
                val dA = lab[i * 3 + 1] - mA
                val dB = lab[i * 3 + 2] - mB
                dist[i] = sqrt(dL * dL + dA * dA + dB * dB).toFloat()
            }
        }
        // 空间支撑过滤：孤立 1 格的高频噪声不该算"细节"——它和邻域均值的
        // 色差同样很大，但放大后会变成满图胡椒面。只有与至少 [MIN_SUPPORT]
        // 个同样超阈值的邻居连成片，才锁定为细节；极强反差（≥ 2×阈值）
        // 例外保留，单格的高光/瞳孔不会因此丢失。
        val t = threshold.toFloat()
        val strong = (threshold * 2.0).toFloat()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val d = dist[i]
                if (d < t) continue
                if (d >= strong) {
                    mask[i] = true
                    continue
                }
                var support = 0
                val y0 = max(0, y - 1)
                val y1 = min(height - 1, y + 1)
                val x0 = max(0, x - 1)
                val x1 = min(width - 1, x + 1)
                for (yy in y0..y1) {
                    for (xx in x0..x1) {
                        if (yy == y && xx == x) continue
                        if (dist[yy * width + xx] >= t) support++
                    }
                }
                mask[i] = support >= MIN_SUPPORT
            }
        }
        return mask
    }

    /** 细节格至少需要的超阈值邻居数（8 邻域） */
    private const val MIN_SUPPORT = 2

    private fun windowSum(sum: DoubleArray, iw: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val a = y0 * iw + x0
        val b = y0 * iw + (x1 + 1)
        val c = (y1 + 1) * iw + x0
        val d = (y1 + 1) * iw + (x1 + 1)
        return sum[d] - sum[b] - sum[c] + sum[a]
    }

    /**
     * 邻域众数滤波：3×3 窗口内某色号出现 ≥ [minCount] 次才替换当前格。
     * 阈值 5/9 的取舍：1~2 格宽的噪点一定被清掉；2×2 以上的真实色块
     * 自身票数不够也会被邻域压过，所以细节格由 [protectedMask] 单独锁定。
     *
     * 每轮读旧图写新图（快照语义），避免同轮内级联造成方向性拖影。
     */
    fun modeFilter(
        cells: IntArray,
        width: Int,
        height: Int,
        paletteSize: Int,
        passes: Int,
        protectedMask: BooleanArray? = null,
        minCount: Int = 5,
    ): IntArray {
        if (passes <= 0 || width < 3 || height < 3) return cells
        var src = cells
        var dst = IntArray(cells.size)
        val counts = IntArray(paletteSize)
        val touched = IntArray(paletteSize)
        repeat(passes) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val cur = src[idx]
                    if (protectedMask?.get(idx) == true || cur < 0) {
                        dst[idx] = cur
                        continue
                    }
                    var touchedCount = 0
                    var best = -1
                    var bestCount = 0
                    for (dy in -1..1) {
                        val yy = (y + dy).coerceIn(0, height - 1)
                        for (dx in -1..1) {
                            val xx = (x + dx).coerceIn(0, width - 1)
                            val c = src[yy * width + xx]
                            if (c < 0) continue
                            val nc = ++counts[c]
                            if (nc == 1) touched[touchedCount++] = c
                            // 平票时保留当前色，避免相邻两色来回翻
                            if (nc > bestCount || (nc == bestCount && c == cur)) {
                                bestCount = nc
                                best = c
                            }
                        }
                    }
                    for (i in 0 until touchedCount) counts[touched[i]] = 0
                    dst[idx] = if (best >= 0 && bestCount >= minCount) best else cur
                }
            }
            val swap = src
            src = dst
            dst = swap
        }
        return src
    }
}
