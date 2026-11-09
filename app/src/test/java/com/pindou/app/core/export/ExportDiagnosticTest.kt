package com.pindou.app.core.export

import com.pindou.app.core.palette.PaletteJson
import com.pindou.app.core.pattern.FilterLevel
import com.pindou.app.core.pattern.GeneratorOptions
import com.pindou.app.core.pattern.PatternGenerator
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/** 诊断（非断言）：用真机同一张图 + 同一套参数在 JVM 渲染导出图，用于定位导出错位。 */
class ExportDiagnosticTest {

    @Test
    fun `渲染 user_cat 导出图`() {
        val w = 735
        val h = 658
        val raw = File("D:/ChatGPT/conversion_eval_input/user_cat.rgb")
        if (!raw.isFile) return
        val bytes = raw.readBytes()
        val pixels = IntArray(w * h) { i ->
            (0xFF shl 24) or
                ((bytes[3 * i].toInt() and 0xFF) shl 16) or
                ((bytes[3 * i + 1].toInt() and 0xFF) shl 8) or
                (bytes[3 * i + 2].toInt() and 0xFF)
        }
        val palette = PaletteJson.parse(
            File("D:/ChatGPT/pindou/app/src/main/assets/palettes/mard.json").readText(),
        )
        val result = PatternGenerator.generate(
            pixels, w, h,
            GeneratorOptions(gridW = 56, gridH = 50, filterLevel = FilterLevel.LOW),
            palette,
        )
        println("GEN ${result.width}x${result.height} colors=${result.usage().size}")
        val rendered = ExportRenderer.renderPattern(
            result,
            ExportRenderer.RenderOptions(cellPx = 60, showCodes = false, showGrid = true, titleHeightPx = 108),
        )
        val out = File("D:/ChatGPT/probe_out/local_art.ppm")
        out.parentFile.mkdirs()
        BufferedOutputStream(FileOutputStream(out)).use { o ->
            o.write("P6\n${rendered.width} ${rendered.height}\n255\n".toByteArray())
            val row = ByteArray(rendered.width * 3)
            for (y in 0 until rendered.height) {
                for (x in 0 until rendered.width) {
                    val p = rendered.pixels[y * rendered.width + x]
                    row[x * 3] = ((p shr 16) and 0xFF).toByte()
                    row[x * 3 + 1] = ((p shr 8) and 0xFF).toByte()
                    row[x * 3 + 2] = (p and 0xFF).toByte()
                }
                o.write(row)
            }
        }
        println("WROTE ${out.absolutePath} ${rendered.width}x${rendered.height}")
    }
}
