package com.pindou.app.core.pattern

import com.pindou.app.core.palette.PaletteJson
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 调参夹具：把 conversion_eval_input 里的裸 RGB 按指定宽度档位跑生产管线，
 * 输出 PNG 到 D:/ChatGPT/tune_out。只服务本地迭代，不含断言。
 * 环境变量 TUNE_NAME（默认 rabbit2）、TUNE_W（默认 58/78/104 三档）。
 */
class TuneHarness {

    private val inputDir = File("D:/ChatGPT/conversion_eval_input")
    private val outDir = File("D:/ChatGPT/tune_out")

    @Test
    fun render() {
        val manifest = inputDir.resolve("manifest.txt")
        if (!manifest.isFile) return
        outDir.mkdirs()
        val entries = manifest.readLines().mapNotNull { line ->
            val p = line.trim().split(",")
            if (p.size != 3) null else Triple(p[0], p[1].toInt(), p[2].toInt())
        }
        val want = System.getenv("TUNE_NAME") ?: "rabbit2"
        val widths = (System.getenv("TUNE_W") ?: "58,78,104").split(",").map { it.trim().toInt() }
        val levelEnv = System.getenv("TUNE_LEVEL")
        val levels = if (levelEnv != null) {
            FilterLevel.entries.filter { it.name.equals(levelEnv, true) }
        } else FilterLevel.entries
        val removeBg = System.getenv("TUNE_BG") == "1"
        val paletteName = System.getenv("TUNE_PALETTE") ?: "all_solid_272.json"

        val palette = PaletteJson.parse(File("src/main/assets/palettes/$paletteName").readText())
        val targets = if (want == "all") entries else entries.filter { it.first == want }
        if (targets.isEmpty()) return
        for (entry in targets) runOne(entry, widths, levels, removeBg, palette)
    }

    private fun runOne(
        entry: Triple<String, Int, Int>,
        widths: List<Int>,
        levels: List<FilterLevel>,
        removeBg: Boolean,
        palette: com.pindou.app.core.palette.BeadPalette,
    ) {
        val (name, w, h) = entry
        val bytes = inputDir.resolve("$name.rgb").readBytes()
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            pixels[i] = (0xFF shl 24) or
                ((bytes[3 * i].toInt() and 0xFF) shl 16) or
                ((bytes[3 * i + 1].toInt() and 0xFF) shl 8) or
                (bytes[3 * i + 2].toInt() and 0xFF)
        }
        for (gw in widths) {
            val gh = Math.round(gw.toDouble() * h / w).toInt().coerceIn(2, 400)
            run {
                val st = StrokeSkeleton.analyze(pixels, w, h, gw, gh)
                val hitCells = st.hits.count { it > 0 }
                val solidCells = st.coverage.count { it >= 0.80f }
                println(
                    "STROKE gw=$gw lineArt=${st.lineArt} hitCells=$hitCells " +
                        "solidCells=$solidCells paper=${"%.3f".format(st.paperShare)} " +
                        "ink=${"%.3f".format(st.inkShare)}"
                )
                val smp = ImagePreprocessor.sample(pixels, w, h, gw, gh, FilterLevel.NONE)
                var changedByInk = 0
                for (i in 0 until gw * gh) {
                    if (st.hits[i] > 0 && smp.argb[i] != st.inkArgb[i]) changedByInk++
                }
                println("STROKE diffVsSample=$changedByInk")
            }
            run {
                val d = PixelArtDetector.detect(pixels, w, h)
                println(
                    if (d == null) "ART none" else
                        "ART artW=${d.artW} artH=${d.artH} bw=${"%.2f".format(d.blockW)} bh=${"%.2f".format(d.blockH)} ox=${"%.1f".format(d.ox)} oy=${"%.1f".format(d.oy)}"
                )
            }
            for (lvl in levels) {
                val t0 = System.nanoTime()
                val res = PatternGenerator.generate(
                    pixels, w, h,
                    GeneratorOptions(
                        gridW = gw, gridH = gh, filterLevel = lvl,
                        removeBackground = removeBg,
                    ),
                    palette,
                )
                val ms = (System.nanoTime() - t0) / 1_000_000
                val suffix = if (removeBg) "_bg" else ""
                val f = File(outDir, "${name}_${gw}_${lvl.name}${suffix}.png")
                writePng(f, res, 14)
                println(
                    "TUNE ${f.name} ${res.width}x${res.height} colors=${res.usage().size} " +
                        "score=${res.quality.score} art=${res.pixelArt} ${ms}ms " +
                        "de=${"%.2f".format(res.quality.meanDeltaE)} p90=${"%.2f".format(res.quality.p90DeltaE)} " +
                        "break=${"%.3f".format(res.quality.breakRate)} flat=${"%.3f".format(res.quality.flattenRate)} " +
                        "qcolors=${res.quality.colorCount}"
                )
            }
        }
    }

    private fun writePng(file: File, res: PatternResult, cell: Int) {
        val ow = res.width * cell
        val oh = res.height * cell
        val argb = IntArray(ow * oh)
        val argbs = res.palette.colors.map { it.argb }
        for (y in 0 until res.height) {
            for (x in 0 until res.width) {
                val idx = res.cells[y * res.width + x]
                val rgb = if (idx < 0) 0xFFFFFFFF.toInt() else argbs[idx] or (0xFF shl 24)
                for (dy in 0 until cell) for (dx in 0 until cell) {
                    argb[(y * cell + dy) * ow + x * cell + dx] = rgb
                }
            }
        }
        // 1 像素浅灰网格线，方便看单格
        for (y in 0 until oh) for (x in 0 until ow) {
            if (x % cell == 0 || y % cell == 0) argb[y * ow + x] = 0xFFE6E6E6.toInt()
        }
        var raw = ByteArray(oh * (1 + ow * 4))
        var o = 0
        for (y in 0 until oh) {
            raw[o++] = 0
            for (x in 0 until ow) {
                val p = argb[y * ow + x]
                raw[o++] = ((p shr 16) and 0xFF).toByte()
                raw[o++] = ((p shr 8) and 0xFF).toByte()
                raw[o++] = (p and 0xFF).toByte()
                raw[o++] = ((p ushr 24) and 0xFF).toByte()
            }
        }
        val deflater = Deflater(6)
        deflater.setInput(raw)
        deflater.finish()
        val zbuf = ByteArray(raw.size + raw.size / 2 + 128)
        var zlen = 0
        while (!deflater.finished()) {
            zlen += deflater.deflate(zbuf, zlen, zbuf.size - zlen)
            if (zlen == zbuf.size) break
        }
        deflater.end()
        FileOutputStream(file).buffered().use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            val ihdr = ByteArray(13)
            putInt(ihdr, 0, ow); putInt(ihdr, 4, oh)
            ihdr[8] = 8; ihdr[9] = 6
            chunk(out, "IHDR", ihdr)
            chunk(out, "IDAT", zbuf.copyOf(zlen))
            chunk(out, "IEND", ByteArray(0))
        }
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }

    private fun chunk(out: java.io.OutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4); putInt(len, 0, data.size)
        out.write(len)
        val body = type.toByteArray(Charsets.US_ASCII) + data
        out.write(body)
        val crc = CRC32(); crc.update(body)
        val c = ByteArray(4); putInt(c, 0, crc.value.toInt())
        out.write(c)
    }
}
