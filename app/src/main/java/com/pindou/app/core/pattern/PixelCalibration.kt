package com.pindou.app.core.pattern

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 像素画导入时的网格校准。
 *
 * 自动检测只给一个初始猜测：被二次压缩、带描边或局部透明的像素画，
 * 检测出的块长/偏移经常差 1~3px。差这一点，采样就会跨到相邻色块，
 * 整张图的花瓣边缘会碎掉。这里把网格参数暴露给用户，并提供按格线
 * 梯度自动吸附的入口。
 *
 * 所有函数都是纯 Kotlin，不依赖 Android，UI 线程之外可直接调用。
 */
object PixelCalibration {

    const val MIN_COLS = 4
    const val MAX_COLS = 200
    /** 1:1 的像素画一格就是 1px，不能从 2px 起算 */
    const val MIN_CELL = 1.0
    const val MAX_CELL = 512.0

    /**
     * 默认对齐阈值：格线平均强度达到最强格线的 30% 才认为信号可信。
     *
     * 旧值是 0.06，但那时 [edgeScore] 除以的是「每行都是纯黑纯白突变」的
     * 理论上限，真实像素画只有 6% 左右，滑条上的 6% 对用户没有任何含义。
     * 现在分数按剖面 99 分位归一化，30% 就是「明显比噪声强」。
     */
    const val DEFAULT_THRESHOLD = 0.30f

    /**
     * 一张像素画的采样网格。
     *
     * [offsetX]/[offsetY] 是第一格左上角在源图中的位置；
     * [cellW]/[cellH] 是一格占多少源图像素。两者都允许小数，
     * 非整数倍缩放的像素画只有小数块长才能对齐。
     */
    data class Grid(
        val cols: Int,
        val rows: Int,
        val cellW: Double,
        val cellH: Double,
        val offsetX: Double,
        val offsetY: Double,
    ) {
        val right: Double get() = offsetX + cols * cellW
        val bottom: Double get() = offsetY + rows * cellH

        /** 改列数：右边界不动，格子跟着变宽/变窄 */
        fun withCols(n: Int): Grid {
            val c = n.coerceIn(MIN_COLS, MAX_COLS)
            if (c == cols) return this
            return copy(cols = c, cellW = (cols * cellW / c).coerceIn(MIN_CELL, MAX_CELL))
        }

        /** 改行数：下边界不动 */
        fun withRows(n: Int): Grid {
            val r = n.coerceIn(MIN_COLS, MAX_COLS)
            if (r == rows) return this
            return copy(rows = r, cellH = (rows * cellH / r).coerceIn(MIN_CELL, MAX_CELL))
        }

        /** 改单元格边长：网格覆盖范围不变，列/行数反向推导 */
        fun withCellSize(px: Double): Grid {
            val size = px.coerceIn(MIN_CELL, MAX_CELL)
            val c = (cols * cellW / size).roundToInt().coerceIn(MIN_COLS, MAX_COLS)
            val r = (rows * cellH / size).roundToInt().coerceIn(MIN_COLS, MAX_COLS)
            return Grid(
                cols = c,
                rows = r,
                cellW = (cols * cellW / c).coerceIn(MIN_CELL, MAX_CELL),
                cellH = (rows * cellH / r).coerceIn(MIN_CELL, MAX_CELL),
                offsetX = offsetX,
                offsetY = offsetY,
            )
        }

        /**
         * 改单元格边长（校准页的「3.单元格大小」）。
         *
         * 与 [withCellSize] 不同：列/行数不动，只缩放格子。校准页的操作
         * 顺序是「先定列数，再让格子边长贴住图片边缘」，如果这里反过来
         * 改列数，用户刚调好的 60 列会跳成 58 列。横纵比保留，非等比
         * 缩放的源图不会被强行拉成正方格。
         */
        fun withCell(px: Double): Grid {
            val size = px.coerceIn(MIN_CELL, MAX_CELL)
            val ratio = if (cellW > 1e-6) cellH / cellW else 1.0
            return copy(
                cellW = size,
                cellH = (size * ratio).coerceIn(MIN_CELL, MAX_CELL),
            )
        }

        fun nudged(dx: Double, dy: Double): Grid =
            copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

        /**
         * 把网格收敛到图像范围内。
         *
         * 不能要求整张网格都落在图内：像素画常常正好铺满画布，自动
         * 检测出的网格已经顶到右/下边缘，再按"整张网格入图"夹偏移，
         * 用户往右拖会一动不动。这里只保证单格不超出图像，并让网格
         * 至少保留一行/一列在画面里；超出部分在确认时按图像边界裁掉。
         */
        fun coerceWithin(imgW: Int, imgH: Int): Grid {
            require(imgW > 0 && imgH > 0) { "图片尺寸非法: ${imgW}x$imgH" }
            val cw = min(cellW, imgW.toDouble()).coerceIn(MIN_CELL, MAX_CELL)
            val ch = min(cellH, imgH.toDouble()).coerceIn(MIN_CELL, MAX_CELL)
            val ox = offsetX.coerceIn(0.0, (imgW - cw).coerceAtLeast(0.0))
            val oy = offsetY.coerceIn(0.0, (imgH - ch).coerceAtLeast(0.0))
            return copy(cellW = cw, cellH = ch, offsetX = ox, offsetY = oy)
        }

        /**
         * 整张网格收进图像内，供确认时裁图使用。
         *
         * 拖动时允许网格滑出边缘，确认时必须裁到图内：先按列/行把块长
         * 压到能放下，再夹偏移。这样裁出的宽高就是网格宽高，采样偏移
         * 归零后不会错位。
         */
        fun fittedWithin(imgW: Int, imgH: Int): Grid {
            require(imgW > 0 && imgH > 0) { "图片尺寸非法: ${imgW}x$imgH" }
            val cw = (min(cellW, imgW.toDouble() / cols)).coerceIn(MIN_CELL, MAX_CELL)
            val ch = (min(cellH, imgH.toDouble() / rows)).coerceIn(MIN_CELL, MAX_CELL)
            val ox = offsetX.coerceIn(0.0, (imgW - cols * cw).coerceAtLeast(0.0))
            val oy = offsetY.coerceIn(0.0, (imgH - rows * ch).coerceAtLeast(0.0))
            return copy(cellW = cw, cellH = ch, offsetX = ox, offsetY = oy)
        }
    }

