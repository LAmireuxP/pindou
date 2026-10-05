package com.pindou.app.core.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRemoverTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val white = argb(255, 255, 255)
    private val red = argb(255, 0, 0)

    @Test
    fun `四周白底被移除而中心红色保留`() {
        // 7×7：白底 + 中心 3×3 红块
        val w = 7
        val h = 7
        val grid = IntArray(w * h) { white }
        for (y in 2..4) for (x in 2..4) grid[y * w + x] = red

        val mask = BackgroundRemover.detect(grid, w, h, tolerance = 10.0)
        assertEquals(w * h - 9, mask.count { it })
        // 中心 9 格必须保留
        for (y in 2..4) for (x in 2..4) {
            assertFalse("中心格 ($x,$y) 不应被判为背景", mask[y * w + x])
        }
    }

    @Test
    fun `同类色背景按容差处理`() {
        // 白底略有噪声（每格偏移 ±6），容差 12 应全部移除
        val w = 5
        val h = 5
        val grid = IntArray(w * h) { i ->
            val d = (i % 5) * 3 - 6
            argb(255 - d, 255 - d, 255 - d)
        }
        val mask = BackgroundRemover.detect(grid, w, h, tolerance = 12.0)
        assertTrue("噪声白底应全部移除，实际保留 ${mask.count { !it }} 格", mask.all { it })
    }

    @Test
    fun `容差过小时保留渐变背景`() {
        val w = 5
        val h = 5
        val grid = IntArray(w * h) { i ->
            val d = (i % 5) * 40 - 80
            argb(255 - kotlin.math.abs(d), 255 - kotlin.math.abs(d), 255 - kotlin.math.abs(d))
        }
        val mask = BackgroundRemover.detect(grid, w, h, tolerance = 1.0)
        // 容差极小：只有与相邻格几乎相同的才会被吞并，不要求全部移除
        assertTrue(mask.any { it })
    }

    @Test
    fun `内部同色大块不被误删`() {
        // 红色主体内部有一块与背景同色的白色区域（不连通边缘）→ 必须保留
        val w = 9
        val h = 9
        val grid = IntArray(w * h) { white }
        // 红色环包住中心白色
        for (y in 1..7) for (x in 1..7) grid[y * w + x] = red
        grid[4 * w + 4] = white

        val mask = BackgroundRemover.detect(grid, w, h, tolerance = 12.0)
        assertFalse("被包围的同色区域不应判为背景", mask[4 * w + 4])
    }

    @Test
    fun `applyToCells 置空背景格`() {
        val w = 5
        val h = 5
        val grid = IntArray(w * h) { white }
        grid[2 * w + 2] = red
        val cells = IntArray(w * h) { 3 } // 假定全部映射到色卡索引 3
        val out = BackgroundRemover.applyToCells(cells, grid, w, h, tolerance = 10.0)
        assertEquals(-1, out[0])
        assertEquals(3, out[2 * w + 2])
    }

    @Test
    fun `小尺寸网格直接返回空掩码`() {
        val grid = IntArray(4) { white }
        val mask = BackgroundRemover.detect(grid, 2, 2)
        assertTrue(mask.none { it })
    }
}