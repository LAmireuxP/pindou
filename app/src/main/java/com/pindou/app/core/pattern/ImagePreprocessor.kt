package com.pindou.app.core.pattern

import com.pindou.app.core.color.ColorConversions
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 过滤等级：四档（无/低/中/高）。
 *
 * 只影响格子层面的降噪强度，不改变采样分辨率：
 * - [clusterK]        Lab k-means 目标簇数（先聚类成大片再映射色卡）
 * - [modePasses]      邻域众数滤波轮数（消除单格跳色）
 * - [smoothPasses]    2× 中间图上 3×3 盒式平滑轮数（吃掉照片纹理高频）
 * - [regionFraction]  小连通块合并阈值（占格数比例）
 * - [regionKeepDeltaE] 合并时允许并入的色差上限（越大合得越狠）
 * - [detailThreshold] 细节掩码阈值（Lab ΔE），越低保护范围越大
 *
 * 先在 2× 中间图上平滑、再 2×2 合到目标网格：盒式平均本身就是
 * 面积加权重采样，比"块内取众数"稳定——众数桶会在相邻块之间来回翻转，
 * 产生细碎噪点。
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
    // 小区域阶段的任务就是把跨色阶的碎点也并掉。
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
    /** 本次采样是否走了"逐画像素原色"档（像素画模式且网格与画网格 1:1） */
    val exact: Boolean = false,
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

    /** 子块与块均值的每通道容差：小于它算纯色块，四个子块统一用块色 */
    private const val FLAT_BLOCK_TOL = 10

    fun sample(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        gridW: Int,
        gridH: Int,
        level: FilterLevel,
        exactArt: Boolean = false,
        /** 用户在校准页确认过的画像素网格（列 × 行）；非空时跳过自动检测 */
        pixelGridHint: Pair<Int, Int>? = null,
    ): GridSample {
        require(gridW in 1..500 && gridH in 1..500) { "网格尺寸越界: ${gridW}x$gridH" }
        require(srcW > 0 && srcH > 0) { "源图尺寸非法: ${srcW}x$srcH" }
        require(pixels.size >= srcW * srcH) { "像素数 ${pixels.size} < ${srcW}x$srcH" }

        // 像素画支路：源图本身是放大过的像素画时，先还原画像素网格再落格。
        // 命中后颜色只取源图里真实存在的那些，不会被白底稀释成灰粉。
        // exactArt：「像素画生成」入口显式选择像素支路时，检测放宽、
        // 取色改为最近画像素（不做块内平均），保留原画的离散色号。
        val detected = when {
            exactArt && pixelGridHint != null -> {
                val (cols, rows) = pixelGridHint
                if (cols in 1..500 && rows in 1..500) {
                    PixelArtDetector.Result(
                        blockW = srcW.toDouble() / cols,
                        blockH = srcH.toDouble() / rows,
                        artW = cols,
                        artH = rows,
                        ox = 0.0,
                        oy = 0.0,
                    )
                } else {
                    PixelArtDetector.detectForced(pixels, srcW, srcH)
                }
            }
            // 显式像素画入口用强制档：二次压缩、缩放模糊的像素画在严格档
            // 自相关会掉到阈值以下，宽松档仍可能漏；强制档还有平坦段兜底。
            exactArt -> PixelArtDetector.detectForced(pixels, srcW, srcH)
            else -> PixelArtDetector.detect(pixels, srcW, srcH)
        }
        // 目标格比画格粗 25% 以上时改走通用支路：像素画缩小后，1px 描边
        // 在任何取样法下都保不住，而通用支路的线检测 + 边界保护更连续。
        // 1:1 与放大不触发，像素画的"逐画像素"语义不受影响。
        val tooCoarse = detected != null &&
            (gridW < detected.artW * 0.8 || gridH < detected.artH * 0.8)
        val art = if (detected != null && !tooCoarse) detected else null
        if (art != null) {
            return sampleArt(pixels, srcW, srcH, gridW, gridH, art, exactArt)
        }
        // 像素画入口但检测没命中（二次压缩、重度缩放、带模糊的像素图）：
        // 说明这张图不是严格的像素画，直接落到通用支路，由线检测与边界
        // 保护处理。强行走"逐格众数"会把平滑线条打成一串孤点。
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

        // ①a2 主色覆盖：一格里的像素高度集中在同一个粗分桶（16 级/通道）时，
        //      直接用该桶的均值，而不是面积平均。照片几乎不会触发（纹理把像素
        //      摊平到几十个桶）；插画、扁平配色、带描边的角色图则大面积命中。
        //      这一步直接消灭"浅蓝翅膀被白底稀释成灰蓝""浅粉腮红变灰"的成因——
        //      这些区域在源图里本来就是纯色，面积平均才是污染源。
        //      阈值 0.55：主色占比过半才代表"这一格属于这个色块"，
        //      否则说明格子确实跨了边界。
        dominantColorPerCell(pixels, srcW, srcH, gridW, gridH, argb)

        // ①a3 线稿基线快照：后面的锐化与墨线优先都是"加点深色"，线稿模式下
        //      需要把这些额外落墨撤回去，否则描边会 2 格宽、转角糊成块。
        val crispBase = argb.copyOf()

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

        // ①d 描边骨架：线稿的描边按覆盖率落格必然断续（同一笔画在相邻格里
        //     可能 70% 也可能 30%）。改成先细化成 1 像素中心线再落格，一格
        //     有线穿过就整格判墨——描边必然连续、必然一格宽，且不会因为
        //     转角处两格同时变黑而糊成块。照片不触发（lineArt=false）。
        val stroke = StrokeSkeleton.analyze(pixels, srcW, srcH, gridW, gridH)
        if (stroke.lineArt) applyStroke(argb, edgeMask, stroke, gridW, gridH, crispBase)

        // ①e 纸白底色解混：线稿的浅色小块（腮红、翅膀、小爱心）在很多格里
        //     只占三成面积，其余是白纸。面积平均出来是"粉+白的均值"，量化后
        //     变成发灰的浅粉——这就是"颜色不如原画"的主因。线稿模式下把
        //     纸白像素排除后重新取色，浅粉就还是浅粉。
        if (stroke.lineArt) {
            unblendOnPaper(pixels, srcW, srcH, gridW, gridH, argb, edgeMask, stroke)
        }

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
     * 把骨架结果落到网格上。
     *
     * 三条规则，顺序不能换：
     * 1. 中心线穿过的格子 → 墨（描边主路径）；
     * 2. 没有中心线但墨覆盖 ≥ [SOLID_COVERAGE] 的格子 → 墨（实心黑块、眼珠，
     *    细化会把这些区域缩成一个点，必须靠覆盖率保回来）；
     * 3. 对角桥接：两个斜向墨格之间的空格补墨，避免细化后出现"只在对角相接"
     *    的断点——拼豆是 4 邻接的，斜接在纸面上看就是断的。
     */
    private fun applyStroke(
        argb: IntArray,
        edgeMask: BooleanArray,
        stroke: StrokeSkeleton.Result,
        w: Int,
        h: Int,
        base: IntArray,
    ) {
        val n = w * h
        val ink = BooleanArray(n)
        // 先把整格恢复到主色基线，再统一按"覆盖率 + 骨架"落墨。
        // 覆盖率门槛 0.55：描边的主判据是骨架命中，覆盖率只兜底实心块。
        // 旧值 0.10 会让 45° 斜线整条落墨——每格都被擦到 30%，于是线胖成两格。
        for (i in 0 until n) {
            val hit = stroke.hits[i] >= 1 || stroke.coverage[i] >= LINE_INK_MIN
            val solid = stroke.coverage[i] >= SOLID_COVERAGE
            if (!hit && !solid) {
                argb[i] = base[i]
                edgeMask[i] = false
                continue
            }
            if (stroke.inkArgb[i] == 0) continue
            argb[i] = stroke.inkArgb[i]
            edgeMask[i] = true
            ink[i] = true
        }
        // 格子级清理：落墨只认"中心线穿过的格子"，描边天然是一格宽，
        // 不会再出现"有的地方两格宽、有的地方只剩一格"的胖瘦跳变。
        //
        // 复杂度 O(格数)。
        val locked = BooleanArray(n) { stroke.coverage[it] >= SOLID_COVERAGE }
        pruneGridSpurs(ink, locked, stroke, w, h)
        // 不做"上下/左右都是墨就补一格"的补洞：斜向笔画每级台阶都会被补，
        // 描边直接胖一倍。断线交给骨架命中 + 下面的对角桥接。
        // 清理改过 ink 后，argb/edgeMask 必须跟着回写，否则被削掉的格子会留墨色。
        // 只回写 applyStroke 自己认领过的格子，别把 sharpenEdgeCells 的成果冲掉。
        //
        // 回写颜色不能直接用 base：那一格的 base 是"白纸 + 黑线"的均值，是灰的，
        // 落到纸上就是描边旁边一圈脏灰。改成取周围非墨格里出现最多的颜色。
        val fill = IntArray(n)
        for (i in 0 until n) {
            if (ink[i]) continue
            if (stroke.hits[i] < 1 && stroke.coverage[i] < LINE_INK_MIN) continue
            fill[i] = neighborColor(ink, argb, w, h, i)
        }
        for (i in 0 until n) {
            if (ink[i]) {
                argb[i] = stroke.inkArgb[i]
                edgeMask[i] = true
                continue
            }
            if (stroke.hits[i] >= 1 || stroke.coverage[i] >= LINE_INK_MIN) {
                argb[i] = if (fill[i] != 0) fill[i] else base[i]
                edgeMask[i] = false
            }
        }

        // 对角转角补一格：骨架是 8 邻接的，45° 走一步会从 (x,y) 直接跳到
        // (x+1,y+1)，中间两个转角格都没墨，纸面上看就是断线。对每一对
        // 斜邻墨格，只在两个转角格里挑覆盖率大的那个补上——正好还原
        // 1px 线的 Bresenham 台阶，线连续、仍然一格宽。
        // 不能两个都补：那会把描边整条加粗一倍。
        repeat(2) {
            val add = ArrayList<Int>(64)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!ink[i]) continue
                    for (d in 0..3) {
                        val dx = if (d == 0 || d == 2) 1 else -1
                        val dy = if (d < 2) 1 else -1
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        if (!ink[ny * w + nx]) continue
                        val c1x = nx
                        val c1y = y
                        val c2x = x
                        val c2y = ny
                        val c1 = c1y * w + c1x
                        val c2 = c2y * w + c2x
                        if (ink[c1] || ink[c2]) continue
                        val pick = if (stroke.coverage[c1] >= stroke.coverage[c2]) c1 else c2
                        // 转角格本身只被线的边角擦到，覆盖率很低，不能拿
                        // 覆盖率当门槛；只要它确实沾到过墨色就补。
                        if (stroke.inkArgb[pick] == 0) continue
                        add.add(pick)
                    }
                }
            }
            if (add.isEmpty()) return@repeat
            for (i in add) {
                argb[i] = stroke.inkArgb[i]
                edgeMask[i] = true
                ink[i] = true
            }
        }
    }

    /** 实心深色区域：墨覆盖率达到它就直接保留整格（细化会把眼珠缩成一点） */
    private const val SOLID_COVERAGE = 0.80f

    /** 孤点剪枝：覆盖率低于它的单邻格墨点视为噪点（抗锯齿、毛发飞边） */
    private const val SPUR_COVERAGE = 0.34f

    /**
     * 去掉只有 1 个 8 邻格邻接的墨点。
     *
     * 落格后常见的是"线旁边多出一颗豆"，纸面上看是脏点。真实笔画端点覆盖率
     * 通常 ≥ [SPUR_COVERAGE]（线头会占掉半格），所以用覆盖率做区分，不误删线头。
     */
    private fun pruneGridSpurs(
        ink: BooleanArray,
        locked: BooleanArray,
        stroke: StrokeSkeleton.Result,
        w: Int,
        h: Int,
    ) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!ink[i] || locked[i]) continue
                if (stroke.coverage[i] >= SPUR_COVERAGE) continue
                var deg = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    if (ink[ny * w + nx]) deg++
                }
                // 只删"四面不挨"的孤点。degree=1 是笔画端点，删了就成断头线。
                if (deg == 0) ink[i] = false
            }
        }
    }

    /** 解混：纸白判定亮度下限（0..255） */
    private const val UNBLEND_PAPER_L = 238.0

    /** 解混：纸白判定彩度上限（R/G/B 极差），超过它的"白"其实是有色浅底 */
    private const val PAPER_CHROMA = 12

    /** 解混：每格保留的候选色桶数（4 bit/通道），超出说明格子纹理太杂 */
    private const val UNBLEND_BUCKETS = 6

    /** 解混：彩色像素最少占比，低于它这格仍是纸白，别把噪点放大成整格 */
    private const val UNBLEND_MIN_SHARE = 0.18f

    /** 解混：彩色像素里深色（墨）占比上限，超过说明这格是断线残影不是色块 */
    private const val UNBLEND_MAX_DARK = 0.60f

    /**
     * 纸白底色解混：把一格里"白纸"像素剔除后重新取色。
     *
     * 权重用 (1-L)²，越接近纸白权重越低，等价于向色块核心靠拢；
     * 这样浅粉腮红不会被抗锯齿过渡带拉成灰粉。
     *
     * 复杂度 O(源像素)，与 [dominantColorPerCell] 同量级。
     */
    private fun unblendOnPaper(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        gridW: Int,
        gridH: Int,
        argb: IntArray,
        edgeMask: BooleanArray,
        stroke: StrokeSkeleton.Result,
    ) {
        val n = gridW * gridH
        val paperCnt = IntArray(n)
        val darkCnt = IntArray(n)
        val total = IntArray(n)
        // 每格最多记 6 个候选色桶（4 bit/通道），桶内累加真实值求均值。
        // 用"众数桶"而不是整体加权平均：同一块纯色区域里每格的平均值会差 ±2，
        // 量化后会跳成两三个相邻色号，纸面上一片格子深浅不一。
        val bucket = IntArray(n * UNBLEND_BUCKETS)
        val bCnt = IntArray(n * UNBLEND_BUCKETS)
        val bR = LongArray(n * UNBLEND_BUCKETS)
        val bG = LongArray(n * UNBLEND_BUCKETS)
        val bB = LongArray(n * UNBLEND_BUCKETS)
        for (y in 0 until srcH) {
            val gy = (y.toLong() * gridH / srcH).toInt()
            val rowBase = gy * gridW
            val row = y * srcW
            for (x in 0 until srcW) {
                val k = rowBase + (x.toLong() * gridW / srcW).toInt()
                total[k]++
                val p = pixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = 0.299 * r + 0.587 * g + 0.114 * b
                val mx = maxOf(r, maxOf(g, b))
                val mn = minOf(r, minOf(g, b))
                if (l >= UNBLEND_PAPER_L && mx - mn <= PAPER_CHROMA) {
                    paperCnt[k]++
                    continue
                }
                if (l < 110.0) darkCnt[k]++
                val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                val base = k * UNBLEND_BUCKETS
                var j = 0
                while (j < UNBLEND_BUCKETS && bCnt[base + j] != 0 && bucket[base + j] != key) j++
                if (j == UNBLEND_BUCKETS) continue // 格子太杂（纹理/渐变），交给常规采样
                bucket[base + j] = key
                bCnt[base + j]++
                bR[base + j] += r
                bG[base + j] += g
                bB[base + j] += b
            }
        }
        for (i in 0 until n) {
            val t = total[i]
            if (t == 0) continue
            // 描边格（骨架判定为墨）不动：颜色已由 applyStroke 决定
            if (stroke.hits[i] >= 1 || stroke.coverage[i] >= LINE_INK_MIN) continue
            val colorCnt = t - paperCnt[i]
            if (colorCnt.toFloat() / t < UNBLEND_MIN_SHARE) continue
            if (darkCnt[i].toFloat() / colorCnt > UNBLEND_MAX_DARK) continue
            // 取像素最多的那个色桶
            val base = i * UNBLEND_BUCKETS
            var best = -1
            var bestN = 0
            for (j in 0 until UNBLEND_BUCKETS) {
                if (bCnt[base + j] > bestN) {
                    bestN = bCnt[base + j]
                    best = j
                }
            }
            if (best < 0) continue
            val r = (bR[base + best].toDouble() / bestN).roundToInt().coerceIn(0, 255)
            val g = (bG[base + best].toDouble() / bestN).roundToInt().coerceIn(0, 255)
            val b = (bB[base + best].toDouble() / bestN).roundToInt().coerceIn(0, 255)
            // 近中性、又很亮的"淡灰"多半是纸面阴影、水印、压缩噪点：
            // 解混会把它们放大成一片浅灰块，量化后变成脏底色，直接跳过。
            // 同理，近中性又偏暗的是白纸与描边的抗锯齿混合，落格后是脏灰。
            val mx = maxOf(r, maxOf(g, b))
            val mn = minOf(r, minOf(g, b))
            val l = 0.299 * r + 0.587 * g + 0.114 * b
            if (mx - mn < 12 && l > 200.0) continue
            if (mx - mn < 18 && l < 205.0) continue
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            edgeMask[i] = true
        }
    }

    /**
     * 取 8 邻格里出现最多的"非墨"颜色，用来填被削掉的墨格。
     * 找不到（整片都是墨）返回 0，调用方回退到 base。
     */
    private fun neighborColor(
        ink: BooleanArray,
        argb: IntArray,
        w: Int,
        h: Int,
        i: Int,
    ): Int {
        val x = i % w
        val y = i / w
        val cand = IntArray(8)
        val cnt = IntArray(8)
        var m = 0
        for (dy in -1..1) {
            val ny = y + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                if (nx < 0 || nx >= w) continue
                val j = ny * w + nx
                if (ink[j]) continue
                val c = argb[j]
                if (c == 0) continue
                var k = 0
                while (k < m && cand[k] != c) k++
                if (k == m && m < 8) {
                    cand[m] = c
                    cnt[m] = 0
                    m++
                }
                if (k < m) cnt[k]++
            }
        }
        var best = 0
        var bestN = 0
        for (k in 0 until m) {
            if (cnt[k] > bestN) {
                bestN = cnt[k]
                best = cand[k]
            }
        }
        return best
    }

    /**
     * 线稿落墨：这一格被描边穿过的最低墨占比。
     *
     * 取 0.95 等于"只认骨架命中"——描边宽度换算到格子上普遍不足一格，
     * 按面积比例落墨会把一条 45° 线落成两格宽的锯齿带（覆盖率 0.3 的门槛
     * 就能触发），104 宽下尤其明显。骨架命中保证一格宽；实心黑块由
     * [SOLID_COVERAGE] 单独兜底。
     */
    private const val LINE_INK_MIN = 0.55f

    /** 对角桥接的最低墨覆盖率：低于它说明只是擦到边角，补上去反而变脏 */
    private const val BRIDGE_COVERAGE = 0.10f

    /**
     * 每格主色统计。
     *
     * 单遍扫描源像素，每格维护 3 个粗色桶（4 bit/通道）的计数与 RGB 累加。
     * 主桶占比 ≥ [DOMINANT_SHARE] 时把该桶均值写回 [argb]，否则保持面积平均。
     *
     * 单格最多覆盖几千像素，3 个桶足以覆盖"底色 + 描边 + 抗锯齿过渡"
     * 三种成分；再多只是给噪点留位置。
     * 复杂度 O(源像素 × 3 次比较)，14MP 图约 20ms，可忽略。
     */
    private fun dominantColorPerCell(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        gridW: Int,
        gridH: Int,
        argb: IntArray,
    ) {
        val n = gridW * gridH
        val SLOTS = 3
        val keys = IntArray(n * SLOTS) { -1 }
        val cnt = IntArray(n * SLOTS)
        val sr = IntArray(n * SLOTS)
        val sg = IntArray(n * SLOTS)
        val sb = IntArray(n * SLOTS)
        for (y in 0 until srcH) {
            val rowBase = (y.toLong() * gridH / srcH).toInt() * gridW
            val row = y * srcW
            for (x in 0 until srcW) {
                val p = pixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // 16 级分桶：±8 的色差在拼豆粒度上不可见，但足以把同一片
                // 平涂色的抗锯齿边缘分出去
                val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                val base = (rowBase + (x.toLong() * gridW / srcW).toInt()) * SLOTS
                var slot = -1
                for (t in 0 until SLOTS) {
                    if (keys[base + t] == key) { slot = t; break }
                }
                if (slot < 0) {
                    // 没有同桶：占掉计数最小的槽位（第三个桶天然是"过渡色"）
                    var minT = 0
                    for (t in 1 until SLOTS) if (cnt[base + t] < cnt[base + minT]) minT = t
                    slot = minT
                    keys[base + slot] = key
                    cnt[base + slot] = 0
                    sr[base + slot] = 0
                    sg[base + slot] = 0
                    sb[base + slot] = 0
                }
                cnt[base + slot]++
                sr[base + slot] += r
                sg[base + slot] += g
                sb[base + slot] += b
            }
        }
        for (i in 0 until n) {
            val base = i * SLOTS
            var best = 0
            var total = 0
            for (t in 0 until SLOTS) {
                total += cnt[base + t]
                if (cnt[base + t] > cnt[base + best]) best = t
            }
            if (total == 0) continue
            val bc = cnt[base + best]
            if (bc.toDouble() / total < DOMINANT_SHARE) continue
            val rr = (sr[base + best] + bc / 2) / bc
            val gg = (sg[base + best] + bc / 2) / bc
            val bb = (sb[base + best] + bc / 2) / bc
            argb[i] = (0xFF shl 24) or (rr.coerceIn(0, 255) shl 16) or
                (gg.coerceIn(0, 255) shl 8) or bb.coerceIn(0, 255)
        }
    }

    /** 主色覆写阈值：单桶占比过半才认为这一格是纯色块 */
    private const val DOMINANT_SHARE = 0.55

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

    private fun sampleArt(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        gridW: Int,
        gridH: Int,
        art: PixelArtDetector.Result,
        exact: Boolean = false,
    ): GridSample {
        // 目标网格比画像素细时，一格只覆盖半个画像素。直接把块色铺上去
        // 等于把小图放大：花瓣里的明暗、眼睛里的高光都会被抹平。这里按
        // 目标网格把画像素细分——sub=2 时每个块拆成 2×2 子块分别取色；
        // 真正平整的块在下一段合并回块色，只有块内确实有明暗结构时才保留。
        //
        // 复杂度 O(子块数 × 子块内像素)。
        // exact 只在目标网格 ≈ 画网格时生效：1:1 才是"逐画像素还原"。
        // 目标网格明显大于画网格时，非细分采样没有新信息，只会把每个
        // 画像素复制成同色块；此时仍然细分，软像素画块内的渐变还能还原。
        val useExact = exact && kotlin.math.abs(gridW - art.artW) <= 2 &&
            kotlin.math.abs(gridH - art.artH) <= 2
        // 目标格明显比画格粗的档（110 格画 -> 60 格豆）不会走到这里：
        // sample() 已经按 1.25 倍判据改跳通用支路。
        val sub = if (useExact) 1 else if (gridW > art.artW + 2 || gridH > art.artH + 2) 2 else 1
        val aw = art.artW * sub
        val ah = art.artH * sub
        val bx = art.blockW / sub
        val by = art.blockH / sub
        val artR = IntArray(aw * ah)
        for (ay in 0 until ah) {
            val y0 = art.oy + ay * by
            val y1 = y0 + by
            for (ax in 0 until aw) {
                val x0 = art.ox + ax * bx
                val x1 = x0 + bx
                // 分档采样：中心 3×3 → 边缘环 25 点 → 整格密集。
                // 纯色块 9 个点就结束；跨色格取占多数的一侧，不做整格平均。
                // exact 档返回桶中心，保证同一画像素在所有格子里是同一个值。
                artR[ay * aw + ax] = PixelCellSampler.sample(
                    pixels, srcW, srcH, x0, y0, x1, y1, bucketCenter = useExact,
                ).argb
            }
        }
        // 平坦块回收。真像素画的块内是纯色，四个子块必然同色；软像素画
        // （平滑渐变的像素风插画、带发光的图）块内有渐变，子块会明显分叉。
        // 判据：四个子块里至少三个与块均值接近就算纯色块，整块统一用均值——
        // 角落子块会被相邻块的过渡带污染，所以不要求四个都一致。
        if (sub > 1) {
            for (byi in 0 until art.artH) {
                for (bxi in 0 until art.artW) {
                    var sr = 0; var sg = 0; var sb = 0
                    for (dy in 0 until 2) for (dx in 0 until 2) {
                        val p = artR[(byi * 2 + dy) * aw + bxi * 2 + dx]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                    }
                    val mr = sr / 4; val mg = sg / 4; val mb = sb / 4
                    var near = 0
                    for (dy in 0 until 2) for (dx in 0 until 2) {
                        val p = artR[(byi * 2 + dy) * aw + bxi * 2 + dx]
                        val d = maxOf(
                            kotlin.math.abs(((p shr 16) and 0xFF) - mr),
                            kotlin.math.abs(((p shr 8) and 0xFF) - mg),
                            kotlin.math.abs((p and 0xFF) - mb),
                        )
                        if (d <= FLAT_BLOCK_TOL) near++
                    }
                    if (near >= 3) {
                        val c = (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb
                        for (dy in 0 until 2) for (dx in 0 until 2) {
                            artR[(byi * 2 + dy) * aw + bxi * 2 + dx] = c
                        }
                    }
                }
            }
        }

        val argb = IntArray(gridW * gridH)
        if (useExact) {
            // 最近画像素：一格取格子中心落在哪个画像素，直接用它的颜色。
            // 1:1 网格（104×104 目标 / 104×104 画网格）时结果与原图逐格相同，
            // 这正是「像素画生成」区别于「图片生成」的地方——照片支路在这一步
            // 做面积平均，边缘必然出现中间色。
            for (gy in 0 until gridH) {
                val cy = (gy + 0.5) * srcH / gridH
                val ay = (((cy - art.oy) / by).toInt()).coerceIn(0, ah - 1)
                for (gx in 0 until gridW) {
                    val cx = (gx + 0.5) * srcW / gridW
                    val ax = (((cx - art.ox) / bx).toInt()).coerceIn(0, aw - 1)
                    argb[gy * gridW + gx] = artR[ay * aw + ax]
                }
            }
            val labExact = FloatArray(argb.size * 3)
            for (i in argb.indices) {
                val l = ColorConversions.argbToLab(argb[i])
                labExact[i * 3] = l[0]
                labExact[i * 3 + 1] = l[1]
                labExact[i * 3 + 2] = l[2]
            }
            return GridSample(argb, labExact, ArtGrid(artR, aw, ah), exact = true)
        }
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
     * 纯盒式会把 1~2 格宽的五官/首饰和背景平均掉，高过滤档位下眼睛
     * 会糊成肤色。按色差加权（二值权重）后，平坦区域的纹理照常被抹平，
     * 跨界处保持原色，等价于一次快速双边滤波。
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
