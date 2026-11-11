package com.pindou.app.core.pattern

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 像素画导入的「检测 → 校准 → 采样反馈」三段式。
 *
 * 像素导入不该让用户面对一张坐标纸，而应先把「系统认到了什么」摆出来：
 * 多少格、多少粒、多少色、多少空白。这里补上这层反馈：
 *
 * 1. [detect] 先判断是不是原生 1:1 像素图（60×60 的 PNG 不能被当成照片
 *    去猜 6~36px 的块长），再退回周期网格检测，最后才用兜底网格。
 * 2. [analyze] 按当前网格逐格采样，产出一张同尺寸的小图 —— 这就是最终
 *    图纸的样子。用户在拖动格线时看到小图跟着变，才知道自己有没有对齐，
 *    而不是只看到一堆线。
 * 3. 空白只从网格四周向内泛洪：主体内部和背景同色的区域不会被误删。
 *
 * 全部纯 Kotlin，可在 Dispatchers.Default 上跑。
 */
object PixelGridInspector {

    enum class Method { NATIVE_1TO1, PERIODIC, FALLBACK, MANUAL }

    data class Detection(
        val grid: PixelCalibration.Grid,
        val method: Method,
        val confidence: Float,
    )

    /**
     * @param previewArgb 逐格采样后的小图，长度 = cols×rows；空白格为 0
     * @param blank       与图片边缘连通的背景格数
     * @param mixed       格内出现两种以上颜色的格数（对齐不准时飙升）
     */
    data class Analysis(
        val cols: Int,
        val rows: Int,
        val previewArgb: IntArray,
        val occupied: Int,
        val blank: Int,
        val outside: Int,
        val colorCount: Int,
        val mixed: Int,
        val confidence: Float,
        val lineHits: Int,
        val lineTotal: Int,
    ) {
        val uniformShare: Float
            get() = if (occupied <= 0) 0f else 1f - mixed.toFloat() / occupied
    }

    fun detect(pixels: IntArray, w: Int, h: Int): Detection {
        if (w <= 0 || h <= 0 || pixels.size != w * h) {
            return Detection(PixelCalibration.fallback(max(w, 1), max(h, 1)), Method.FALLBACK, 0f)
        }
        PixelArtDetector.detectNative(pixels, w, h)?.let { art ->
            val grid = PixelCalibration.Grid(
                cols = art.artW,
                rows = art.artH,
                cellW = art.blockW,
                cellH = art.blockH,
                offsetX = art.ox,
                offsetY = art.oy,
            )
            return Detection(grid, Method.NATIVE_1TO1, 0.99f)
        }
        val art = PixelArtDetector.detectForced(pixels, w, h)
        if (art != null) {
            val grid = PixelCalibration.Grid(
                cols = art.artW.coerceIn(PixelCalibration.MIN_COLS, PixelCalibration.MAX_COLS),
                rows = art.artH.coerceIn(PixelCalibration.MIN_COLS, PixelCalibration.MAX_COLS),
                cellW = art.blockW.coerceIn(PixelCalibration.MIN_CELL, PixelCalibration.MAX_CELL),
                cellH = art.blockH.coerceIn(PixelCalibration.MIN_CELL, PixelCalibration.MAX_CELL),
                offsetX = art.ox,
                offsetY = art.oy,
            )
            val fit = PixelCalibration.measure(pixels, w, h, grid)
            return Detection(
                grid = grid,
                method = Method.PERIODIC,
                confidence = (0.70f * fit.score + 0.30f).coerceIn(0f, 1f),
            )
        }
        return Detection(PixelCalibration.fallback(w, h), Method.FALLBACK, 0f)
    }

    fun analyze(
        pixels: IntArray,
        w: Int,
        h: Int,
        grid: PixelCalibration.Grid,
        method: Method = Method.MANUAL,
    ): Analysis {
        val cols = grid.cols.coerceAtLeast(1)
        val rows = grid.rows.coerceAtLeast(1)
        val preview = IntArray(cols * rows)
        if (w <= 0 || h <= 0 || pixels.size != w * h) {
            return Analysis(cols, rows, preview, 0, 0, cols * rows, 0, 0, 0f, 0, 0)
        }

        val outside = BooleanArray(cols * rows)
        val transparent = BooleanArray(cols * rows)
        val mixedFlags = BooleanArray(cols * rows)

        // 逐格采样。只按格子与图片的交集取色：拖动时网格会滑出图外，
        // 越界部分直接算「图外」，不能让边缘残色参与统计。
        for (cy in 0 until rows) {
            val y0 = grid.offsetY + cy * grid.cellH
            val y1 = y0 + grid.cellH
            for (cx in 0 until cols) {
                val x0 = grid.offsetX + cx * grid.cellW
                val x1 = x0 + grid.cellW
                val idx = cy * cols + cx
                val overlapW = min(x1, w.toDouble()) - max(x0, 0.0)
                val overlapH = min(y1, h.toDouble()) - max(y0, 0.0)
                if (overlapW <= 0.0 || overlapH <= 0.0 ||
                    overlapW * overlapH < grid.cellW * grid.cellH * 0.60
                ) {
                    outside[idx] = true
                    continue
                }
                val sample = PixelCellSampler.sample(
                    pixels, w, h,
                    max(x0, 0.0), max(y0, 0.0),
                    min(x1, w.toDouble()), min(y1, h.toDouble()),
                    bucketCenter = true,
                )
                preview[idx] = sample.argb
                if (sample.confidence < 0.62f) {
                    mixedFlags[idx] = true
                }
                // 透明 PNG：格心透明就当作空格，供后面的边缘泛洪使用
                val ccx = ((x0 + x1) / 2.0).toInt().coerceIn(0, w - 1)
                val ccy = ((y0 + y1) / 2.0).toInt().coerceIn(0, h - 1)
                if ((pixels[ccy * w + ccx] ushr 24) < 32) transparent[idx] = true
            }
        }

        val blank = floodBackground(preview, outside, transparent, cols, rows)
        for (i in preview.indices) {
            if (blank[i] || outside[i]) preview[i] = 0
        }

        val colors = mergeColors(preview, blank, outside)
        val occupiedFinal = preview.indices.count { preview[it] != 0 }
        val mixed = preview.indices.count { mixedFlags[it] && preview[it] != 0 }
        val uniform = if (occupiedFinal <= 0) 0f else 1f - mixed.toFloat() / occupiedFinal
        val fit = if (method == Method.NATIVE_1TO1) {
            PixelCalibration.Fit(0, 0, 1f)
        } else {
            PixelCalibration.measure(pixels, w, h, grid)
        }
        val confidence = if (method == Method.NATIVE_1TO1) {
            0.99f
        } else {
            (0.65f * fit.score + 0.35f * uniform).coerceIn(0f, 1f)
        }

        return Analysis(
            cols = cols,
            rows = rows,
            previewArgb = preview,
            occupied = occupiedFinal,
            blank = blank.count { it },
            outside = outside.count { it },
            colorCount = colors,
            mixed = mixed,
            confidence = confidence,
            lineHits = fit.hits,
            lineTotal = fit.total,
        )
    }

