package com.pindou.app.core.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class BrushStampTest {

    private fun collect(w: Int, h: Int, idx: Int, size: Int): List<Int> {
        val out = ArrayList<Int>()
        BrushStamp.forEachCell(w, h, idx, size) { out.add(it) }
        return out.sorted()
    }

    @Test
    fun `1x1 只命中中心`() {
        assertEquals(listOf(4), collect(3, 3, 4, 1))
        assertEquals(listOf(0), collect(3, 3, 0, 1))
    }

    @Test
    fun `3x3 在中心命中九格`() {
        assertEquals((0..8).toList(), collect(3, 3, 4, 3))
    }

    @Test
    fun `3x3 在角上裁成 2x2`() {
        // 左上角 (0,0)：x∈[0,1], y∈[0,1]
        assertEquals(listOf(0, 1, 3, 4), collect(3, 3, 0, 3))
        // 右下角 (2,2)
        assertEquals(listOf(4, 5, 7, 8), collect(3, 3, 8, 3))
    }

    @Test
    fun `5x5 不被裁剪时是完整方块`() {
        val cells = collect(9, 9, 4 * 9 + 4, 5)
        assertEquals(25, cells.size)
        assertEquals(2 * 9 + 2, cells.first())
        assertEquals(6 * 9 + 6, cells.last())
    }

    @Test
    fun `5x5 贴边只覆盖可见部分`() {
        // (0,0) 半径 2：x 0..2, y 0..2 -> 9 格
        assertEquals(9, collect(9, 9, 0, 5).size)
        // (0,4)：x 0..2, y 2..6 -> 15 格
        assertEquals(15, collect(9, 9, 4, 5).size)
    }

    @Test
    fun `越界索引不回调`() {
        assertEquals(emptyList<Int>(), collect(3, 3, -1, 3))
        assertEquals(emptyList<Int>(), collect(3, 3, 9, 3))
    }

    @Test
    fun `尺寸钳制到奇数且最小 1`() {
        // 传入 0 或 2 都被钳到 1（半径 0）
        assertEquals(listOf(4), collect(3, 3, 4, 0))
        assertEquals(listOf(4), collect(3, 3, 4, 2))
        // 传入超大值不越界，仍被网格裁掉
        assertEquals(9, collect(3, 3, 4, 999).size)
    }
}
