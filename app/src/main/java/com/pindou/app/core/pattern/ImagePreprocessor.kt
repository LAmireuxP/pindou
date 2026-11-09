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

/** 采样结果：每格 ARGB + 每格 Lab（三个 float 一组，下游免重复转换） */
class GridSample(val argb: IntArray, val lab: FloatArray, val art: ArtGrid? = null) {
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

        // 像素画原图：先还原到它自己的画像素网格，再按面积多数表决落到目标
        // 网格。面积平均会把画像素和背景混成中间色，量化后花瓣碎成胡椒面，
        // 所以这条支路完全绕开平均。
        PixelArtDetector.detect(pixels, srcW, srcH)?.let { art ->
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

        val lab = FloatArray(argb.size * 3)
        for (i in argb.indices) {
            val l = ColorConversions.argbToLab(argb[i])
            lab[i * 3] = l[0]
            lab[i * 3 + 1] = l[1]
            lab[i * 3 + 2] = l[2]
        }
        return GridSample(argb, lab)
    }

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
        val artR = IntArray(aw * ah)
        // 每个画像素取"内部区域众数"，不是均值。
        //
        // 为什么：源图是放大过的像素画，缩放会把块边界糊成 4~6px 的过渡带；
        // 块尺寸又常常不是整数（本例 801/68 = 11.78），中心取样会周期性落到
        // 过渡带上，均值把相邻两色混成源图不存在的灰粉，整圈花瓣随之发白、
        // 变薄。众数只会选中块内真正占多数的那个颜色，过渡带再宽也不会污染。
        // 只取块内缩 25% 的区域，边界过渡带直接不参与统计。
        for (ay in 0 until ah) {
            val y0 = (art.oy + ay * art.block).toDouble()
            val y1 = y0 + art.block
            val sy0 = (y0 + (y1 - y0) * 0.25).toInt().coerceIn(0, srcH - 1)
            val sy1 = (y1 - (y1 - y0) * 0.25).toInt().coerceIn(sy0, srcH - 1)
            for (ax in 0 until aw) {
                val x0 = (art.ox + ax * art.block).toDouble()
                val x1 = x0 + art.block
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
                val ay0 = (((y0 - art.oy) / art.block).toInt()).coerceIn(0, ah - 1)
                val ay1 = (((y1 - art.oy) / art.block - 1e-9).toInt()).coerceIn(0, ah - 1)
                val ax0 = (((x0 - art.ox) / art.block).toInt()).coerceIn(0, aw - 1)
                val ax1 = (((x1 - art.ox) / art.block - 1e-9).toInt()).coerceIn(0, aw - 1)
                for (ay in ay0..ay1) {
                    val oy = min(y1, art.oy + (ay + 1.0) * art.block) -
                        max(y0, art.oy + ay.toDouble() * art.block)
                    if (oy <= 0) continue
                    for (ax in ax0..ax1) {
                        val ox = min(x1, art.ox + (ax + 1.0) * art.block) -
                            max(x0, art.ox + ax.toDouble() * art.block)
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
