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

    @Test
    fun `自适应聚类数按格数钳制`() {
        assertEquals(12, KmeansColorReducer.adaptiveClusterCount(100)) // 下限
        assertEquals(18, KmeansColorReducer.adaptiveClusterCount(225)) // 15×15
        assertEquals(32, KmeansColorReducer.adaptiveClusterCount(10000)) // 上限
    }

    @Test
    fun `量化后空间连贯不跳变`() {
        // 平滑横向渐变 + 逐格微噪声（制造"每格颜色都不同"的跳号条件）
        val w = 10; val h = 10
        val grid = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            argb((x * 25) + ((i * 7) % 9) - 4, (y * 25) + ((i * 5) % 9) - 4, 128)
        }
        val out = KmeansColorReducer.reduce(grid, maxColors = 4)
        // 全图只剩 ≤ 4 种颜色（类中心）
        assertTrue("量化后颜色数 ${out.distinct().size} 超限", out.distinct().size <= 4)
        // 中间一行上颜色切换次数 ≤ 6：渐变应形成连续色带而非逐格跳变
        var transitions = 0
        for (x in 0 until w - 1) if (out[5 * w + x] != out[5 * w + x + 1]) transitions++
        assertTrue("渐变行跳变 $transitions 次，存在跳号", transitions <= 6)
    }

    @Test
    fun `显著色不被大簇吸收`() {
        val skin = argb(230, 190, 170)   // 97 格
        val hair = argb(35, 30, 60)      // 50 格
        val eye = argb(0, 40, 255)       // 3 格，高饱和蓝，与深色簇中心 ΔE 远超 12
        val grid = IntArray(150)
        for (i in grid.indices) grid[i] = if (i < 97) skin else if (i < 147) hair else eye
        // 标记眼睛为显著色 → 眼睛保持原色不被吸收
        val rescued = KmeansColorReducer.reduce(grid, maxColors = 2, salientColors = setOf(eye))
        assertTrue("显著眼睛色应保留原色", rescued.contains(eye))
        // 不标记 → 被簇中心吸收，眼睛色消失
        val absorbed = KmeansColorReducer.reduce(grid, maxColors = 2)
        assertTrue("未标记时眼睛色应被吸收", !absorbed.contains(eye))
    }
}
