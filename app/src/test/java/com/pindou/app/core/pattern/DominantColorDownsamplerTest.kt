package com.pindou.app.core.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DominantColorDownsamplerTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `纯色图整格同色`() {
        val w = 64; val h = 64
        val red = argb(200, 30, 40)
        val pixels = IntArray(w * h) { red }
        val grid = DominantColorDownsampler.downsample(pixels, w, h, 8, 8)
        assertEquals(64, grid.size)
        assertTrue(grid.all { it == red })
    }

    @Test
    fun `左右两色块正确分格`() {
        val w = 100; val h = 50
        val left = argb(220, 40, 40)
        val right = argb(40, 40, 220)
        val pixels = IntArray(w * h) { i -> if (i % w < w / 2) left else right }
        val grid = DominantColorDownsampler.downsample(pixels, w, h, 2, 1)
        assertEquals(left, grid[0])
        assertEquals(right, grid[1])
    }

    @Test
    fun `目标网格大于源像素时逐像素采样`() {
        // 2×2 源图 → 4×4 网格：每格至少覆盖一个源像素的邻域
        val pixels = intArrayOf(
            argb(255, 0, 0), argb(0, 255, 0),
            argb(0, 0, 255), argb(255, 255, 0),
        )
        val grid = DominantColorDownsampler.downsample(pixels, 2, 2, 4, 4)
        assertEquals(16, grid.size)
        assertTrue(grid.all { (it shr 24) == 0xFF.toByte().toInt() })
    }
}
