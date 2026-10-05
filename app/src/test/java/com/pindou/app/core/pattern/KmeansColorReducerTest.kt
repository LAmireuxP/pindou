package com.pindou.app.core.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KmeansColorReducerTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `颜色数不超限时不处理`() {
        val grid = intArrayOf(argb(255, 0, 0), argb(0, 0, 255), argb(255, 0, 0))
        val out = KmeansColorReducer.reduce(grid, maxColors = 8)
        assertTrue(out.contentEquals(grid))
    }

    @Test
    fun `压缩到 1 色时全部相同`() {
        val grid = IntArray(100) { i -> argb((i * 7) % 256, (i * 13) % 256, (i * 29) % 256) }
        val out = KmeansColorReducer.reduce(grid, maxColors = 1)
        assertEquals(1, out.distinct().size)
    }

    @Test
    fun `双峰颜色压缩到 2 色`() {
        val red = argb(220, 30, 30)
        val blue = argb(30, 30, 220)
        val grid = IntArray(50) { if (it % 2 == 0) red else blue }
        // 加入接近的噪声色，制造 50 种"名义上不同"的颜色
        val noisy = IntArray(50) { i ->
            val base = if (i % 2 == 0) red else blue
            val d = (i / 2) - 12
            argb(
                ((base shr 16) and 0xFF) + d,
                (base shr 8) and 0xFF,
                base and 0xFF,
            )
        }
        val out = KmeansColorReducer.reduce(noisy, maxColors = 2)
        assertEquals(2, out.distinct().size)
    }
}
