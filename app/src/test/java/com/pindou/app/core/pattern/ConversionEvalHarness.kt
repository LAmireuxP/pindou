package com.pindou.app.core.pattern

import com.pindou.app.core.palette.PaletteJson
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 转换效果评测 harness（非断言测试）：真实照片 × 四档过滤等级，
 * 输出对比图到 D:/ChatGPT/conversion_eval，视觉评估用。
 * 输入为预先转好的裸 RGB 数据（python/PIL 生成，android.jar 无 ImageIO），
 * manifest.txt 每行 `name,width,height`；不存在时静默跳过（CI 不受影响）。
 */
class ConversionEvalHarness {

    private val rawDir = File("D:/ChatGPT/conversion_eval_input")
    private val outDir = File("D:/ChatGPT/conversion_eval")

    @Test
    fun `输出转换对比图`() {
        val manifest = rawDir.resolve("manifest.txt")
        if (!manifest.isFile) return
        val entries = manifest.readLines().mapNotNull { line ->
            val p = line.split(",")
            if (p.size != 3) null else Triple(p[0], p[1].toInt(), p[2].toInt())
        }
        if (entries.isEmpty()) return
        outDir.mkdirs()
        val palette = PaletteJson.parse(File("src/main/assets/palettes/mard.json").readText())
        val stats = StringBuilder()

        for ((name, w, h) in entries) {
            val raw = rawDir.resolve("$name.rgb")
            if (!raw.isFile) continue
            val bytes = raw.readBytes()
            val pixels = IntArray(w * h)
            for (i in pixels.indices) {
                pixels[i] = (0xFF shl 24) or
                    ((bytes[3 * i].toInt() and 0xFF) shl 16) or
                    ((bytes[3 * i + 1].toInt() and 0xFF) shl 8) or
                    (bytes[3 * i + 2].toInt() and 0xFF)
            }

            // 与颂拼豆同为"宽度档位、高度按比例"：78 宽对应原图高宽比
            val gridW = 78
            val gridH = (gridW.toDouble() * h / w).toInt().coerceIn(5, 200)
            // 诊断：像素画检测结果 + 还原出的画像素网格，看噪声是检测问题还是采样问题
            val artInfo = PixelArtDetector.detect(pixels, w, h)
            if (artInfo != null) {
                stats.appendLine("$name 像素画检测 block=${artInfo.blockW} art=${artInfo.artW}x${artInfo.artH} " +
                    "off=${artInfo.ox},${artInfo.oy}")
                if (name == "user_test") {
                    val s = ImagePreprocessor.sample(pixels, w, h, gridW, gridH, FilterLevel.MEDIUM)
                    val a = s.art
                    if (a != null) {
                        val px = 6
                        val img = IntArray(a.w * px * a.h * px) { 0xFFFFFFFF.toInt() }
                        for (yy in 0 until a.h) for (xx in 0 until a.w) {
                            fillRect(img, a.w * px, xx * px, yy * px, px, px, a.argb[yy * a.w + xx])
                        }
                        writePng(File(outDir, "user_test_artgrid.png"), img, a.w * px, a.h * px)
                        // 目标网格上的采样色（进入量化前），用来区分"采样噪声"和"量化噪声"
                        val cell = 8
                        val gi = IntArray(gridW * cell * gridH * cell) { 0xFFFFFFFF.toInt() }
                        for (yy in 0 until gridH) for (xx in 0 until gridW) {
                            fillRect(gi, gridW * cell, xx * cell, yy * cell, cell, cell, s.argb[yy * gridW + xx])
                        }
                        writePng(File(outDir, "user_test_sample.png"), gi, gridW * cell, gridH * cell)
                    }
                }
            }
            val configs = FilterLevel.entries.map { lvl ->
                lvl.label to GeneratorOptions(
                    gridW = gridW, gridH = gridH,
                    filterLevel = lvl,
                    autoExposure = true,
                )
            }
            val cells = mutableListOf<Pair<String, IntArray>>()
            for ((cname, options) in configs) {
                val r = PatternGenerator.generate(pixels, w, h, options, palette)
                cells.add(cname to r.cells)
                val colorCount = r.cells.filter { it >= 0 }.distinct().size
                stats.appendLine(
                    "$name $cname 用色 $colorCount 色号, ${r.cells.count { it >= 0 }} 格, " +
                        "杂点率 ${"%.2f".format(speckleRate(r.cells, gridW, gridH) * 100)}%, ${r.elapsedMs}ms | " +
                        r.quality.summary(),
                )
            }

            // 自动优选：候选排序 + 每个候选单图（仅 user_test，便于肉眼比对）
            val base = GeneratorOptions(gridW = gridW, gridH = gridH, filterLevel = FilterLevel.MEDIUM)
            val outcome = AutoTuner.sweep(
                pixels, w, h, base, palette,
                maxColorsOptions = AutoTuner.defaultColorOptions(base),
            )
            if (outcome != null) {
                stats.appendLine("$name 自动优选 → ${outcome.best.filterLevel.label} / " +
                    "${outcome.best.maxColors?.let { "$it 色" } ?: "不限色"} 得分 ${outcome.best.score}")
                outcome.all.sortedByDescending { it.score }.forEachIndexed { i, c ->
                    stats.appendLine("   #${i + 1} ${c.filterLevel.label}/${c.maxColors ?: "不限"} " +
                        "= ${c.score} 分 · 用色 ${c.result.quality.colorCount} · 均差 " +
                        "${"%.2f".format(c.result.quality.meanDeltaE)}")
                    if (name == "user_test") writePattern(File(outDir, "user_test_auto_$i.png"), c.result, palette)
                }
            }

            // 对比行：原图缩略 | 无 | 低 | 中 | 高，统一高度
            val cellPx = 8
            val panelH = gridH * cellPx
            val gap = 16
            val thumbW = (panelH.toDouble() * w / h).toInt()
            val sheetW = thumbW + gap + (gridW * cellPx + gap) * cells.size
            val sheetH = panelH + gap
            val sheet = IntArray(sheetW * sheetH) { 0xFFFFFF }
            drawScaled(sheet, sheetW, pixels, w, h, 0, 0, thumbW, panelH)
            var x = thumbW + gap
            for ((_, c) in cells) {
                for (gy in 0 until gridH) for (gx in 0 until gridW) {
                    val idx = c[gy * gridW + gx]
                    val color = if (idx >= 0) palette.colors[idx].argb else 0xFFF6F6F6.toInt()
                    fillRect(sheet, sheetW, x + gx * cellPx, gy * cellPx, cellPx, cellPx, color)
                }
                x += gridW * cellPx + gap
            }
            writePng(File(outDir, "${name}_sheet.png"), sheet, sheetW, sheetH)
        }
        File(outDir, "stats.txt").writeText(stats.toString())
        println(stats)
    }

