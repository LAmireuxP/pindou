package com.pindou.app.core.pattern

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 画像素取色：分档采样，不整格平均。
 *
 * 档位与 iBeads 3.9 的 cell sampling profile 对齐：
 * `center_3x3` → `edge_ring_lattice25` → `dense_full`。
 * 纯色块第一档 9 个点就结束；格内出现两种以上颜色时升到 25 点；
 * 仍不稳定才扫整格。取"占多数的色桶"而不是 RGB 均值，跨色边界的格子
 * 只会落到其中一侧，不会生成源图里不存在的中间色。
 *
 * 复杂度：纯色块 9 次读，最坏 O(格内像素)，上限 [DENSE_SAMPLES] 次。
 */
object PixelCellSampler {

    /** 分档采样结果；[confidence] 是主色桶占样本的比例（1 = 纯色）。 */
    class Sample(val argb: Int, val confidence: Float)

    /** 中心 3×3 的采样位置（格内归一化坐标） */
    private val CENTER_9 = floatArrayOf(0.30f, 0.50f, 0.70f)

    /** 边缘环 5×5：内缩 18%，避开格子边界的抗锯齿过渡带 */
    private val RING_25 = floatArrayOf(0.18f, 0.34f, 0.50f, 0.66f, 0.82f)

    /** 密集档最多采样点数：再高对取色没有增益，只会拖慢 200×200 网格 */
    private const val DENSE_SAMPLES = 576

    /** 第一档直接通过的主色占比：9 点里至少 7 点同桶 */
    private const val FAST_SHARE = 7.0f / 9.0f

    /** 第二档直接通过的主色占比 */
    private const val RING_SHARE = 0.68f

    fun sample(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        /** 逐画像素档：返回桶中心，保证同一色块在所有格子里是同一个值 */
        bucketCenter: Boolean,
    ): Sample {
        val sx0 = floor(x0).toInt().coerceIn(0, srcW - 1)
        val sy0 = floor(y0).toInt().coerceIn(0, srcH - 1)
        val sx1 = (ceil(x1).toInt() - 1).coerceIn(sx0, srcW - 1)
        val sy1 = (ceil(y1).toInt() - 1).coerceIn(sy0, srcH - 1)
        val w = sx1 - sx0 + 1
        val h = sy1 - sy0 + 1

        // 不足 3×3 的格子没有"内部"可采，直接取中心像素。
        if (w <= 2 || h <= 2) {
            val p = pixels[((sy0 + sy1) / 2) * srcW + (sx0 + sx1) / 2]
            return Sample(0xFF000000.toInt() or (p and 0xFFFFFF), 1f)
        }

        val fast = IntArray(9)
        var n = 0
        for (fy in CENTER_9) {
            val yy = sy0 + (fy * (h - 1)).roundToInt()
            for (fx in CENTER_9) {
                val xx = sx0 + (fx * (w - 1)).roundToInt()
                fast[n++] = pixels[yy * srcW + xx]
            }
        }
        val fastBucket = dominantBucket(fast, n)
        val fastCount = bucketCount(fast, n, fastBucket)
        if (fastCount.toFloat() / n >= FAST_SHARE) {
            return Sample(bucketColor(fastBucket, fast, n, bucketCenter), fastCount.toFloat() / n)
        }

        val ring = IntArray(25)
        n = 0
        for (fy in RING_25) {
            val yy = sy0 + (fy * (h - 1)).roundToInt()
            for (fx in RING_25) {
                val xx = sx0 + (fx * (w - 1)).roundToInt()
                ring[n++] = pixels[yy * srcW + xx]
            }
        }
        val ringBucket = dominantBucket(ring, n)
        val ringCount = bucketCount(ring, n, ringBucket)
        if (ringCount.toFloat() / n >= RING_SHARE) {
            return Sample(bucketColor(ringBucket, ring, n, bucketCenter), ringCount.toFloat() / n)
        }

        // 密集档：按步长扫整格。每轴最多 24 个采样点，总数 ≤ DENSE_SAMPLES。
        val stepX = max(1, ceil(w / 24.0).toInt())
        val stepY = max(1, ceil(h / 24.0).toInt())
        val keys = IntArray(DENSE_SAMPLES)
        var used = 0
        for (yy in sy0..sy1 step stepY) {
            val row = yy * srcW
            for (xx in sx0..sx1 step stepX) {
                if (used == keys.size) break
                keys[used++] = pixels[row + xx]
            }
            if (used == keys.size) break
        }
        if (used == 0) {
            val p = pixels[((sy0 + sy1) / 2) * srcW + (sx0 + sx1) / 2]
            return Sample(0xFF000000.toInt() or (p and 0xFFFFFF), 1f)
        }
        val denseBucket = dominantBucket(keys, used)
        val denseCount = bucketCount(keys, used, denseBucket)
        return Sample(
            bucketColor(denseBucket, keys, used, bucketCenter),
            denseCount.toFloat() / used.coerceAtLeast(1),
        )
    }

    /** 主色桶：丢低 3 位后的键；同桶的 JPEG 抖动落到同一个值。 */
    private fun dominantBucket(samples: IntArray, n: Int): Int {
        val keys = IntArray(n)
        val counts = IntArray(n)
        var used = 0
        for (i in 0 until n) {
            val key = samples[i] and 0xF8F8F8
            var slot = -1
            for (s in 0 until used) if (keys[s] == key) { slot = s; break }
            if (slot < 0) {
                slot = used++
                keys[slot] = key
            }
            counts[slot]++
        }
        var best = 0
        for (s in 1 until used) if (counts[s] > counts[best]) best = s
        return if (used > 0) keys[best] and 0xF8F8F8 else 0
    }

    private fun bucketCount(samples: IntArray, n: Int, bucket: Int): Int {
        var c = 0
        for (i in 0 until n) if ((samples[i] and 0xF8F8F8) == (bucket and 0xF8F8F8)) c++
        return c
    }

    /**
     * 主色桶的代表色。
     *
     * 逐画像素档取桶中心：同一色块的每一格都必须得到完全相同的值，
     * 否则 ArtQuantizer 的 exact 支路会把一块纯色拆成十几个"新颜色"。
     * 普通档取桶内实际像素均值：误差更小，下游还有相似色合并兜底。
     */
    private fun bucketColor(bucket: Int, samples: IntArray, n: Int, center: Boolean): Int {
        if (center) return bucketCenterOf(bucket)
        var sr = 0
        var sg = 0
        var sb = 0
        var c = 0
        for (i in 0 until n) {
            val p = samples[i]
            if ((p and 0xF8F8F8) != (bucket and 0xF8F8F8)) continue
            sr += (p shr 16) and 0xFF
            sg += (p shr 8) and 0xFF
            sb += p and 0xFF
            c++
        }
        if (c == 0) return bucketCenterOf(bucket)
        val r = (sr + c / 2) / c
        val g = (sg + c / 2) / c
        val b = (sb + c / 2) / c
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun bucketCenterOf(bucket: Int): Int {
        val r = (((bucket shr 16) and 0xFF) or 0x04).coerceAtMost(255)
        val g = (((bucket shr 8) and 0xFF) or 0x04).coerceAtMost(255)
        val b = ((bucket and 0xFF) or 0x04).coerceAtMost(255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
