package com.pindou.app.core.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class PixelCalibrationTest {

    @Test
    fun `改列数时右边界不动`() {
        val grid = PixelCalibration.Grid(10, 10, 5.0, 5.0, 2.0, 2.0)
        val out = grid.withCols(20)
        assertEquals(20, out.cols)
        assertEquals(2.5, out.cellW, 0.0001)
        assertEquals(grid.right, out.right, 0.0001)
    }

    @Test
    fun `改行数时下边界不动`() {
        val grid = PixelCalibration.Grid(10, 10, 5.0, 5.0, 2.0, 2.0)
        val out = grid.withRows(4)
        assertEquals(4, out.rows)
        assertEquals(12.5, out.cellH, 0.0001)
        assertEquals(grid.bottom, out.bottom, 0.0001)
    }

    @Test
    fun `改单元格大小时覆盖范围不变`() {
        val grid = PixelCalibration.Grid(10, 10, 5.0, 5.0, 2.0, 2.0)
        val out = grid.withCellSize(10.0)
        assertEquals(5, out.cols)
        assertEquals(5, out.rows)
        assertEquals(10.0, out.cellW, 0.0001)
        assertEquals(grid.right, out.right, 0.0001)
        assertEquals(grid.bottom, out.bottom, 0.0001)
    }

    @Test
    fun `平移网格只改偏移`() {
        val grid = PixelCalibration.Grid(10, 10, 5.0, 5.0, 2.0, 2.0)
        val out = grid.nudged(1.5, -0.5)
        assertEquals(3.5, out.offsetX, 0.0001)
        assertEquals(1.5, out.offsetY, 0.0001)
        assertEquals(grid.cols, out.cols)
        assertEquals(grid.cellW, out.cellW, 0.0001)
    }

    @Test
    fun `兜底网格完整落在图内`() {
        val grid = PixelCalibration.fallback(100, 200, 32, 32)
        assertTrue(grid.cellW >= 1.0)
        assertTrue(grid.cellH >= 1.0)
        assertTrue(grid.offsetX >= 0.0)
        assertTrue(grid.offsetY >= 0.0)
        assertTrue(grid.right <= 100.0 + 0.0001)
        assertTrue(grid.bottom <= 200.0 + 0.0001)
    }

    @Test
    fun `纯色图没有格线信号`() {
        val pixels = IntArray(64 * 64) { 0xFF3A7BD5.toInt() }
        val grid = PixelCalibration.Grid(8, 8, 8.0, 8.0, 0.0, 0.0)
        assertEquals(0f, PixelCalibration.edgeScore(pixels, 64, 64, grid), 0.0001f)
        val alignment = PixelCalibration.refine(pixels, 64, 64, grid)
        assertFalse(alignment.applied)
        assertEquals(grid, alignment.grid)
    }

    @Test
    fun `智能对齐把偏移吸附回真实格线`() {
        val (pixels, trueGrid) = checkerPixels()
        val guessed = trueGrid.nudged(2.0, -1.5).copy(cellW = 7.6, cellH = 8.4)
        val alignment = PixelCalibration.refine(pixels, 64, 64, guessed, threshold = 0.02f)
        assertTrue(alignment.applied)
        assertEquals(4.0, alignment.grid.offsetX, 1.0)
        assertEquals(4.0, alignment.grid.offsetY, 1.0)
        assertEquals(8.0, alignment.grid.cellW, 0.6)
        assertEquals(8.0, alignment.grid.cellH, 0.6)
        assertTrue(alignment.score > 0.02f)
    }

    @Test
    fun `阈值高于格线强度时保留手动网格`() {
        val (pixels, trueGrid) = checkerPixels()
        val guessed = trueGrid.nudged(1.0, 1.0)
        val alignment = PixelCalibration.refine(pixels, 64, 64, guessed, threshold = 0.99f)
        assertFalse(alignment.applied)
        assertEquals(guessed, alignment.grid)
    }

    @Test
    fun `极小图兜底不会要求单格小于一像素`() {
        val grid = PixelCalibration.fallback(3, 2)
        assertEquals(3, grid.cols)
        assertEquals(2, grid.rows)
        assertEquals(1.0, grid.cellW, 0.0001)
        assertEquals(1.0, grid.cellH, 0.0001)
        assertTrue(grid.right <= 3.0)
        assertTrue(grid.bottom <= 2.0)
    }

    @Test
    fun `网格平移保留至少一格在画面内`() {
        val grid = PixelCalibration.Grid(10, 10, 5.0, 5.0, 4.0, 4.0)
        val out = grid.nudged(80.0, -80.0).coerceWithin(64, 64)
        // 允许网格滑出右/下边缘，但至少留一格可见
        assertEquals(59.0, out.offsetX, 0.0001)
        assertEquals(0.0, out.offsetY, 0.0001)
        assertTrue(out.offsetX <= 64.0 - out.cellW)
        assertTrue(out.offsetY >= 0.0)
    }

    @Test
    fun `铺满画布的网格仍可向右拖动`() {
        // 检测结果刚好 56×13=728 铺满 735 宽，旧逻辑会把偏移夹回 9，
        // 手指往右拖时网格一动不动。
        val grid = PixelCalibration.Grid(56, 50, 13.0, 12.9, 9.0, 11.0)
        val out = grid.nudged(1.0, 1.0).coerceWithin(735, 658)
        assertEquals(10.0, out.offsetX, 0.0001)
        assertEquals(12.0, out.offsetY, 0.0001)
        assertEquals(13.0, out.cellW, 0.0001)
    }

    @Test
    fun `确认裁图时整张网格收进图像`() {
        // 拖动把网格推到右下，确认时块长要压到能放下，偏移夹回图内。
        val grid = PixelCalibration.Grid(56, 50, 13.0, 12.9, 101.2, 78.5)
        val out = grid.fittedWithin(735, 658)
        assertTrue(out.offsetX >= 0.0)
        assertTrue(out.offsetY >= 0.0)
        assertTrue(out.right <= 735.0 + 0.0001)
        assertTrue(out.bottom <= 658.0 + 0.0001)
        assertEquals(56, out.cols)
        assertEquals(50, out.rows)
        assertTrue(out.cellW <= 735.0 / 56)
        assertTrue(out.cellH <= 658.0 / 50)
    }

    @Test
    fun `智能对齐横纵非等比缩放分别吸附`() {
        val w = 80
        val h = 64
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val gx = (x - 4) / 8
                val gy = (y - 4) / 6
                val r = 40 + (gx % 8) * 23
                val g = 30 + (gy % 8) * 27
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or 60
            }
        }
        val guess = PixelCalibration.Grid(9, 9, 7.7, 5.8, 4.8, 3.7)
        val out = PixelCalibration.refine(pixels, w, h, guess, threshold = 0.01f)
        assertTrue(out.applied)
        assertEquals(8.0, out.grid.cellW, 0.6)
        assertEquals(6.0, out.grid.cellH, 0.6)
    }

    /**
     * 64×64，7×7 个 8px 色块，第一格从 (4,4) 开始。
     * 每格用不同 RGB 组合，格线处形成清晰突变。
     */
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
