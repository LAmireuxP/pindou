package com.pindou.app.core.pattern

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 图片内容简化：在进入降采样前对源像素做保边平滑，减少细节噪点、保留主体轮廓。
 *
 * 三种模式：
 * - [Mode.OFF]     原图不改
 * - [Mode.CARTOON] 卡通化：块内主导色扁平化（保边去细节，类似双边滤波的快速近似）
 * - [Mode.EDGE]    边缘增强：在卡通化基础上对检测到的边缘像素加深，轮廓更锐
 *
 * 实现：把图像按 [ tileSize]×[tileSize] 分块，每块取 4-bit 量化众数桶均值替换块内所有像素；
 * 跨块边界检测为边缘（与邻块主导色 ΔRGB 大于阈值），边缘像素做加深处理（仅 EDGE 模式）。
 * 块大小决定简化力度：块越大细节越少；默认 8px，配合 4-bit 量化桶（4096 桶）。
 *
 * 纯 Kotlin，像素为 ARGB 行优先，可在任意线程执行。
 */
object ContentSimplifier {

    enum class Mode { OFF, CARTOON, EDGE }

    fun simplify(
        pixels: IntArray,
        width: Int,
        height: Int,
        mode: Mode,
        tileSize: Int = 8,
        edgeStrength: Int = 40,
    ): IntArray {
        if (mode == Mode.OFF || width <= 0 || height <= 0 || pixels.size != width * height) return pixels
        val ts = tileSize.coerceIn(2, 64)
        val flat = flattenByDominant(pixels, width, height, ts)
        if (mode == Mode.CARTOON) return flat
        // EDGE：在卡通化基础上加深跨块边界
        return enhanceEdges(flat, width, height, ts, edgeStrength)
    }

    /** 块内主导色扁平化（保边去细节）：每块取众数桶均值替换整块 */
    private fun flattenByDominant(pixels: IntArray, width: Int, height: Int, ts: Int): IntArray {
        val out = IntArray(pixels.size)
        val counts = IntArray(4096)
        val sumsR = IntArray(4096)
        val sumsG = IntArray(4096)
        val sumsB = IntArray(4096)
        // 触碰桶列表复用，只重置用过的桶（避免每块 4×4096 全量清零）
        val touched = IntArray(4096)
        var touchedCount = 0
        for (by in 0 until height step ts) {
            val y1 = min(by + ts, height)
            for (bx in 0 until width step ts) {
                val x1 = min(bx + ts, width)
                // 块内分桶取众数（先重置上一块用过的桶）
                for (i in 0 until touchedCount) {
                    val b = touched[i]
                    counts[b] = 0
                    sumsR[b] = 0
                    sumsG[b] = 0
                    sumsB[b] = 0
                }
                touchedCount = 0
                var bestBucket = -1
                var maxCount = 0
                for (y in by until y1) {
                    val row = y * width
                    for (x in bx until x1) {
                        val p = pixels[row + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                        val c = ++counts[bucket]
                        if (c == 1) touched[touchedCount++] = bucket
                        sumsR[bucket] += r
                        sumsG[bucket] += g
                        sumsB[bucket] += b
                        if (c > maxCount) {
                            maxCount = c
                            bestBucket = bucket
                        }
                    }
                }
                val mean = if (bestBucket >= 0 && maxCount > 0) {
                    (0xFF shl 24) or
                        (sumsR[bestBucket] / maxCount shl 16) or
                        (sumsG[bestBucket] / maxCount shl 8) or
                        (sumsB[bestBucket] / maxCount)
                } else 0xFF shl 24
                // 整块替换为均值
                for (y in by until y1) {
                    val row = y * width
                    for (x in bx until x1) out[row + x] = mean
                }
            }
        }
        return out
    }

    /** 跨块边界加深：当前像素与右/下邻像素若分属不同块且主导色差异大，加深为暗色 */
    private fun enhanceEdges(
        pixels: IntArray,
        width: Int,
        height: Int,
        ts: Int,
        strength: Int,
    ): IntArray {
        val out = pixels.copyOf()
        val dark = (0xFF shl 24) or (strength shl 16) or (strength shl 8) or strength
        // 预算每格所属块的主导色
        val tileColor = HashMap<Long, Int>()
        for (by in 0 until height step ts) {
            for (bx in 0 until width step ts) {
                tileColor[by.toLong() shl 32 or bx.toLong()] = pixels[by * width + bx]
            }
        }
        fun tileOf(x: Int, y: Int): Int {
            val bx = (x / ts) * ts
            val by = (y / ts) * ts
            return tileColor[by.toLong() shl 32 or bx.toLong()] ?: 0xFF shl 24
        }
        fun rgbDiff(a: Int, b: Int): Int {
            val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            val db = abs((a and 0xFF) - (b and 0xFF))
            return max(dr, max(dg, db))
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val cur = tileOf(x, y)
                var isEdge = false
                if (x + 1 < width) {
                    val right = tileOf(x + 1, y)
                    if (rgbDiff(cur, right) > 30) isEdge = true
                }
                if (y + 1 < height) {
                    val down = tileOf(x, y + 1)
                    if (rgbDiff(cur, down) > 30) isEdge = true
                }
                if (isEdge) out[idx] = dark
            }
        }
        return out
    }
}