    /** 自动对齐结果；[applied] = false 表示格线信号低于阈值，保留原网格 */
    data class Alignment(
        val grid: Grid,
        val score: Float,
        val applied: Boolean,
    )

    /**
     * 格线拟合结果。
     *
     * [score] 是每条网格线归一化强度的平均值（0..1），[hits]/[total] 是
     * 明显压到色块边界上的线数。UI 同时显示两者：只给一个百分比仍然抽象，
     * 「58/62 条线压中」是用户可以自己核对的量。
     */
    data class Fit(
        val hits: Int,
        val total: Int,
        val score: Float,
    )

    /**
     * 未检测到规则网格时的兜底：覆盖整图的 [cols]×[rows] 网格。
     * 用户可以在校准页直接改列数/行数，不会卡在"识别失败"。
     */
    fun fallback(
        imgW: Int,
        imgH: Int,
        cols: Int = 32,
        rows: Int = 32,
    ): Grid {
        require(imgW > 0 && imgH > 0) { "图片尺寸非法: ${imgW}x$imgH" }
        // 一格最小 1px：列/行不能超过图片本身的像素数，否则网格会伸出图外
        // 极小图（图标、1:1 像素画）不能强行给 4 列：四列会要求单格
        // 小于 1px。这里把上限让给图像实际尺寸，最少保留 1 列。
        val c = cols.coerceIn(1, min(MAX_COLS, imgW).coerceAtLeast(1))
        val r = rows.coerceIn(1, min(MAX_COLS, imgH).coerceAtLeast(1))
        val cell = max(1.0, min(imgW.toDouble() / c, imgH.toDouble() / r))
        val offsetX = (imgW - c * cell) / 2.0
        val offsetY = (imgH - r * cell) / 2.0
        return Grid(c, r, cell, cell, offsetX, offsetY)
    }

