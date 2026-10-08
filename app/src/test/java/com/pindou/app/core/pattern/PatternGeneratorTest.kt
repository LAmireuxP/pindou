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

    @Test
    fun `先聚类后映射使色号数被自适应 K 封顶`() {
        // 灰阶渐变源图（每格颜色都不同）+ 20 个近似等距的灰阶色卡：
        // 16×16 网格的自适应 K = 19 → 最终用色数必须 ≤ 19（旧管线会用到全部 20 色）
        val palette = PaletteJson.parse(
            buildString {
                append("""{"brand":"G","brandLabel":"灰阶","beadSize":"5mm","version":1,"colors":[""")
                (0 until 20).joinTo(this, ",") { i ->
                    val v = 13 + i * 12
                    """{"code":"G$i","hex":"#${v.toString(16).padStart(2, '0').repeat(3)}"}"""
                }
                append("]}")
            },
        )
        val srcW = 160; val srcH = 160
        val pixels = IntArray(srcW * srcH) { i ->
            val x = i % srcW
            val g = (x * 255 / srcW) + ((i * 11) % 7) - 3 // 横向渐变 + 微噪声
            val v = g.coerceIn(0, 255)
            argb(v, v, v)
        }
        val result = PatternGenerator.generate(
            pixels, srcW, srcH,
            GeneratorOptions(gridW = 16, gridH = 16, cleanupEnabled = false, similarCodeMaxDeltaE = 0.0),
            palette,
        )
        val distinctCodes = result.cells.distinct().size
        val expectedCap = KmeansColorReducer.adaptiveClusterCount(16 * 16)
        assertTrue("用色 $distinctCodes 超过自适应 K $expectedCap", distinctCodes <= expectedCap)
        assertTrue("用色 $distinctCodes 少得反常", distinctCodes >= 5)
        // 确定性：同一输入两次生成结果完全一致
        val again = PatternGenerator.generate(
            pixels, srcW, srcH,
            GeneratorOptions(gridW = 16, gridH = 16, cleanupEnabled = false, similarCodeMaxDeltaE = 0.0),
            palette,
        )
        assertTrue(result.cells.contentEquals(again.cells))
    }

    @Test
    fun `小面积高对比细节穿透管线保留`() {
        // 200×200 皮肤底 + 中央 8×8 眼睛（占一个 20×20 采样块的 16% ≥ 15%）
        // → 降采样翻转 + 聚类救援 + 清理保护，最终图纸必须含有眼睛色号
        val w = 200; val h = 200
        val skin = argb(235, 195, 175)
        val eye = argb(15, 35, 95)
        val pixels = IntArray(w * h) { skin }
        // 眼睛完整落在一个 20×20 采样块内（x 100-119, y 100-119），8×8=16% ≥ 15%
        for (y in 106 until 114) for (x in 106 until 114) pixels[y * w + x] = eye
        val palette = PaletteJson.parse(
            """
            {"brand":"F","brandLabel":"脸","beadSize":"5mm","version":1,"colors":[
              {"code":"S","hex":"#EBC3AF"},
              {"code":"E","hex":"#0F235F"},
              {"code":"W","hex":"#FFFFFF"}
            ]}
            """.trimIndent(),
        )
        val result = PatternGenerator.generate(
            pixels, w, h,
            GeneratorOptions(gridW = 10, gridH = 10),
            palette,
        )
        val eyeIdx = palette.indexOfCode("E")
        assertTrue("眼睛色号应在图纸中保留", result.cells.contains(eyeIdx))
    }
}
