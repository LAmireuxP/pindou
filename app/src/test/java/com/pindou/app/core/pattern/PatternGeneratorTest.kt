package com.pindou.app.core.pattern

import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.palette.PaletteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternGeneratorTest {

    private val testPalette: BeadPalette = PaletteJson.parse(
        """
        {
          "brand": "T", "brandLabel": "测试", "beadSize": "5mm", "version": 1,
          "colors": [
            {"code": "W", "hex": "#FFFFFF"},
            {"code": "K", "hex": "#000000"},
            {"code": "R", "hex": "#FF0000"},
            {"code": "G", "hex": "#00CC00"},
            {"code": "B", "hex": "#0000FF"}
          ]
        }
    """.trimIndent(),
    )

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `四象限图生成正确色号`() {
        // 40×40 图，四象限纯色 → 2×2 网格
        val w = 40; val h = 40
        val red = argb(255, 0, 0)
        val green = argb(0, 204, 0)
        val blue = argb(0, 0, 255)
        val white = argb(255, 255, 255)
        val pixels = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                x < 20 && y < 20 -> red
                x >= 20 && y < 20 -> green
                x < 20 && y >= 20 -> blue
                else -> white
            }
        }
        val result = PatternGenerator.generate(
            pixels, w, h,
            GeneratorOptions(gridW = 2, gridH = 2, cleanupEnabled = false),
            testPalette,
        )
        val paletteIndexOf = { code: String -> testPalette.indexOfCode(code) }
        assertEquals(paletteIndexOf("R"), result.cells[0])
        assertEquals(paletteIndexOf("G"), result.cells[1])
        assertEquals(paletteIndexOf("B"), result.cells[2])
        assertEquals(paletteIndexOf("W"), result.cells[3])
        assertEquals(2, result.width)
    }

    @Test
    fun `排除色号后不出现`() {
        val w = 10; val h = 10
        val red = argb(255, 0, 0)
        val pixels = IntArray(w * h) { red }
        val result = PatternGenerator.generate(
            pixels, w, h,
            GeneratorOptions(gridW = 2, gridH = 2, excludedCodes = setOf("R"), cleanupEnabled = false),
            testPalette,
        )
        val rIndex = testPalette.indexOfCode("R")
        assertTrue(result.cells.none { it == rIndex })
    }

    @Test
    fun `用量统计正确`() {
        val w = 20; val h = 20
        val red = argb(255, 0, 0)
        val white = argb(255, 255, 255)
        val pixels = IntArray(w * h) { i -> if (i % w < 10) red else white }
        val result = PatternGenerator.generate(
            pixels, w, h,
            GeneratorOptions(gridW = 2, gridH = 1, cleanupEnabled = false),
            testPalette,
        )
        val usage = result.usage().toMap()
        val rIndex = testPalette.indexOfCode("R")
        val wIndex = testPalette.indexOfCode("W")
        assertEquals(1, usage[rIndex])
        assertEquals(1, usage[wIndex])
    }

    @Test
    fun `性能冒烟 512x512 到 29x29`() {
        val w = 512; val h = 512
        val pixels = IntArray(w * h) { i -> argb((i * 7) % 256, (i * 13) % 256, (i * 31) % 256) }
        val result = PatternGenerator.generate(
            pixels, w, h,
            GeneratorOptions(gridW = 29, gridH = 29),
            testPalette,
        )
        assertEquals(29 * 29, result.cells.size)
        assertTrue("生成耗时 ${result.elapsedMs}ms 超出预算", result.elapsedMs < 3000)
    }
}