    /**
     * 格线强度：网格线落在源图颜色边界上的平均程度，0..1。
     * 0 = 每条线都落在纯色内部；1 = 每条线都是纯黑/纯白突变。
     */
    fun edgeScore(pixels: IntArray, w: Int, h: Int, grid: Grid): Float =
        measure(pixels, w, h, grid).score

    /**
     * 逐条网格线检查是否落在色块边界上。
     *
     * 先把梯度剖面按 99 分位归一化：像素画里真正压中的线会接近 1.0，
     * 噪声线在 0.1 以下。这样 [Fit.score] 与滑条阈值是同一个尺度，
     * 「阈值 30%」在真实图片上有可复现的含义。
     *
     * @param minHit 记为「命中」的单线强度下限；只影响 [Fit.hits] 的显示
     */
    fun measure(
        pixels: IntArray,
        w: Int,
        h: Int,
        grid: Grid,
        minHit: Float = 0.25f,
    ): Fit {
        if (w <= 0 || h <= 0 || pixels.size != w * h) return Fit(0, 0, 0f)
        if (grid.cols < 1 || grid.rows < 1) return Fit(0, 0, 0f)
        val nx = normalizeProfile(gradientProfile(pixels, w, h, horizontal = true))
        val ny = normalizeProfile(gradientProfile(pixels, w, h, horizontal = false))
        var hits = 0
        var total = 0
        var sum = 0.0
        for (k in 0..grid.cols) {
            val v = sample(nx, grid.offsetX + k * grid.cellW).coerceIn(0.0, 1.0)
            sum += v
            total++
            if (v >= minHit) hits++
        }
        for (k in 0..grid.rows) {
            val v = sample(ny, grid.offsetY + k * grid.cellH).coerceIn(0.0, 1.0)
            sum += v
            total++
            if (v >= minHit) hits++
        }
        if (total == 0) return Fit(0, 0, 0f)
        return Fit(hits, total, (sum / total).toFloat().coerceIn(0f, 1f))
    }

    /**
     * 智能对齐：在当前位置附近搜索单元格边长与偏移。
     *
     * 横向、纵向的得分互相独立，分别取最优再合并，避免
     * offsetX × offsetY 的组合爆炸。搜索范围限制在半格以内，
     * 不会把整张网格跳到隔壁色块。
     *
     * @param threshold 最低格线强度；低于该值不应用，返回 applied=false
     */
    fun refine(
        pixels: IntArray,
        w: Int,
        h: Int,
        grid: Grid,
        threshold: Float = DEFAULT_THRESHOLD,
    ): Alignment {
        if (w <= 0 || h <= 0 || pixels.size != w * h) {
            return Alignment(grid, 0f, applied = false)
        }
        val gx = gradientProfile(pixels, w, h, horizontal = true)
        val gy = gradientProfile(pixels, w, h, horizontal = false)

        // 单元格边长 ±10%，按 2% 一档；非整数倍缩放的误差通常在这一带。
        // 横纵分别搜索：源图被非等比缩放时，cellW 和 cellH 不会一起变。
        val scaleSteps = IntArray(11) { it - 5 }
        var bestCellW = grid.cellW
        var bestCellH = grid.cellH
        var bestOffsetX = grid.offsetX
        var bestOffsetY = grid.offsetY
        var bestX = -1.0
        var bestY = -1.0

        for (step in scaleSteps) {
            val scale = 1.0 + step * 0.02
            val cw = (grid.cellW * scale).coerceIn(MIN_CELL, MAX_CELL)
            val ox = bestOffset(gx, grid.offsetX, cw, grid.cols)
            if (ox.first > bestX) {
                bestX = ox.first
                bestCellW = cw
                bestOffsetX = ox.second
            }
        }
        for (step in scaleSteps) {
            val scale = 1.0 + step * 0.02
            val ch = (grid.cellH * scale).coerceIn(MIN_CELL, MAX_CELL)
            val oy = bestOffset(gy, grid.offsetY, ch, grid.rows)
            if (oy.first > bestY) {
                bestY = oy.first
                bestCellH = ch
                bestOffsetY = oy.second
            }
        }

        val refined = grid.copy(
            cellW = bestCellW,
            cellH = bestCellH,
            offsetX = bestOffsetX,
            offsetY = bestOffsetY,
        ).coerceWithin(w, h)
        // 搜索阶段比较的是原始梯度（同一张图内可比）；最终分数换成
        // 归一化后的格线强度，才能和用户设定的阈值同量纲。
        val score = measure(pixels, w, h, refined).score
        return Alignment(
            grid = if (score >= threshold) refined else grid,
            score = score,
            applied = score >= threshold,
        )
    }

