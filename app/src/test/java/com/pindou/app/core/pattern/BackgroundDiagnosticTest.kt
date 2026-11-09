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

        // 被删掉的彩色格：背景是白/近白，彩色格被删说明泛洪吃进了花瓣
        val colored = ArrayList<String>()
        for (y in 0 until art.artH) {
            for (x in 0 until art.artW) {
                val i = y * art.artW + x
                if (!mask[i]) continue
                val c = sample.argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val chroma = maxOf(r, g, b) - minOf(r, g, b)
                if (chroma >= 14 && !(r > 244 && g > 244 && b > 244)) {
                    colored.add("($x,$y) #%06X chroma=%d".format(c and 0xFFFFFF, chroma))
                }
            }
        }
        println("BG removed-colored=${colored.size}")
        colored.forEach { println("  $it") }
    }

    @Test
    fun `打印右下角淡粉落成什么色号`() {
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
        val palette = com.pindou.app.core.palette.PaletteJson.parse(
            File("D:/ChatGPT/pindou/app/src/main/assets/palettes/mard.json").readText()
        )
        val res = PatternGenerator.generate(
            pixels, w, h,
            GeneratorOptions(
                gridW = art.artW, gridH = art.artH,
                filterLevel = FilterLevel.NONE, autoExposure = false,
            ),
            palette,
        )
        val sample = ImagePreprocessor.sample(pixels, w, h, art.artW, art.artH, FilterLevel.NONE)
        println("DIAG ${art.artW}x${art.artH} codes in bottom-right (x 34..55, y 28..49)")
        for (y in 28 until art.artH) {
            val row = StringBuilder("%2d: ".format(y))
            for (x in 34 until art.artW) {
                val i = y * art.artW + x
                val idx = res.cells[i]
                val c = sample.argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val chroma = maxOf(r, g, b) - minOf(r, g, b)
                row.append(if (idx >= 0) palette.colors[idx].code.padEnd(4) else "--  ")
                if (chroma >= 12) row.append("*") else row.append(" ")
            }
            println("DIAG $row")
        }
        println("DIAG src colors row 38: " + (34 until art.artW).joinToString(" ") {
            "#%06X".format(sample.argb[38 * art.artW + it] and 0xFFFFFF)
        })
        println("DIAG src colors row 44: " + (34 until art.artW).joinToString(" ") {
            "#%06X".format(sample.argb[44 * art.artW + it] and 0xFFFFFF)
        })
    }

    /** 离线渲染最终图纸，省得每改一版都要装到手机上截图 */
    @Test
    fun `渲染最终图纸`() {
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
        val palette = com.pindou.app.core.palette.PaletteJson.parse(
            File("D:/ChatGPT/pindou/app/src/main/assets/palettes/mard.json").readText()
        )
        for (bg in listOf(false, true)) {
            val res = PatternGenerator.generate(
                pixels, w, h,
                GeneratorOptions(
                    gridW = art.artW, gridH = art.artH,
                    filterLevel = FilterLevel.NONE, autoExposure = false,
                    removeBackground = bg, backgroundTolerance = 12.0,
                ),
                palette,
            )
            // 单元测试里没有 java.awt，直接写 PPM（P6），外面用 Python 转 PNG
            val out = File("D:/ChatGPT/probe_out/offline_bg_$bg.ppm")
            out.parentFile.mkdirs()
            java.io.ByteArrayOutputStream().use { bos ->
                bos.write("P6\n${res.width} ${res.height}\n255\n".toByteArray())
                for (y in 0 until res.height) {
                    for (x in 0 until res.width) {
                        val idx = res.cells[y * res.width + x]
                        val rgb = if (idx >= 0) palette.colors[idx].argb else 0xF2F2F2
                        bos.write((rgb shr 16) and 0xFF)
                        bos.write((rgb shr 8) and 0xFF)
                        bos.write(rgb and 0xFF)
                    }
                }
                out.writeBytes(bos.toByteArray())
            }
            val top = res.usage().take(8).joinToString {
                "${palette.colors[it.first].code}=${it.second}"
            }
            println(
                "DIAG bg=$bg ${res.width}x${res.height} beads=${res.usage().sumOf { it.second }} " +
                    "colors=${res.usage().size} top: $top"
            )
        }
    }
}
