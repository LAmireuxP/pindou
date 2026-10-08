package com.pindou.app.core.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarCodeMergerTest {

    // 三个彼此非常接近的深藏青（ΔE 均 < 3）+ 一个明显不同的红
    private val NAVY_A = 0xFF1A2438.toInt()
    private val NAVY_B = 0xFF1B2539.toInt()
    private val NAVY_C = 0xFF1C263A.toInt()
    private val RED = 0xFFFF2A2A.toInt()
    private val palette = listOf(NAVY_A, NAVY_B, NAVY_C, RED)

    @Test
    fun `近同色号并入常用方`() {
        // 97 格 A + 3 格 B（1×3 的 B 条，不是孤立点，清理器不会处理）
        val w = 10
        val h = 10
        val cells = IntArray(w * h) { 0 }
        cells[0] = 1; cells[1] = 1; cells[2] = 1
        val out = SimilarCodeMerger.merge(cells, palette, 3.0)
        assertTrue(out.none { it == 1 })
        assertEquals(100, out.count { it == 0 })
    }

    @Test
    fun `色差大的色号不合并`() {
        val cells = IntArray(100) { 0 }
        cells[0] = 3; cells[1] = 3 // 少量红
        val out = SimilarCodeMerger.merge(cells, palette, 3.0)
        assertEquals(2, out.count { it == 3 })
        assertEquals(98, out.count { it == 0 })
    }

    @Test
    fun `链式合并收敛到常用方`() {
        // A 占多数，B、C 各少量；B/C 与 A 均近同 → 最终全部并入 A
        val cells = IntArray(100) { 0 }
        cells[0] = 1; cells[1] = 1
        cells[2] = 2; cells[3] = 2
        val out = SimilarCodeMerger.merge(cells, palette, 3.0)
        assertTrue(out.none { it == 1 || it == 2 })
        assertEquals(100, out.count { it == 0 })
    }

    @Test
    fun `阈值为零时关闭合并`() {
        val cells = IntArray(100) { 0 }
        cells[0] = 1; cells[1] = 1
        val out = SimilarCodeMerger.merge(cells, palette, 0.0)
        assertEquals(2, out.count { it == 1 })
    }

    @Test
    fun `空格不参与合并`() {
        val cells = IntArray(100) { 0 }
        cells[0] = -1
        val out = SimilarCodeMerger.merge(cells, palette, 3.0)
        assertEquals(-1, out[0])
    }
}
