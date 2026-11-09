package com.pindou.app.core.editor

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions
import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.palette.BeadSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorReplaceOpsTest {

    private val NAVY_A = 0xFF1A2438.toInt()
    private val NAVY_B = 0xFF1B2539.toInt()
    private val RED = 0xFFFF2A2A.toInt()
    private val PINK = 0xFFF7B7C8.toInt()
    private val PINK_NEAR = 0xFFF6B9C9.toInt()

    private fun paletteOf(argbs: List<Int>): BeadPalette = BeadPalette(
        brand = "TEST",
        brandLabel = "TEST",
        beadSize = BeadSize.MM5,
        source = null,
        colors = argbs.mapIndexed { i, argb ->
            BeadPalette.Entry("C$i", null, argb, ColorConversions.argbToLab(argb))
        },
    )

    private fun noLocks(pages: List<IntArray>) = pages.map { BooleanArray(it.size) }

    @Test
    fun `指定色替换只动目标色号并保留空格`() {
        val pages = listOf(intArrayOf(0, 0, 1, -1, 2))
        val out = ColorReplaceOps.replaceExact(pages, noLocks(pages), from = 0, to = 2)
        assertTrue(out.pages[0].contentEquals(intArrayOf(2, 2, 1, -1, 2)))
        assertEquals(2, out.movedCells)
        assertEquals(1, out.moves.size)
        assertEquals(0, out.moves[0].from)
        assertEquals(2, out.moves[0].to)
    }

    @Test
    fun `指定色替换相同时不产生改动`() {
        val pages = listOf(intArrayOf(0, 1))
        val out = ColorReplaceOps.replaceExact(pages, noLocks(pages), 1, 1)
        assertFalse(out.changed)
        assertTrue(out.pages[0].contentEquals(pages[0]))
    }

    @Test
    fun `替换相似色把阈值内的色并到目标色`() {
        val palette = paletteOf(listOf(PINK, PINK_NEAR, RED))
        val d = Ciede2000.deltaE(
            palette.colors[0].lab, palette.colors[1].lab,
        )
        // 构造的粉色足够接近，阈值 5 应能覆盖
        assertTrue("测试色差 $d 应小于 5", d < 5.0)
        val pages = listOf(intArrayOf(0, 0, 1, 2, -1))
        val out = ColorReplaceOps.replaceSimilar(
            pages, noLocks(pages), palette, from = 0, to = 2, maxDeltaE = 5.0,
        )
        // 0 和 1 都变成 2；红色本来就是 2 不变；空格不动
        assertTrue(out.pages[0].contentEquals(intArrayOf(2, 2, 2, 2, -1)))
        assertEquals(3, out.movedCells)
        assertEquals(2, out.moves.size)
    }

    @Test
    fun `替换相似色阈值为零时只替换源色`() {
        val palette = paletteOf(listOf(PINK, PINK_NEAR, RED))
        val pages = listOf(intArrayOf(0, 1))
        val out = ColorReplaceOps.replaceSimilar(
            pages, noLocks(pages), palette, from = 0, to = 2, maxDeltaE = 0.0,
        )
        assertTrue(out.pages[0].contentEquals(intArrayOf(2, 1)))
        assertEquals(1, out.movedCells)
    }

    @Test
    fun `锁定格跳过并计数`() {
        val pages = listOf(intArrayOf(0, 0, 0))
        val locks = listOf(booleanArrayOf(false, true, false))
        val out = ColorReplaceOps.replaceExact(pages, locks, from = 0, to = 1)
        assertTrue(out.pages[0].contentEquals(intArrayOf(1, 0, 1)))
        assertEquals(2, out.movedCells)
        assertEquals(1, out.lockedSkipped)
    }

    @Test
    fun `合并相近色保留用量最多者并输出映射`() {
        val palette = paletteOf(listOf(NAVY_A, NAVY_B, RED))
        // A 97 格、B 2 格、红 1 格；B 应并入 A
        val pages = listOf(IntArray(100) { 0 }.also { it[0] = 1; it[1] = 1; it[2] = 2 })
        val out = ColorReplaceOps.mergeSimilar(pages, noLocks(pages), palette, maxDeltaE = 3.0)
        assertEquals(1, out.moves.size)
        assertEquals(0, out.moves[0].to)
        assertEquals(1, out.moves[0].from)
        assertEquals(2, out.moves[0].cells)
        assertEquals(99, out.pages[0].count { it == 0 })
        assertEquals(1, out.pages[0].count { it == 2 })
    }

    @Test
    fun `合并相近色阈值为零不改动`() {
        val palette = paletteOf(listOf(NAVY_A, NAVY_B))
        val pages = listOf(intArrayOf(0, 1))
        val out = ColorReplaceOps.mergeSimilar(pages, noLocks(pages), palette, maxDeltaE = 0.0)
        assertFalse(out.changed)
        assertTrue(out.pages[0].contentEquals(pages[0]))
    }

    @Test
    fun `跨页统计用量后合并`() {
        val palette = paletteOf(listOf(NAVY_A, NAVY_B, RED))
        // 第 1 页 A 多，第 2 页 B 多：合计 A 仍更多，B 并入 A
        val pages = listOf(
            IntArray(10) { 0 },
            IntArray(10) { 1 }.also { it[9] = 0 },
        )
        val out = ColorReplaceOps.mergeSimilar(pages, noLocks(pages), palette, maxDeltaE = 3.0)
        assertEquals(1, out.moves.size)
        assertEquals(9, out.moves[0].cells)
        assertTrue(out.pages[1].all { it == 0 })
    }
}