    /**
     * 从网格四边向内泛洪，只吃掉与边缘连通的背景。
     *
     * 这是用户反复提的那个问题的正解：主体内部有一块和外部背景同色的区域
     * （白兔子的脸、粉色兔子的裙子），不能因为颜色相同就删掉 —— 它和图片
     * 边缘不连通。
     */
    private fun floodBackground(
        preview: IntArray,
        outside: BooleanArray,
        transparent: BooleanArray,
        cols: Int,
        rows: Int,
    ): BooleanArray {
        val blank = BooleanArray(preview.size)
        val bg = dominantBorderColor(preview, outside, transparent, cols, rows)
        val queue = IntArray(preview.size)
        var head = 0
        var tail = 0

        fun push(i: Int) {
            if (blank[i] || outside[i]) return
            val isTransparent = transparent[i]
            if (!isTransparent && !closeTo(preview[i], bg, 26)) return
            blank[i] = true
            queue[tail++] = i
        }

        for (cx in 0 until cols) {
            push(cx)
            push((rows - 1) * cols + cx)
        }
        for (cy in 0 until rows) {
            push(cy * cols)
            push(cy * cols + cols - 1)
        }

        while (head < tail) {
            val i = queue[head++]
            val cx = i % cols
            val cy = i / cols
            if (cx > 0) push(i - 1)
            if (cx < cols - 1) push(i + 1)
            if (cy > 0) push(i - cols)
            if (cy < rows - 1) push(i + cols)
        }
        return blank
    }

    /** 边环上出现最多的颜色；全透明或没有边环时返回 0 */
    private fun dominantBorderColor(
        preview: IntArray,
        outside: BooleanArray,
        transparent: BooleanArray,
        cols: Int,
        rows: Int,
    ): Int {
        var best = 0
        var bestCount = 0
        val seen = HashMap<Int, Int>()
        fun visit(i: Int) {
            if (outside[i] || transparent[i]) return
            val key = preview[i] and 0xF0F0F0
            val c = (seen[key] ?: 0) + 1
            seen[key] = c
            if (c > bestCount) {
                bestCount = c
                best = key
            }
        }
        for (cx in 0 until cols) {
            visit(cx)
            visit((rows - 1) * cols + cx)
        }
        for (cy in 0 until rows) {
            visit(cy * cols)
            visit(cy * cols + cols - 1)
        }
        return best
    }

    /** 占用格去重后的颜色数；5bit 桶 + 12 级容差合并相近色 */
    private fun mergeColors(preview: IntArray, blank: BooleanArray, outside: BooleanArray): Int {
        val reps = ArrayList<Int>(256)
        val seen = HashSet<Int>()
        for (i in preview.indices) {
            if (blank[i] || outside[i]) continue
            val p = preview[i]
            if ((p ushr 24) == 0) continue
            val key = p and 0xF8F8F8
            if (!seen.add(key)) continue
            var merged = false
            for (r in reps) {
                if (abs(((p shr 16) and 0xFF) - ((r shr 16) and 0xFF)) <= 12 &&
                    abs(((p shr 8) and 0xFF) - ((r shr 8) and 0xFF)) <= 12 &&
                    abs((p and 0xFF) - (r and 0xFF)) <= 12
                ) {
                    merged = true
                    break
                }
            }
            if (!merged && reps.size < 256) reps.add(p)
        }
        return reps.size
    }

    private fun closeTo(a: Int, b: Int, tolerance: Int): Boolean {
        if ((a ushr 24) == 0) return true
        if ((b ushr 24) == 0) return false
        return abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tolerance &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tolerance &&
            abs((a and 0xFF) - (b and 0xFF)) <= tolerance
    }
}
