package com.pindou.app.core.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionCleanerTest {

    // 0/1 近同色（ΔE < 5，应互并），2 是高对比红（真细节，保护）
    private val palette = listOf(0xFFF5F5F5.toInt(), 0xFFEDEDED.toInt(), 0xFFFF0000.toInt())

    private fun grid10() = IntArray(100) { 0 }

    @Test
    fun `小于最小面积的碎块并入最大邻块`() {
        val cells = grid10().also { it[0] = 1; it[1] = 1 } // 顶部边角 2 格碎块
        val out = RegionCleaner.clean(cells, 10, 10, 3, palette)
        assertTrue(out.none { it == 1 })
        assertEquals(100, out.count { it == 0 })
    }

    @Test
    fun `达标面积的区域不清理`() {
        val cells = grid10().also { for (x in 0 until 4) it[x] = 1 } // 4 格 ≥ minArea 3
        val out = RegionCleaner.clean(cells, 10, 10, 3, palette)
        assertEquals(4, out.count { it == 1 })
    }

    @Test
    fun `高色差真细节被保护`() {
        val cells = grid10().also { it[40] = 2; it[41] = 2 } // 2 格红点，ΔE 大
        val out = RegionCleaner.clean(cells, 10, 10, 3, palette)
        assertEquals(2, out.count { it == 2 })
    }

    @Test
    fun `锁定格所在区域不动`() {
        val cells = grid10().also { it[40] = 1; it[41] = 1 }
        val out = RegionCleaner.clean(cells, 10, 10, 3, palette, locked = setOf(40))
        assertEquals(2, out.count { it == 1 })
    }

    @Test
    fun `minArea 小于等于 1 时原样返回`() {
        val cells = grid10().also { it[0] = 1 }
        val out = RegionCleaner.clean(cells, 10, 10, 1, palette)
        assertEquals(cells, out) // 内容相等（同一对象亦可）
    }

    @Test
    fun `被空格包围的碎块不动`() {
        // 中央 1 格色 1，四周全是 -1：无邻接色块，保持原样
        val cells = IntArray(100) { -1 }.also { it[45] = 1 }
        val out = RegionCleaner.clean(cells, 10, 10, 3, palette)
        assertEquals(1, out[45])
    }

    @Test
    fun `自适应最小面积钳制`() {
        assertEquals(4, RegionCleaner.adaptiveMinArea(841))    // 29×29 → 3.36 → 下限 4
        assertEquals(24, RegionCleaner.adaptiveMinArea(10000)) // 100×100 → 40 → 上限 24
        assertEquals(4, RegionCleaner.adaptiveMinArea(0))
    }
}
