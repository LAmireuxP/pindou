package com.pindou.app.core.palette

import com.pindou.app.core.color.ColorConversions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteJsonTest {

    private val labWhite = ColorConversions.argbToLab(0xFFFFFFFF.toInt())
    private val labRed = ColorConversions.argbToLab(0xFFFF0000.toInt())
    private val labGreen = ColorConversions.argbToLab(0xFF00FF00.toInt())

    private val sampleJson = """
        {
          "brand": "TEST",
          "brandLabel": "测试色卡",
          "beadSize": "5mm",
          "version": 1,
          "source": "unit-test",
          "colors": [
            {"code": "001", "hex": "#FFFFFF", "name": "白"},
            {"code": "002", "hex": "#FF0000", "name": "红"},
            {"code": "003", "hex": "#00FF00"}
          ]
        }
    """.trimIndent()

    @Test
    fun `解析与 Lab 预计算`() {
        val palette = PaletteJson.parse(sampleJson)
        assertEquals("TEST", palette.brand)
        assertEquals(BeadSize.MM5, palette.beadSize)
        assertEquals(3, palette.colors.size)
        assertEquals(0xFFFFFFFF.toInt(), palette.colors[0].argb)
        assertEquals("白", palette.colors[0].name)
    }

    @Test
    fun `最近色查找`() {
        val palette = PaletteJson.parse(sampleJson)
        assertEquals(0, palette.nearestIndex(labWhite))
        assertEquals(1, palette.nearestIndex(labRed))
        assertEquals(2, palette.nearestIndex(labGreen))
    }

    @Test
    fun `排除色号后跳过`() {
        val palette = PaletteJson.parse(sampleJson)
        val idx = palette.nearestIndex(labWhite, excludedCodes = setOf("001"))
        assertTrue("排除 001 后不应再命中 001，实际 idx=$idx", idx != 0)
    }

    @Test
    fun `全部排除时返回 -1`() {
        val palette = PaletteJson.parse(sampleJson)
        assertEquals(-1, palette.nearestIndex(labWhite, excludedCodes = setOf("001", "002", "003")))
    }

    @Test
    fun `hex 解析边界`() {
        assertEquals(0xFF123456.toInt(), PaletteJson.parseHex("#123456"))
        assertEquals(0xFFABCDEF.toInt(), PaletteJson.parseHex("ABCDEF"))
        assertNull(PaletteJson.parseHex("#12345"))
        assertNull(PaletteJson.parseHex("#12345G"))
        assertNull(PaletteJson.parseHex(""))
    }

    @Test
    fun `豆子规格解析`() {
        assertEquals(BeadSize.MM5, BeadSize.fromTag("5mm"))
        assertEquals(BeadSize.MM2_6, BeadSize.fromTag("2.6mm"))
        assertEquals(BeadSize.MM5, BeadSize.fromTag("unknown"))
    }
}