    /**
     * 在 [base] ±半格内找格线平均梯度最大的偏移。
     * 返回 (得分, 偏移)；得分已按 3 通道 × 255 归一化。
     */
    private fun bestOffset(
        profile: DoubleArray,
        base: Double,
        cell: Double,
        count: Int,
    ): Pair<Double, Double> {
        if (profile.isEmpty() || count < 1) return 0.0 to base
        val span = cell / 2.0
        val steps = 16
        var bestScore = -1.0
        var best = base
        for (i in 0..steps) {
            val offset = base - span + span * 2.0 * i / steps
            var sum = 0.0
            for (k in 0..count) {
                sum += sample(profile, offset + k * cell)
            }
            val score = sum / (count + 1)
            if (score > bestScore) {
                bestScore = score
                best = offset
            }
        }
        return bestScore to best
    }

    /** 每列（行）与前一列（行）的平均通道差，已归一化到 0..1 */
    private fun gradientProfile(
        pixels: IntArray,
        w: Int,
        h: Int,
        horizontal: Boolean,
    ): DoubleArray {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val out = DoubleArray(len)
        if (len < 2) return out
        val step = max(1, lines / 240)
        var line = 0
        var samples = 0
        while (line < lines) {
            var prev = if (horizontal) pixels[line * w] else pixels[line]
            var i = 1
            while (i < len) {
                val cur = if (horizontal) pixels[line * w + i] else pixels[i * w + line]
                out[i] += abs((cur shr 16 and 0xFF) - (prev shr 16 and 0xFF)) +
                    abs((cur shr 8 and 0xFF) - (prev shr 8 and 0xFF)) +
                    abs((cur and 0xFF) - (prev and 0xFF))
                prev = cur
                i++
            }
            samples++
            line += step
        }
        val norm = (samples.coerceAtLeast(1) * 3.0 * 255.0)
        for (i in out.indices) out[i] /= norm
        return out
    }

    /** 线性插值采样；越界取最近的边缘值 */
    private fun sample(profile: DoubleArray, pos: Double): Double {
        if (profile.isEmpty()) return 0.0
        val p = pos.coerceIn(0.0, (profile.size - 1).toDouble())
        val i0 = p.toInt()
        if (i0 >= profile.size - 1) return profile[profile.size - 1]
        val f = p - i0
        return profile[i0] * (1.0 - f) + profile[i0 + 1] * f
    }

    /**
     * 按 99 分位归一化。用分位而不是最大值：一条孤立的 JPEG 振铃不该
     * 把整张图其他格线压到 5%，而 99 分位仍由真正的色块边界决定。
     */
    private fun normalizeProfile(profile: DoubleArray): DoubleArray {
        if (profile.isEmpty()) return profile
        val sorted = profile.copyOf()
        sorted.sort()
        val p99 = sorted[((sorted.size - 1) * 0.99).toInt()]
        if (p99 <= 1e-9) return DoubleArray(profile.size)
        return DoubleArray(profile.size) { (profile[it] / p99).coerceIn(0.0, 1.0) }
    }
}
