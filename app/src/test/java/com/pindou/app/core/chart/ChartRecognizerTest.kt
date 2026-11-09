package com.pindou.app.core.chart

import com.pindou.app.core.color.ColorConversions
import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.palette.BeadSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 合成一张「色号图」验证识别链路：24×18 格、20px 格子、1px 网格线、
 * 白格里有模拟色号的小字墨点、整图带 ±2 的 JPEG 级噪声。
 */
class ChartRecognizerTest {

    private val colors = intArrayOf(
        0xFFE11D48.toInt(), // 玫红
        0xFFF59E0B.toInt(), // 琥珀
        0xFF16A34A.toInt(), // 绿
        0xFF2563EB.toInt(), // 蓝
        0xFF7C3AED.toInt(), // 紫
        0xFF111827.toInt(), // 近黑
    )

    private class Chart(
        val pixels: IntArray,
        val w: Int,
        val h: Int,
        val cols: Int,
        val rows: Int,
        val cell: Int,
        val offset: Int,
        val cells: Array<IntArray>,
    )

    private fun buildChart(cols: Int = 24, rows: Int = 18, cell: Int = 20, offset: Int = 14): Chart {
        val w = cols * cell + offset * 2
        val h = rows * cell + offset * 2
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        // 0 = 没有格子（纸白留白）。不能用 -1：白豆的 ARGB 恰好就是 -1。
        val cells = Array(rows) { IntArray(cols) { 0 } }
        var seed = 12345
        fun rnd(bound: Int): Int {
            seed = seed * 1103515245 + 12345
            return (seed ushr 16) % bound
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                // 图案区域画色块，四周留一圈纯纸白；每 7 格挑一格做「白豆 + 色号小字」
                val inArt = r in 1..rows - 2 && c in 1..cols - 2
                val isWhiteBead = inArt && (r * cols + c) % 7 == 3
                val color = when {
                    !inArt -> 0
                    isWhiteBead -> 0xFFFFFFFF.toInt()
                    else -> colors[(r * 3 + c * 5) % colors.size]
                }
                cells[r][c] = color
                if (color == 0) continue
                val x0 = offset + c * cell + 1
                val y0 = offset + r * cell + 1
                for (y in y0 until y0 + cell - 1) {
                    for (x in x0 until x0 + cell - 1) {
                        val rr = ((color shr 16) and 0xFF) + rnd(5) - 2
                        val gg = ((color shr 8) and 0xFF) + rnd(5) - 2
                        val bb = (color and 0xFF) + rnd(5) - 2
                        px[y * w + x] = (0xFF shl 24) or
                            (rr.coerceIn(0, 255) shl 16) or
                            (gg.coerceIn(0, 255) shl 8) or
                            bb.coerceIn(0, 255)
                    }
                }
                if (isWhiteBead) {
                    // 白格中央写「色号」：3×5 的深灰墨点阵
                    val cx = offset + c * cell + cell / 2
                    val cy = offset + r * cell + cell / 2
                    for (dy in -2..2) {
                        for (dx in -1..1) {
                            if ((dx + dy) and 1 == 0) px[(cy + dy) * w + cx + dx] = 0xFF4B5563.toInt()
                        }
                    }
                }
            }
        }
        // 网格线：1px 深灰
        val line = 0xFF9CA3AF.toInt()
        for (c in 0..cols) {
            val x = offset + c * cell
            for (y in offset until offset + rows * cell + 1) px[y * w + x] = line
        }
        for (r in 0..rows) {
            val y = offset + r * cell
            for (x in offset until offset + cols * cell + 1) px[y * w + x] = line
        }
        return Chart(px, w, h, cols, rows, cell, offset, cells)
    }

    /** 图纸格 (r,c) 的中心点落在识别网格的第几行第几列；越界返回 null */
    private fun mapCell(chart: Chart, grid: ChartRecognizer.ChartGrid, r: Int, c: Int): Pair<Int, Int>? {
        val cx = chart.offset + c * chart.cell + chart.cell / 2.0
        val cy = chart.offset + r * chart.cell + chart.cell / 2.0
        val gc = ((cx - grid.offsetX) / grid.cellW).toInt()
        val gr = ((cy - grid.offsetY) / grid.cellH).toInt()
        if (gc < 0 || gc >= grid.cols || gr < 0 || gr >= grid.rows) return null
        return gr to gc
    }

    @Test
    fun `detect finds the grid`() {
        val chart = buildChart()
        val grid = ChartRecognizer.detect(chart.pixels, chart.w, chart.h)
        assertNotNull("应识别出网格", grid)
        grid!!
        // 四周留白被 trim 掉：内容区是 22×16 格
        assertTrue("列数 ${grid.cols} 应接近 22", abs(grid.cols - 22) <= 1)
        assertTrue("行数 ${grid.rows} 应接近 16", abs(grid.rows - 16) <= 1)
        assertTrue("格子边长 ${grid.cellW} 应接近 20", abs(grid.cellW - 20) < 1.0)
        assertTrue("行高 ${grid.cellH} 应接近 20", abs(grid.cellH - 20) < 1.0)
        assertTrue("偏移 ${grid.offsetX} 应接近 34", abs(grid.offsetX - 34) < 5)
    }

    @Test
    fun `refine keeps a good grid good`() {
        val chart = buildChart()
        val grid = ChartRecognizer.detect(chart.pixels, chart.w, chart.h)!!
        val refined = ChartRecognizer.refine(chart.pixels, chart.w, chart.h, grid)
        assertTrue(abs(refined.cellW - grid.cellW) < 1.5)
        assertTrue(abs(refined.offsetX - grid.offsetX) < 3)
    }

    @Test
    fun `sample reads cell colors and keeps white beads`() {
        val chart = buildChart()
        val grid = ChartRecognizer.detect(chart.pixels, chart.w, chart.h)!!
        val sample = ChartRecognizer.sample(chart.pixels, chart.w, chart.h, grid)
        assertEquals(grid.cols, sample.cols)
        assertEquals(grid.rows, sample.rows)

        var checked = 0
        for (r in 0 until chart.rows) {
            for (c in 0 until chart.cols) {
                val expected = chart.cells[r][c]
                if (expected == 0) continue
                val g = mapCell(chart, grid, r, c) ?: continue
                val idx = g.first * grid.cols + g.second
                assertEquals("格 ($r,$c) 不应为空（白豆+色号字也不能吃成空格）", false, sample.empty[idx])
                val got = sample.argb[idx]
                val dr = abs(((got shr 16) and 0xFF) - ((expected shr 16) and 0xFF))
                val dg = abs(((got shr 8) and 0xFF) - ((expected shr 8) and 0xFF))
                val db = abs((got and 0xFF) - (expected and 0xFF))
                assertTrue("格 ($r,$c) 颜色偏差 $dr/$dg/$db 过大", maxOf(dr, dg, db) <= 8)
                checked++
            }
        }
        assertTrue("应采到足够多的格子（$checked）", checked > 300)
        assertTrue("实心格 ${sample.filledCells} 太少", sample.filledCells >= 300)
    }

    @Test
    fun `toPattern maps sampled colors onto the palette`() {
        val chart = buildChart()
        val grid = ChartRecognizer.detect(chart.pixels, chart.w, chart.h)!!
        val sample = ChartRecognizer.sample(chart.pixels, chart.w, chart.h, grid)
        // 色卡必须含白色：白豆在图纸里是一个正经色号，不是空格
        val entries = (colors + 0xFFFFFFFF.toInt()).mapIndexed { i, c ->
            BeadPalette.Entry("T$i", null, c, ColorConversions.argbToLab(c))
        }
        val palette = BeadPalette("TEST", "Test", BeadSize.MM5, null, entries)
        val result = ChartRecognizer.toPattern(sample, palette)
        assertEquals(sample.cols, result.width)
        assertEquals(sample.rows, result.height)

        var matched = 0
        var total = 0
        for (r in 0 until chart.rows) {
            for (c in 0 until chart.cols) {
                val expected = chart.cells[r][c]
                if (expected == 0) continue
                val g = mapCell(chart, grid, r, c) ?: continue
                total++
                val idx = g.first * grid.cols + g.second
                if (result.cells[idx] >= 0 && entries[result.cells[idx]].argb == expected) matched++
            }
        }
        assertTrue("色号映射命中 $matched/$total", matched >= total * 0.97)
    }

    @Test
    fun `plain photo is rejected`() {
        // 平滑渐变没有网格线，detect 不能硬凑出一个网格
        val w = 300
        val h = 300
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 128
        }
        assertNull(ChartRecognizer.detect(px, w, h))
    }
}
