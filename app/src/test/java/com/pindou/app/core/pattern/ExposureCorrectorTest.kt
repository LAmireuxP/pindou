package com.pindou.app.core.pattern

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureCorrectorTest {

    private fun medianLuma(pixels: IntArray): Int {
        val v = pixels.map { p ->
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r * 77 + g * 150 + b * 29) shr 8
        }.sorted()
        return v[v.size / 2]
    }

    @Test
    fun `欠曝图提亮后中位亮度接近目标`() {
        // 模拟暗光照片：亮度集中在 15~45
        val w = 64; val h = 64
        val pixels = IntArray(w * h) { i ->
            val v = 15 + (i * 31) % 31
            (0xFF shl 24) or (v shl 16) or ((v * 9 / 10) shl 8) or (v * 8 / 10)
        }
        val out = ExposureCorrector.correct(pixels, w, h)
        val after = medianLuma(out)
        assertTrue("提亮后中位 $after 未接近目标 115", after in 95..135)
        // 不改变尺寸
        assertEquals(pixels.size, out.size)
    }

    @Test
    fun `正常曝光图原样返回`() {
        val w = 32; val h = 32
        val pixels = IntArray(w * h) { i ->
            val v = 80 + (i * 13) % 120 // 中位 ~140
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = ExposureCorrector.correct(pixels, w, h)
        assertArrayEquals(pixels, out)
    }

    @Test
    fun `修正确定性`() {
        val w = 32; val h = 32
        val pixels = IntArray(w * h) { i ->
            val v = 10 + (i * 17) % 25
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val a = ExposureCorrector.correct(pixels, w, h)
        val b = ExposureCorrector.correct(pixels, w, h)
        assertArrayEquals(a, b)
    }

    @Test
    fun `提亮保持色相方向`() {
        // 暗红色块提亮后仍应是"偏红"（R 通道仍为最大）
        val w = 16; val h = 16
        val pixels = IntArray(w * h) { (0xFF shl 24) or (60 shl 16) or (20 shl 8) or 18 }
        val out = ExposureCorrector.correct(pixels, w, h)
        val p = out[0]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        assertTrue("提亮后 R=$r 应仍大于 G=$g", r > g)
        assertTrue("R 通道应被提亮", r > 60)
    }

}
