package com.pindou.app.core.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class BackgroundRemoverTest {

    private fun argb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private val white = argb(255, 255, 255)
    private val red = argb(230, 40, 40)
    private val dark = argb(25, 25, 25)
    private val gray = argb(140, 140, 140)
    private val palePink = argb(255, 235, 240)
    private val hotPink = argb(240, 110, 150)

    private fun filled(w: Int, h: Int, color: Int) = IntArray(w * h) { color }

    @Test
    fun `白底中心主体保留，主体外背景被移除`() {
        val w = 15
        val h = 15
        val grid = filled(w, h, white)
        for (y in 5..8) for (x in 5..8) grid[y * w + x] = red

        val mask = BackgroundRemover.detect(grid, w, h, tolerance = 10.0)
        // 主体外围保护带里与背景同色的白格由「纯背景色清理」带走
        assertEquals("实际移除 ${mask.count { it }} / ${w * h}", w * h - 4 * 4, mask.count { it })
        for (y in 5..8) for (x in 5..8) {
            assertFalse("主体格 ($x,$y) 不应被判为背景", mask[y * w + x])
        }
    }

    @Test
    fun `深色背景同样只移除主体外区域`() {
        val w = 11
        val h = 11
        val grid = filled(w, h, dark)
        for (y in 3..7) for (x in 3..7) grid[y * w + x] = red

        val mask = BackgroundRemover.detect(grid, w, h, tolerance = 10.0)
        assertTrue("深色背景应被移除", mask[0])
        assertFalse("红色主体必须保留", mask[5 * w + 5])
    }

    @Test
    fun `主体内部与背景同色的洞必须保留`() {
        val w = 21
        val h = 21
        val grid = filled(w, h, white)
        for (y in 5..15) for (x in 5..15) grid[y * w + x] = red
        grid[10 * w + 10] = white

        val mask = BackgroundRemover.detect(grid, w, h, tolerance = 12.0)
        assertFalse("被主体包住的白洞不应被删", mask[10 * w + 10])
        assertTrue("主体外白底应被删", mask[0])
    }

    @Test
    fun `细黑描边的白主体在低分辨率网格下内部白不被误删`() {
        // 240×240：白底 + 半径 95、4px 粗的黑描边圆，圆内也是白。
        // 目标网格 40×40 时每格 6px，描边不到 1 格宽——单分辨率泛洪会穿线。
        val src = 240
        val pixels = IntArray(src * src) { white }
        val c = 119.5
        for (y in 0 until src) for (x in 0 until src) {
            val d = hypot(x - c, y - c)
            if (d in 91.0..95.0) pixels[y * src + x] = dark
        }
        val w = 40
        val h = 40
        val cells = IntArray(w * h)
        val out = BackgroundRemover.applyToCellsFromPixels(
            cells, pixels, src, src, w, h, tolerance = 12.0, scale = 3,
        )
        // 圆心格（20,20）在主体内部，必须保留为色号 0
        assertEquals(0, out[20 * w + 20])
        assertEquals(0, out[15 * w + 20])
        // 四角是画面外背景，应清空
        assertEquals(-1, out[0])
        assertEquals(-1, out[w - 1])
        val removed = out.count { it == -1 }
        // 圆面积约占 49%，背景约 51%；跨描边的格子按「保留」处理会留下 1 格白边，
        // 允许少量残留，但主体外必须清掉大半
        assertTrue("背景应被大量移除：实际移除 $removed / ${w * h}", removed > w * h * 2 / 5)
    }

    @Test
    fun `贴边淡色花瓣不被背景移除吃掉`() {
        // 白底 + 圆环淡粉/粉色花瓣 + 中心灰色主体
        val w = 25
        val h = 25
        val c = 12
        val grid = filled(w, h, white)
        for (y in 0 until h) for (x in 0 until w) {
            val d = hypot((x - c).toDouble(), (y - c).toDouble())
            if (d in 8.0..9.8) {
                grid[y * w + x] = if ((x + y) % 3 == 0) hotPink else palePink
            }
        }
        for (y in 11..13) for (x in 11..13) grid[y * w + x] = gray

        val mask = BackgroundRemover.detect(grid, w, h, tolerance = 12.0)
        var petals = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (grid[y * w + x] != white) {
                petals++
                assertFalse("花瓣 ($x,$y) 被误删", mask[y * w + x])
            }
        }
        assertTrue("应包含花瓣", petals > 20)
        assertTrue("四角白底应被移除", mask[0] && mask[w - 1] && mask[(h - 1) * w])
        assertTrue("环外背景应移除", mask[12] || mask[w + 1])
    }

    @Test
    fun `纯色图视为无主体，返回空掩码`() {
        val w = 9
        val h = 9
        val mask = BackgroundRemover.detect(filled(w, h, white), w, h, tolerance = 12.0)
        assertTrue("纯色图不应被删光", mask.none { it })
    }

    @Test
    fun `带噪声的白底不会把整图删光`() {
        val w = 9
        val h = 9
        val grid = IntArray(w * h) { i ->
            val d = (i % 9) % 5 - 2
            argb(255 + d, 255 + d, 255 + d)
        }
        val mask = BackgroundRemover.detect(grid, w, h, tolerance = 12.0)
        assertTrue("没有主体时不得整图清空", mask.none { it })
    }

    @Test
    fun `applyToCells 置空背景格，主体格保留色号`() {
        val w = 11
        val h = 11
        val grid = filled(w, h, white)
        for (y in 4..6) for (x in 4..6) grid[y * w + x] = red
        val cells = IntArray(w * h) { 3 }
        val out = BackgroundRemover.applyToCells(cells, grid, w, h, tolerance = 10.0)
        assertEquals(-1, out[0])
        assertEquals(3, out[5 * w + 5])
        assertEquals("不得改动入参", 3, cells[0])
    }

    @Test
    fun `小尺寸网格直接返回空掩码`() {
        val grid = IntArray(4) { white }
        val mask = BackgroundRemover.detect(grid, 2, 2)
        assertTrue(mask.none { it })
    }
}
