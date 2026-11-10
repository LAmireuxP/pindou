package com.pindou.app.core.color

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorConversionsTest {

    @Test
    fun `白色`() {
        val lab = ColorConversions.argbToLab(0xFFFFFFFF.toInt())
        assertEquals(100.0f, lab[0], 0.01f)
        assertEquals(0.0f, lab[1], 0.01f)
        assertEquals(0.0f, lab[2], 0.01f)
    }

    @Test
    fun `黑色`() {
        val lab = ColorConversions.argbToLab(0xFF000000.toInt())
        assertEquals(0.0f, lab[0], 0.01f)
        assertEquals(0.0f, lab[1], 0.01f)
        assertEquals(0.0f, lab[2], 0.01f)
    }

    @Test
    fun `纯红`() {
        // sRGB red 的 D65 Lab 标准值 ≈ (53.24, 80.09, 67.20)
        val lab = ColorConversions.argbToLab(0xFFFF0000.toInt())
        assertEquals(53.24f, lab[0], 0.02f)
        assertEquals(80.09f, lab[1], 0.02f)
        assertEquals(67.20f, lab[2], 0.02f)
    }

    @Test
    fun `灰色亮度正确`() {
        val lab = ColorConversions.argbToLab(0xFF808080.toInt())
        assertEquals(53.59f, lab[0], 0.02f)
        assertEquals(0.0f, lab[1], 0.02f)
        assertEquals(0.0f, lab[2], 0.02f)
    }
}
