package com.pindou.app.core.pattern

import org.junit.Test
import java.io.File

/**
 * 诊断（非断言）：对评测集里的像素画原图跑检测，打印块尺寸/还原网格。
 * 输入不存在时自动跳过，不影响 CI。
 */
class PixelArtDiagnosticTest {

    @Test
    fun `打印像素画检测结果`() {
        val dir = File("D:/ChatGPT/conversion_eval_input")
        val manifest = dir.resolve("manifest.txt")
        if (!manifest.isFile) return
        for (line in manifest.readLines()) {
            val p = line.split(",")
            if (p.size != 3) continue
            val name = p[0]
            val w = p[1].toInt()
            val h = p[2].toInt()
            val raw = dir.resolve("$name.rgb")
            if (!raw.isFile) continue
            val bytes = raw.readBytes()
            val pixels = IntArray(w * h)
            for (i in pixels.indices) {
                pixels[i] = (0xFF shl 24) or
                    ((bytes[3 * i].toInt() and 0xFF) shl 16) or
                    ((bytes[3 * i + 1].toInt() and 0xFF) shl 8) or
                    (bytes[3 * i + 2].toInt() and 0xFF)
            }
            val d = PixelArtDetector.detect(pixels, w, h)
            println("ART $name ${w}x$h -> $d")
        }
    }
}
