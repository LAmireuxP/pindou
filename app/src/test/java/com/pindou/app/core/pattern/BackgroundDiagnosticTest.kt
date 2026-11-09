package com.pindou.app.core.pattern



import org.junit.Test
import java.io.File

/** 诊断：像素画背景移除掩码，打印内外白区是否被误删（非断言） */
class BackgroundDiagnosticTest {

    @Test
    fun `打印花环猫背景掩码`() {
        val dir = File("D:/ChatGPT/conversion_eval_input")
        val raw = dir.resolve("user_cat.rgb")
        if (!raw.isFile) return
        val w = 735
        val h = 658
        val bytes = raw.readBytes()
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            pixels[i] = (0xFF shl 24) or
                ((bytes[3 * i].toInt() and 0xFF) shl 16) or
                ((bytes[3 * i + 1].toInt() and 0xFF) shl 8) or
                (bytes[3 * i + 2].toInt() and 0xFF)
        }
        val art = PixelArtDetector.detect(pixels, w, h) ?: return
        val sample = ImagePreprocessor.sample(pixels, w, h, art.artW, art.artH, FilterLevel.NONE)
        val mask = BackgroundRemover.detect(sample.argb, art.artW, art.artH, 12.0)
        val n = mask.size
        println("BG removed=${mask.count { it }} total=$n art=${art.artW}x${art.artH}")
        // ASCII：'#' = 删除，'.' = 保留，'@' = 保留且是白色（内部白被保住会显示 @）
        val sb = StringBuilder()
        for (y in 0 until art.artH) {
            for (x in 0 until art.artW) {
                val i = y * art.artW + x
                val c = sample.argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                sb.append(
                    when {
                        mask[i] -> '#'
                        r > 235 && g > 235 && b > 235 -> '@'
                        else -> '.'
                    },
                )
            }
            sb.append('\n')
        }
        val out = File("D:/ChatGPT/probe_out/bg_mask.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
        println("BG mask -> " + out.absolutePath)

        // 外圈 3 格以内应尽量删除，花环内部（画心 20%~80% 区域）白格应保留
        var innerWhite = 0
        var innerRemoved = 0
        for (y in art.artH * 25 / 100 until art.artH * 75 / 100) {
            for (x in art.artW * 25 / 100 until art.artW * 75 / 100) {
                val i = y * art.artW + x
                val c = sample.argb[i]
                if ((c and 0xFFFFFF) == 0xFFFFFF || ((c shr 16) and 0xFF) > 245) {
                    innerWhite++
                    if (mask[i]) innerRemoved++
                }
            }
        }
        println("BG inner-white=$innerWhite removed=$innerRemoved")
    }
}