    /** 单张图纸 8px/格 */
    private fun writePattern(file: File, r: PatternResult, palette: com.pindou.app.core.palette.BeadPalette) {
        val cellPx = 8
        val w = r.width * cellPx
        val h = r.height * cellPx
        val img = IntArray(w * h) { 0xFFF6F6F6.toInt() }
        for (gy in 0 until r.height) for (gx in 0 until r.width) {
            val idx = r.cells[gy * r.width + gx]
            if (idx < 0) continue
            fillRect(img, w, gx * cellPx, gy * cellPx, cellPx, cellPx, palette.colors[idx].argb)
        }
        writePng(file, img, w, h)
    }

    /** 杂点率：与 8 邻域众数不同的格子占比，越低越"干净" */
    private fun speckleRate(cells: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var diff = 0
        var total = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val cur = cells[y * w + x]
                val freq = HashMap<Int, Int>()
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val c = cells[(y + dy) * w + (x + dx)]
                    if (c >= 0) freq.merge(c, 1, Int::plus)
                }
                val mode = freq.maxByOrNull { it.value }?.key ?: continue
                if (cur != mode) diff++
                total++
            }
        }
        return if (total == 0) 0.0 else diff.toDouble() / total
    }

    /** 最近邻缩放绘制到画布（评测够用，无需插值） */
    private fun drawScaled(dst: IntArray, dstW: Int, src: IntArray, srcW: Int, srcH: Int, ox: Int, oy: Int, dw: Int, dh: Int) {
        for (y in 0 until dh) {
            val sy = y * srcH / dh
            for (x in 0 until dw) {
                dst[(oy + y) * dstW + ox + x] = src[sy * srcW + x * srcW / dw]
            }
        }
    }

    private fun fillRect(dst: IntArray, dstW: Int, ox: Int, oy: Int, w: Int, h: Int, color: Int) {
        for (y in oy until oy + h) {
            val row = y * dstW
            for (x in ox until ox + w) dst[row + x] = color
        }
    }

    /** 最小 PNG 编码器：RGB 8bit、无滤波，仅依赖 java.util.zip（单测运行时走真 JDK） */
    private fun writePng(file: File, pixels: IntArray, w: Int, h: Int) {
        val stride = w * 3
        val raw = ByteArray((stride + 1) * h)
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 0 // 每行滤波类型 0 = None
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                raw[o++] = ((p shr 16) and 0xFF).toByte()
                raw[o++] = ((p shr 8) and 0xFF).toByte()
                raw[o++] = (p and 0xFF).toByte()
            }
        }
        val def = Deflater()
        def.setInput(raw)
        def.finish()
        val buf = ByteArray(65536)
        val comp = ByteArrayOutputStream(raw.size / 4)
        while (!def.finished()) {
            val n = def.deflate(buf)
            comp.write(buf, 0, n)
        }
        def.end()

        val ihdr = ByteArray(13)
        putInt(ihdr, 0, w)
        putInt(ihdr, 4, h)
        ihdr[8] = 8  // bit depth
        ihdr[9] = 2  // color type: truecolor RGB

        file.outputStream().use { f ->
            f.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            f.write(chunk("IHDR", ihdr))
            f.write(chunk("IDAT", comp.toByteArray()))
            f.write(chunk("IEND", ByteArray(0)))
        }
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray(Charsets.US_ASCII) + data
        val crc = CRC32()
        crc.update(body, 0, body.size)
        val crcV = crc.value.toInt()
        val head = ByteArray(4)
        putInt(head, 0, data.size)
        return head + body + byteArrayOf(
            (crcV ushr 24).toByte(), (crcV ushr 16).toByte(), (crcV ushr 8).toByte(), crcV.toByte(),
        )
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }
}
