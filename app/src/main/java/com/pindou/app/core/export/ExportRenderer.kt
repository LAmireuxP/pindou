package com.pindou.app.core.export

import com.pindou.app.core.pattern.PatternResult

/**
 * 导出渲染：把图纸渲染为位图（带色号版 / 符号版）。
 * 纯逻辑，不依赖 Android Bitmap——输出为像素数组 + 尺寸，由调用方包装成 Bitmap。
 * 这样可以在 JVM 单测里完整验证渲染结果。
 */
object ExportRenderer {

    /** 符号版编号前缀（与色号组合成唯一标记，黑白打印友好） */
    val SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456".toCharArray()

    data class RenderedBitmap(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    data class RenderOptions(
        val cellPx: Int = 64,
        val showCodes: Boolean = true,
        val showGrid: Boolean = true,
        /**
         * 标题栏高度（像素）。内嵌点阵字体只有 ASCII 字形，中文标题会缺字，
         * 因此这里只**预留空间**，真正的标题文字由 Android 层用系统字体绘制
         * （见 ExportService.drawTitleBar）。
         */
        val titleHeightPx: Int = 0,
    )

    private const val BG = 0xFFFFFAF0.toInt()          // 奶油画布
    private const val GRID_LINE = 0x33000000          // 半透明黑
    private const val TEXT_DARK = 0xFF333333.toInt()
    private const val TEXT_LIGHT = 0xFFF5F5F5.toInt()

    /**
     * 渲染彩色图纸。每格 cellPx 像素，格内居中绘制色号（若开启且格子够大）。
     * 顶部可选标题栏（图纸名 + 尺寸/色卡信息）。
     */
    fun renderPattern(result: PatternResult, options: RenderOptions): RenderedBitmap {
        val cell = options.cellPx.coerceIn(8, 256)
        val titleH = options.titleHeightPx.coerceAtLeast(0)
        val w = result.width * cell + 1
        val h = result.height * cell + 1 + titleH
        val pixels = IntArray(w * h) { BG }

        for (gy in 0 until result.height) {
            for (gx in 0 until result.width) {
                val idx = result.cells[gy * result.width + gx]
                if (idx < 0) continue
                val argb = result.palette.colors[idx].argb
                val x0 = gx * cell
                val y0 = gy * cell + titleH
                fillRect(pixels, w, h, x0, y0, cell, cell, argb)
                if (options.showCodes && cell >= 20) {
                    val code = result.palette.colors[idx].code
                    val textColor = if (isLight(argb)) TEXT_DARK else TEXT_LIGHT
                    // 点阵字高 7px，缩放到不超过格子高度 28%，避免遮住颜色
                    val maxScale = (cell * 0.28f / 7f).toInt().coerceAtLeast(1)
                    val byWidth = (cell * 0.9f / (code.length * 6f)).toInt().coerceAtLeast(1)
                    val glyphScale = minOf(maxScale, byWidth, 4)
                    val tw = code.length * 6 * glyphScale - glyphScale
                    val th = 7 * glyphScale
                    drawTextLine(
                        pixels, w, h, code,
                        x0 + (cell - tw) / 2,
                        y0 + (cell - th) / 2,
                        textColor, glyphScale,
                    )
                }
            }
        }

        if (options.showGrid) {
            for (gx in 0..result.width) {
                val x = (gx * cell).coerceAtMost(w - 1)
                drawVLine(pixels, w, h, x, titleH, GRID_LINE)
            }
            for (gy in 0..result.height) {
                val y = (gy * cell + titleH).coerceAtMost(h - 1)
                drawHLine(pixels, w, h, y, GRID_LINE)
            }
        }
        return RenderedBitmap(w, h, pixels)
    }

    /**
     * 渲染黑白符号版：每格白底 + 黑色符号 + 格内小字色号。
     * 按色卡索引循环分配符号（相同色号 = 相同符号），随附对照表。
     */
    fun renderSymbol(result: PatternResult, options: RenderOptions): RenderedBitmap {
        val cell = options.cellPx.coerceIn(16, 256)
        // usage() 按用量降序，符号分配因此是确定性的：用得多的颜色拿前面的符号
        val usedIndices = result.usage().map { it.first }
        val symbolOf = HashMap<Int, Char>()
        usedIndices.forEachIndexed { i, colorIdx ->
            symbolOf[colorIdx] = SYMBOLS[i % SYMBOLS.size]
        }

        val legendRows = usedIndices.size
        val titleH = cell
        val legendH = ((legendRows + 3) / 4) * (cell / 2) + cell
        val w = result.width * cell + 1
        val h = result.height * cell + 1 + titleH + legendH
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }

        drawTextLine(pixels, w, h, "SYMBOL CHART", 8, titleH / 2 - 8, TEXT_DARK, 2)

        for (gy in 0 until result.height) {
            for (gx in 0 until result.width) {
                val idx = result.cells[gy * result.width + gx]
                if (idx < 0) continue
                val x0 = gx * cell
                val y0 = gy * cell + titleH
                // 符号版底色用浅灰，靠色号文字区分（黑白打印机友好）
                fillRect(pixels, w, h, x0 + 2, y0 + 2, cell - 4, cell - 4, 0xFFF2F2F2.toInt())
                if (cell >= 24) {
                    val code = result.palette.colors[idx].code
                    drawTextLine(
                        pixels, w, h, "${symbolOf[idx]} $code",
                        x0 + 2, y0 + cell - 9, TEXT_DARK, 1,
                    )
                }
            }
        }

        // 网格
        for (gx in 0..result.width) {
            val x = (gx * cell).coerceAtMost(w - 1)
            drawVLine(pixels, w, h, x, titleH, GRID_LINE)
        }
        for (gy in 0..result.height) {
            val y = (gy * cell + titleH).coerceAtMost(h - 1)
            drawHLine(pixels, w, h, y, GRID_LINE)
        }

        // 对照表：4 列 × N 行
        val legendTop = result.height * cell + titleH + cell / 2
        val colW = w / 4
        usedIndices.forEachIndexed { i, colorIdx ->
            val col = i % 4
            val row = i / 4
            val x = col * colW
            val y = legendTop + row * (cell / 2)
            val entry = result.palette.colors[colorIdx]
            drawRectOutline(pixels, w, h, x + 4, y + 2, cell / 3, cell / 3, entry.argb)
            val label = "${symbolOf[colorIdx]} ${entry.code}"
            drawTextLine(pixels, w, h, label, x + cell / 3 + 8, y + 2, TEXT_DARK, 1)
        }

        return RenderedBitmap(w, h, pixels)
    }

