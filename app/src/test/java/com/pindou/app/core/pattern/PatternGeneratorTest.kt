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
    fun `横向渐变输出稳定且可复现`() {
        // 灰阶渐变 + 20 个近似等距灰阶色卡：采样用面积平均，
        // 相邻格子只会在色号边界处跳变，同一输入两次结果必须一致。
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
        assertTrue("用色 $distinctCodes 超过色卡色数", distinctCodes <= 20)
        assertTrue("用色 $distinctCodes 少得反常", distinctCodes >= 5)
        // 横向渐变：色号应单调推进，来回翻转（方向反复）只能是个别边界
        for (y in 0 until 16) {
            var prevDir = 0
            var reversals = 0
            for (x in 2 until 16) {
                val d = result.cells[y * 16 + x] - result.cells[y * 16 + x - 1]
                val dir = if (d > 0) 1 else if (d < 0) -1 else 0
                if (dir != 0 && prevDir != 0 && dir != prevDir) reversals++
                if (dir != 0) prevDir = dir
            }
            assertTrue("第 $y 行方向反转 $reversals 次，噪点过多", reversals <= 2)
        }
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
        // 200×200 皮肤底 + 中央 10×10 眼睛：20×20 网格时恰好占满一格，
        // 细节掩码会锁定它，众数滤波和小区域合并都不得抹掉。
        val w = 200; val h = 200
        val skin = argb(235, 195, 175)
        val eye = argb(15, 35, 95)
        val pixels = IntArray(w * h) { skin }
        // 眼睛完整落在 (100..109, 100..109) 这一个 10×10 采样格内
        for (y in 100 until 110) for (x in 100 until 110) pixels[y * w + x] = eye
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
            GeneratorOptions(gridW = 20, gridH = 20),
            palette,
        )
        val eyeIdx = palette.indexOfCode("E")
        assertTrue("眼睛色号应在图纸中保留", result.cells.contains(eyeIdx))
    }
}
