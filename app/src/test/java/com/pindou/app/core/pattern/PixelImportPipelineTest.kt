package com.pindou.app.core.pattern

import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.palette.PaletteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelImportPipelineTest {

    private val palette: BeadPalette = PaletteJson.parse(
        """
        {
          "brand": "T", "brandLabel": "测试", "beadSize": "5mm", "version": 1,
          "colors": [
            {"code": "W", "hex": "#FFFFFF"},
            {"code": "K", "hex": "#000000"},
            {"code": "R", "hex": "#FF0000"},
            {"code": "G", "hex": "#00CC00"},
            {"code": "B", "hex": "#0000FF"}
          ]
        }
        """.trimIndent(),
    )

    /**
     * 进校准页时的默认路径：检测给的网格偏了 1~2px，智能对齐必须当场
     * 吸附，而不是等用户把开关关一次再打开。
     */
    @Test
    fun `智能对齐默认在进页面时生效`() {
        val (pixels, trueGrid) = checkerPixels()
        val guessed = PixelGridInspector.Detection(
            grid = trueGrid.nudged(1.5, -1.2).copy(cellW = 7.7, cellH = 8.3),
            method = PixelGridInspector.Method.PERIODIC,
            confidence = 0.60f,
        )
        val detect: (IntArray, Int, Int) -> PixelGridInspector.Detection = { _, _, _ -> guessed }

        val prepared = PixelImportPipeline.prepare(
            pixels, 64, 64, smart = true, threshold = 0.02f, detect = detect,
        )
        assertTrue(prepared.applied)
        assertEquals(4.0, prepared.grid.offsetX, 1.0)
        assertEquals(4.0, prepared.grid.offsetY, 1.0)
        assertEquals(8.0, prepared.grid.cellW, 0.6)
        assertEquals(8.0, prepared.grid.cellH, 0.6)
        assertEquals(true, prepared.detected)

        val manual = PixelImportPipeline.prepare(
            pixels, 64, 64, smart = false, threshold = 0.02f, detect = detect,
        )
        assertFalse(manual.applied)
        assertEquals(guessed.grid, manual.grid)
    }

    /**
     * 「看图纸」和「完成」必须来自同一条管线：预览色 = 色号色，
     * 重复转换结果一致，裁图尺寸与网格一致。
     */
    @Test
    fun `预览与生成共用同一份结果`() {
        val cols = 60
        val rows = 60
        val block = 4
        val srcW = cols * block
        val srcH = rows * block
        val colors = intArrayOf(
            0xFFFF0000.toInt(),
            0xFF00CC00.toInt(),
            0xFF0000FF.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
        )
        val pixels = IntArray(srcW * srcH)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val cx = x / block
                val cy = y / block
                pixels[y * srcW + x] = colors[(cx / 5 + cy / 7) % colors.size]
            }
        }
        val grid = PixelCalibration.Grid(cols, rows, block.toDouble(), block.toDouble(), 0.0, 0.0)
        val options = GeneratorOptions(gridW = 29, gridH = 29, autoExposure = false)

        val first = PixelImportPipeline.convert(pixels, srcW, srcH, grid, palette, options)
        val second = PixelImportPipeline.convert(pixels, srcW, srcH, grid, palette, options)

        assertEquals(cols, first.result.width)
        assertEquals(rows, first.result.height)
        assertEquals(cols * rows, first.result.cells.size)
        assertEquals(cols * rows, first.previewArgb.size)
        assertEquals(grid, first.grid)
        assertEquals(srcW, first.cropped.width)
        assertEquals(srcH, first.cropped.height)

        // 同一输入必须得到同一张图：预览和完成之间不能有随机差异
        assertTrue(first.result.cells.contentEquals(second.result.cells))
        assertTrue(first.previewArgb.contentEquals(second.previewArgb))

        for (i in first.result.cells.indices) {
            val idx = first.result.cells[i]
            if (idx >= 0) {
                assertEquals(first.result.palette.colors[idx].argb, first.previewArgb[i])
            } else {
                assertEquals(0, first.previewArgb[i])
            }
        }

        // 色块原样落格：第一格是纯红，不能被聚类/清理变成别的色
        val redIndex = palette.colors.indexOfFirst { it.code == "R" }
        assertEquals(redIndex, first.result.cells[0])
    }

    /**
     * 像素图导入是 1:1 还原，照片那套限色/抖动/去背不能沿用：
     * 上一轮把限色调到 1 色、开了抖动和去背，这一轮导像素画仍应保留
     * 全部色块和白色背景。
     */
    @Test
    fun `照片参数不会带进像素图`() {
        val cols = 8
        val rows = 8
        val block = 4
        val srcW = cols * block
        val srcH = rows * block
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val pixels = IntArray(srcW * srcH) { white }
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val cx = x / block
                val cy = y / block
                if (cx in 2..3 && cy in 2..5) pixels[y * srcW + x] = red
                if (cx in 4..5 && cy in 2..5) pixels[y * srcW + x] = blue
            }
        }
        val grid = PixelCalibration.Grid(cols, rows, block.toDouble(), block.toDouble(), 0.0, 0.0)
        val hostile = GeneratorOptions(
            gridW = 8,
            gridH = 8,
            maxColors = 1,
            ditheringEnabled = true,
            removeBackground = true,
            autoExposure = true,
        )

        val conversion = PixelImportPipeline.convert(pixels, srcW, srcH, grid, palette, hostile)
        val cells = conversion.result.cells

        // 去背没有生效：四角的白背景还是色块，不是空格
        assertEquals(palette.colors.indexOfFirst { it.code == "W" }, cells[0])
        assertEquals(palette.colors.indexOfFirst { it.code == "W" }, cells[cols - 1])
        assertFalse(cells.any { it < 0 })
        // 限色没有生效：白、红、蓝至少三种色号
        assertTrue(cells.distinct().size >= 3)
        // 抖动没有生效：红块内部仍是纯红
        val redIndex = palette.colors.indexOfFirst { it.code == "R" }
        assertEquals(redIndex, cells[2 * cols + 2])
    }

    /** 64×64，7×7 个 8px 色块，第一格从 (4,4) 开始 */
    private fun checkerPixels(): Pair<IntArray, PixelCalibration.Grid> {
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { 0xFF202020.toInt() }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val gx = (x - 4).toDouble() / 8.0
                val gy = (y - 4).toDouble() / 8.0
                if (gx < 0.0 || gy < 0.0 || gx >= 7.0 || gy >= 7.0) continue
                val cx = gx.toInt()
                val cy = gy.toInt()
                val r = 40 + (cx * 29 + cy * 7) % 180
                val g = 30 + (cx * 11 + cy * 31) % 180
                val b = 50 + (cx * 17 + cy * 13) % 160
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels to PixelCalibration.Grid(
            cols = 7,
            rows = 7,
            cellW = 8.0,
            cellH = 8.0,
            offsetX = 4.0,
            offsetY = 4.0,
        )
    }
}