    // ---------- 基础绘图（纯像素操作） ----------

    private fun fillRect(p: IntArray, w: Int, h: Int, x: Int, y: Int, rw: Int, rh: Int, color: Int) {
        for (yy in y until (y + rh).coerceAtMost(h)) {
            if (yy < 0) continue
            val row = yy * w
            for (xx in x until (x + rw).coerceAtMost(w)) {
                if (xx < 0) continue
                p[row + xx] = color
            }
        }
    }

    private fun drawRectOutline(p: IntArray, w: Int, h: Int, x: Int, y: Int, rw: Int, rh: Int, color: Int) {
        for (xx in x until (x + rw).coerceAtMost(w)) {
            setPx(p, w, h, xx, y, color)
            setPx(p, w, h, xx, y + rh - 1, color)
        }
        for (yy in y until (y + rh).coerceAtMost(h)) {
            setPx(p, w, h, x, yy, color)
            setPx(p, w, h, x + rw - 1, yy, color)
        }
    }

    private fun setPx(p: IntArray, w: Int, h: Int, x: Int, y: Int, color: Int) {
        if (x in 0 until w && y in 0 until h) p[y * w + x] = color
    }

    private fun drawVLine(p: IntArray, w: Int, h: Int, x: Int, y0: Int, color: Int) {
        for (y in y0 until h) setPx(p, w, h, x, y, color)
    }

    private fun drawHLine(p: IntArray, w: Int, h: Int, y: Int, color: Int) {
        for (x in 0 until w) setPx(p, w, h, x, y, color)
    }

    private fun isLight(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r * 299 + g * 587 + b * 114 > 128 * 1000
    }

    /** 绘制一行 ASCII 文本（内嵌 5x7 点阵，只覆盖色号常用字符集） */
    fun drawTextLine(p: IntArray, canvasW: Int, canvasH: Int, text: String, x: Int, y: Int, color: Int, scale: Int) {
        var cursor = x
        for (ch in text) {
            val glyph = Font5x7[ch]
            if (glyph != null) {
                for (row in 0 until 7) {
                    val bits = glyph[row]
                    for (col in 0 until 5) {
                        if (bits and (1 shl (4 - col)) != 0) {
                            for (dy in 0 until scale) {
                                for (dx in 0 until scale) {
                                    setPx(p, canvasW, canvasH, cursor + col * scale + dx, y + row * scale + dy, color)
                                }
                            }
                        }
                    }
                }
            }
            cursor += 6 * scale
        }
    }

    /** 5x7 点阵字体：数字、大写字母、常用符号 */
    private val Font5x7: Map<Char, IntArray> = buildMap {
        fun g(vararg rows: Int) = intArrayOf(*rows)
        put('0', g(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110))
        put('1', g(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110))
        put('2', g(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111))
        put('3', g(0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110))
        put('4', g(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010))
        put('5', g(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110))
        put('6', g(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110))
        put('7', g(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000))
        put('8', g(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110))
        put('9', g(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100))
        put('A', g(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001))
        put('B', g(0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110))
        put('C', g(0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110))
        put('D', g(0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110))
        put('E', g(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111))
        put('F', g(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000))
        put('G', g(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111))
        put('H', g(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001))
        put('I', g(0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110))
        put('J', g(0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100))
        put('K', g(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001))
        put('L', g(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111))
        put('M', g(0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001))
        put('N', g(0b10001, 0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001))
        put('O', g(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110))
        put('P', g(0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000))
        put('Q', g(0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101))
        put('R', g(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001))
        put('S', g(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110))
        put('T', g(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100))
        put('U', g(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110))
        put('V', g(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100))
        put('W', g(0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001))
        put('X', g(0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001))
        put('Y', g(0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100))
        put('Z', g(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111))
        put(' ', g(0, 0, 0, 0, 0, 0, 0))
        put('-', g(0, 0, 0, 0b11111, 0, 0, 0))
        put(':', g(0, 0b00100, 0, 0, 0, 0b00100, 0))
        put('.', g(0, 0, 0, 0, 0, 0b00100, 0b00100))
        put('/', g(0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0, 0))
        put('#', g(0b01010, 0b11111, 0b01010, 0b01010, 0b11111, 0b01010, 0))
    }
}