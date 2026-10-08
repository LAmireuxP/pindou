package com.pindou.app.core.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSimplifierTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `原图模式不改像素`() {
        val w = 8; val h = 8
        val px = IntArray(w * h) { argb(100, 150, 200) }
        val out = ContentSimplifier.simplify(px, w, h, ContentSimplifier.Mode.OFF)
        assertTrue(out.contentEquals(px))
    }

    @Test
    fun `卡通化块内噪点被抹平`() {
        // 8×8 单块（tile=8），块内全红 + 4 个噪声像素
        val w = 8; val h = 8
        val red = argb(200, 30, 30)
        val px = IntArray(w * h) { red }
        px[0] = argb(210, 40, 40)   // 与红同 4-bit 桶（高 4 位相同）
        px[1] = argb(205, 35, 35)
        px[2] = argb(208, 38, 38)
        px[3] = argb(206, 33, 33)
        val out = ContentSimplifier.simplify(px, w, h, ContentSimplifier.Mode.CARTOON, tileSize = 8)
        // 整块应被替换为主导色均值（红桶均值），噪点消失
        assertTrue(out.all { it == out[0] })
        // 主导色应接近原红（均值微偏）
        val r = (out[0] shr 16) and 0xFF
        assertTrue("主导色红通道 $r 偏离过多", r in 200..215)
    }

    @Test
    fun `卡通化保留跨块边界`() {
        // 16×16，左半红右半蓝，tile=8 → 两个块，颜色不同应保持
        val w = 16; val h = 16
        val red = argb(200, 30, 30)
        val blue = argb(30, 30, 200)
        val px = IntArray(w * h) { i -> if (i % w < 8) red else blue }
        val out = ContentSimplifier.simplify(px, w, h, ContentSimplifier.Mode.CARTOON, tileSize = 8)
        // 左块全红、右块全蓝（边界保留）
        assertTrue(out.indices.all { i ->
            val x = i % w
            if (x < 8) out[i] == out[0] else out[i] == out[8]
        })
        assertEquals("左右块主导色应不同", out[0], red)
        assertEquals(out[8], blue)
    }

    @Test
    fun `边缘增强模式加深跨块边界`() {
        val w = 16; val h = 16
        val red = argb(200, 30, 30)
        val blue = argb(30, 30, 200)
        val px = IntArray(w * h) { i -> if (i % w < 8) red else blue }
        val out = ContentSimplifier.simplify(px, w, h, ContentSimplifier.Mode.EDGE, tileSize = 8, edgeStrength = 40)
        // 红块右缘（x=7）与蓝块相邻 → 跨块边界 → 加深为暗色
        val dark = (0xFF shl 24) or (40 shl 16) or (40 shl 8) or 40
        assertEquals(dark, out[0 * w + 7])
        // 块内部应仍是原主导色（红块 x=0、蓝块 x=8）
        assertEquals(red, out[0 * w + 0])
        assertEquals(blue, out[0 * w + 8])
    }

    @Test
    fun `tileSize 钳制在合法范围`() {
        val px = IntArray(4) { argb(100, 100, 100) }
        // tileSize 越界不应崩溃
        ContentSimplifier.simplify(px, 2, 2, ContentSimplifier.Mode.CARTOON, tileSize = 0)
        ContentSimplifier.simplify(px, 2, 2, ContentSimplifier.Mode.CARTOON, tileSize = 999)
    }
}
