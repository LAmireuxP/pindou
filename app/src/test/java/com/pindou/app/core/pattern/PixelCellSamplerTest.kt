package com.pindou.app.core.pattern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelCellSamplerTest {

    /** 与采样器一致的桶中心：每通道丢低 3 位后补 0x04 */
    private fun center(r: Int, g: Int, b: Int): Int {
        val cr = ((r and 0xF8) or 0x04).coerceAtMost(255)
        val cg = ((g and 0xF8) or 0x04).coerceAtMost(255)
        val cb = ((b and 0xF8) or 0x04).coerceAtMost(255)
        return (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
    }

    private fun solid(w: Int, h: Int, argb: Int) = IntArray(w * h) { argb }

    @Test
    fun `纯色块九点直接通过`() {
        val p = solid(12, 12, 0xFFE040A0.toInt())
        val s = PixelCellSampler.sample(p, 12, 12, 0.0, 0.0, 12.0, 12.0, bucketCenter = true)
        assertEquals(center(0xE0, 0x40, 0xA0), s.argb)
        assertEquals(1f, s.confidence, 0.0001f)
    }

    @Test
    fun `跨色边界格只落到其中一侧`() {
        val w = 8
        val h = 8
        val red = 0xFFE02020.toInt()
        val blue = 0xFF2040E0.toInt()
        val p = IntArray(w * h) { i -> if (i % w < 4) red else blue }
        val s = PixelCellSampler.sample(p, w, h, 0.0, 0.0, 8.0, 8.0, bucketCenter = true)
        val redCenter = center(0xE0, 0x20, 0x20)
        val blueCenter = center(0x20, 0x40, 0xE0)
        assertTrue(
            "跨色格不能生成中间色，实际=${Integer.toHexString(s.argb)}",
            s.argb == redCenter || s.argb == blueCenter,
        )
        assertTrue(s.confidence in 0.5f..1f)
    }

    @Test
    fun `小数块长按边界取整不串色`() {
        // 50px / 4 格 = 12.5px：第 0 格覆盖 0..12，第 1 格从 12.5 起算到 13
        val w = 50
        val h = 4
        val colors = intArrayOf(
            0xFFE02020.toInt(),
            0xFF20E020.toInt(),
            0xFF2020E0.toInt(),
            0xFFE0E020.toInt(),
        )
        val p = IntArray(w * h) { i ->
            val x = i % w
            colors[(x / 12.5).toInt().coerceIn(0, 3)]
        }
        for (i in 0 until 4) {
            val s = PixelCellSampler.sample(
                p, w, h, i * 12.5, 0.0, (i + 1) * 12.5, 4.0, bucketCenter = true,
            )
            assertTrue("第 $i 格串色: ${Integer.toHexString(s.argb)}", s.confidence >= 0.6f)
        }
    }

    @Test
    fun `校准网格一比一还原粉块且格内白底不渗色`() {
        val block = 10
        val cols = 6
        val rows = 6
        val w = cols * block
        val h = rows * block
        val pink = 0xFFF060A0.toInt()
        val white = 0xFFFFFFFF.toInt()
        val p = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x < block && y < block) pink else white
        }
        val sample = ImagePreprocessor.sample(
            p, w, h, cols, rows, FilterLevel.NONE, exactArt = true, pixelGridHint = cols to rows,
        )
        assertNotNull(sample.art)
        assertTrue(sample.exact)
        assertEquals(center(0xF0, 0x60, 0xA0), sample.argb[0])
        assertEquals(center(0xFF, 0xFF, 0xFF), sample.argb[1])
        assertEquals(center(0xFF, 0xFF, 0xFF), sample.argb[cols])
        assertEquals(cols * rows, sample.argb.size)
    }

    @Test
    fun `六十乘六十校准网格逐格取色`() {
        val block = 8
        val cols = 60
        val rows = 60
        val w = cols * block
        val h = rows * block
        val p = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = (x / block) * 4
            val g = (y / block) * 4
            (0xFF shl 24) or (r shl 16) or (g shl 8) or 0x40
        }
        val sample = ImagePreprocessor.sample(
            p, w, h, cols, rows, FilterLevel.NONE, exactArt = true, pixelGridHint = cols to rows,
        )
        assertTrue(sample.exact)
        assertEquals(3600, sample.argb.size)
        assertEquals(center(0x00, 0x00, 0x40), sample.argb[0])
        assertEquals(center(236, 236, 0x40), sample.argb[59 * cols + 59])
    }
}
