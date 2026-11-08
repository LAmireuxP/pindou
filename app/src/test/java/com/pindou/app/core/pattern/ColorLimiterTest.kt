package com.pindou.app.core.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorLimiterTest {

    private val paletteArgbs = listOf(
        0xFFE53935.toInt(), // 0 红
        0xFFFF7043.toInt(), // 1 橙（近红）
        0xFF43A047.toInt(), // 2 绿
        0xFF1E88E5.toInt(), // 3 蓝
        0xFFFDD835.toInt(), // 4 黄
        0xFF8E24AA.toInt(), // 5 紫
    )

    @Test
    fun `色号数压到上限以内`() {
        // 6 种色号 → 限 3
        val cells = intArrayOf(0, 1, 1, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5)
        val out = ColorLimiter.limit(cells, paletteArgbs, 3)
        val distinct = out.filter { it >= 0 }.distinct()
        assertTrue("限色后仍剩 ${distinct.size} 种", distinct.size <= 3)
        // 非空格总数守恒
        assertEquals(cells.count { it >= 0 }, out.count { it >= 0 })
    }

    @Test
    fun `已达标时不改任何格子`() {
        val cells = intArrayOf(0, 0, 2, 2, 3)
        val out = ColorLimiter.limit(cells, paletteArgbs, 3)
        assertTrue(cells.contentEquals(out))
    }

    @Test
    fun `用量最少的色号先被合并`() {
        // 色号 1 只出现 1 次（红色系近邻 0），限 3 色 → 1 应并入 0 而不是大面积色块被拆
        val cells = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 2, 2, 2, 3, 3, 3, 3)
        val out = ColorLimiter.limit(cells, paletteArgbs, 3)
        val distinct = out.filter { it >= 0 }.distinct().toSet()
        assertEquals(3, distinct.size)
        assertTrue("孤品红应并入主红", 0 in distinct && 1 !in distinct)
    }

    @Test
    fun `近色号优先合并`() {
        // 只留 2 种：橙(1) 与红(0) ΔE 最近，橙应并入红而不是绿
        val cells = intArrayOf(0, 0, 0, 1, 1, 2, 2, 2)
        val out = ColorLimiter.limit(cells, paletteArgbs, 2)
        val distinct = out.filter { it >= 0 }.distinct().toSet()
        assertEquals(2, distinct.size)
        assertTrue("橙应并入红", 0 in distinct && 1 !in distinct)
    }

    @Test
    fun `确定性`() {
        val cells = intArrayOf(0, 1, 2, 3, 4, 5, 0, 1, 2, 3, 4, 5)
        val a = ColorLimiter.limit(cells, paletteArgbs, 3)
        val b = ColorLimiter.limit(cells, paletteArgbs, 3)
        assertTrue(a.contentEquals(b))
    }
}
