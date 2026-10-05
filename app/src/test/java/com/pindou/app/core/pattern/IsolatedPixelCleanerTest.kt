package com.pindou.app.core.pattern

import org.junit.Assert.assertEquals
import org.junit.Test

class IsolatedPixelCleanerTest {

    private val RED = 0
    private val ORPHAN = 7

    private fun field(size: Int, fill: Int) = IntArray(size) { fill }

    @Test
    fun `孤立像素被替换为邻域众数`() {
        // 5×5 全红 + 中心 1 个孤立色
        val w = 5; val h = 5
        val cells = field(w * h, RED)
        cells[2 * w + 2] = ORPHAN
        val out = IsolatedPixelCleaner.clean(cells, w, h)
        assertEquals(RED, out[2 * w + 2])
    }

    @Test
    fun `出现次数多的颜色不被清理`() {
        // 红色棋盘格里嵌入一个 3×3 的蓝色块（9 格 > 阈值 3），不应被清理
        val w = 7; val h = 7
        val BLUE = 3
        val cells = field(w * h, RED)
        for (y in 2..4) for (x in 2..4) cells[y * w + x] = BLUE
        val out = IsolatedPixelCleaner.clean(cells, w, h)
        assertEquals(BLUE, out[3 * w + 3])
    }

    @Test
    fun `锁定格不被清理`() {
        val w = 5; val h = 5
        val cells = field(w * h, RED)
        val orphanIdx = 2 * w + 2
        cells[orphanIdx] = ORPHAN
        val out = IsolatedPixelCleaner.clean(cells, w, h, locked = setOf(orphanIdx))
        assertEquals(ORPHAN, out[orphanIdx])
    }

    @Test
    fun `空格不参与清理`() {
        val w = 5; val h = 5
        val cells = field(w * h, RED)
        cells[2 * w + 2] = -1
        val out = IsolatedPixelCleaner.clean(cells, w, h)
        assertEquals(-1, out[2 * w + 2])
    }
}
