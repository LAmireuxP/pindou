package com.pindou.app.core.export

import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.palette.PaletteJson
import com.pindou.app.core.pattern.PatternResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRendererTest {

    private val palette: BeadPalette = PaletteJson.parse(
        """
        {
          "brand": "T", "brandLabel": "测试", "beadSize": "5mm", "version": 1,
          "colors": [
            {"code": "W", "hex": "#FFFFFF"},
            {"code": "K", "hex": "#000000"},
            {"code": "R", "hex": "#FF0000"}
          ]
        }
    """.trimIndent(),
    )

    private fun makeResult(): PatternResult {
        // 3×2：白 白 红 / 黑 红 白
        val w = palette.indexOfCode("W")
        val k = palette.indexOfCode("K")
        val r = palette.indexOfCode("R")
        return PatternResult(3, 2, intArrayOf(w, w, r, k, r, w), palette, 0)
    }

    @Test
    fun `彩色版尺寸含边线与标题`() {
        val bmp = ExportRenderer.renderPattern(
            makeResult(),
            ExportRenderer.RenderOptions(cellPx = 32, titleHeightPx = 48),
        )
        assertEquals(3 * 32 + 1, bmp.width)
        assertEquals(2 * 32 + 1 + 48, bmp.height) // 含预留标题栏
    }

    @Test
    fun `彩色版格子颜色正确`() {
        val bmp = ExportRenderer.renderPattern(
            makeResult(),
            ExportRenderer.RenderOptions(cellPx = 32, showCodes = false, showGrid = false),
        )
        // 左上格（白）
        assertEquals(0xFFFFFFFF.toInt(), bmp.pixels[0])
        // 第 3 格（红）左上角
        assertEquals(0xFFFF0000.toInt(), bmp.pixels[2 * 32])
        // 第二行第一格（黑）
        assertEquals(0xFF000000.toInt(), bmp.pixels[32 * bmp.width])
    }

    @Test
    fun `符号版每色唯一标记且含对照表`() {
        val bmp = ExportRenderer.renderSymbol(
            makeResult(),
            ExportRenderer.RenderOptions(cellPx = 32),
        )
        // 尺寸：内容 + 标题 + 对照表
        assertTrue(bmp.width == 3 * 32 + 1)
        assertTrue(bmp.height > 2 * 32 + 32)
        // 图案区不应出现彩色填充（黑白打印友好）；对照表的色块示意是刻意保留的
        val contentH = 2 * 32 + 1 + 32
        assertTrue(bmp.pixels.take(contentH * bmp.width).none { it == 0xFFFF0000.toInt() })
        // 应存在黑色像素（文字/网格）
        assertTrue(bmp.pixels.any { it == 0xFF000000.toInt() || it == 0xFF333333.toInt() })
    }

    @Test
    fun `空格不绘制`() {
        val result = PatternResult(2, 1, intArrayOf(-1, palette.indexOfCode("R")), palette, 0)
        val bmp = ExportRenderer.renderPattern(
            result,
            ExportRenderer.RenderOptions(cellPx = 16, showCodes = false, showGrid = false),
        )
        // 空格保持背景色
        assertEquals(0xFFFFFAF0.toInt(), bmp.pixels[0])
        assertEquals(0xFFFF0000.toInt(), bmp.pixels[16])
    }

    @Test
    fun `点阵字体覆盖色号字符`() {
        // 现有色卡色号用到的字符都要能渲染（否则导出会缺字）
        val chars = setOf(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            '-', ':', '.', '/', '#', ' ',
        )
        val missing = chars.filter { ch ->
            // 渲染单字符，检查是否产生非背景像素（空格除外）
            val size = 64
            val pixels = IntArray(size * size) { 0xFFFFFAF0.toInt() }
            ExportRenderer.drawTextLine(pixels, size, size, ch.toString(), 4, 4, 0xFF000000.toInt(), 1)
            pixels.none { it != 0xFFFFFAF0.toInt() } && ch != ' '
        }
        assertTrue("缺少字形: $missing", missing.isEmpty())
    }
}