package com.pindou.app.core.pattern

import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.palette.PaletteJson
import org.junit.Assert.assertTrue
import org.junit.Test

class FloydSteinbergDithererTest {

    private val bwPalette: BeadPalette = PaletteJson.parse(
        """
        {
          "brand": "BW", "brandLabel": "黑白", "beadSize": "5mm", "version": 1,
          "colors": [
            {"code": "W", "hex": "#FFFFFF"},
            {"code": "K", "hex": "#101010"}
          ]
        }
    """.trimIndent(),
    )

    @Test
    fun `灰色图抖动后黑白混合`() {
        val w = 8; val h = 8
        val gray = (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128
        val grid = IntArray(w * h) { gray }
        val out = FloydSteinbergDitherer.dither(
            grid, w, h,
            FloydSteinbergDitherer.FromPalette(bwPalette),
        )
        val wIndex = bwPalette.indexOfCode("W")
        val kIndex = bwPalette.indexOfCode("K")
        val whites = out.count { it == wIndex }
        val blacks = out.count { it == kIndex }
        assertTrue("应出现白色 $whites", whites > 0)
        assertTrue("应出现黑色 $blacks", blacks > 0)
    }
}
