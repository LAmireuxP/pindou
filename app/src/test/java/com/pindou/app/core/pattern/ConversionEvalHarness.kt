package com.pindou.app.core.pattern

import com.pindou.app.core.palette.PaletteJson
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 转换效果评测 harness（非断言测试）：真实照片 × 多组参数，
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

            val grid = 40
            val configs = listOf(
                "A_旧1比1" to GeneratorOptions(
                    gridW = grid, gridH = grid,
                    contentMode = ContentSimplifier.Mode.OFF,
                    regionCleanupEnabled = false,
                    autoExposure = false,
                ),
                "B_新默认卡通化" to GeneratorOptions(
                    gridW = grid, gridH = grid,
                    contentMode = ContentSimplifier.Mode.CARTOON,
                    regionCleanupEnabled = true,
                ),
                "C_卡通化限24色" to GeneratorOptions(
                    gridW = grid, gridH = grid,
                    contentMode = ContentSimplifier.Mode.CARTOON,
                    regionCleanupEnabled = true,
                    maxColors = 24,
                ),
            )
            val cells = mutableListOf<Pair<String, IntArray>>()
            for ((cname, options) in configs) {
                val r = PatternGenerator.generate(pixels, w, h, options, palette)
                cells.add(cname to r.cells)
                val colorCount = r.cells.filter { it >= 0 }.distinct().size
                stats.appendLine("$name $cname 用色 $colorCount 色号, ${r.cells.count { it >= 0 }} 格, ${r.elapsedMs}ms")
            }

            // 对比行：原图缩略 | A | B | C，统一高度，面板顺序与 stats 一致
            val cellPx = 12
            val panelH = grid * cellPx
            val gap = 16
            val thumbW = panelH * w / h
            val sheetW = thumbW + gap + (cellPx * grid + gap) * cells.size
            val sheetH = panelH + gap
            val sheet = IntArray(sheetW * sheetH) { 0xFFFFFF }
            drawScaled(sheet, sheetW, pixels, w, h, 0, 0, thumbW, panelH)
            var x = thumbW + gap
            for ((_, c) in cells) {
                for (gy in 0 until grid) for (gx in 0 until grid) {
                    val idx = c[gy * grid + gx]
                    val color = if (idx >= 0) palette.colors[idx].argb else 0xFFF6F6
                    fillRect(sheet, sheetW, x + gx * cellPx, gy * cellPx, cellPx, cellPx, color)
                }
                x += cellPx * grid + gap
            }
            writePng(File(outDir, "${name}_sheet.png"), sheet, sheetW, sheetH)
        }
        File(outDir, "stats.txt").writeText(stats.toString())
        println(stats)
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
