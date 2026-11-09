package com.pindou.app

import com.pindou.app.core.palette.PaletteJson
import com.pindou.app.core.pattern.FilterLevel
import com.pindou.app.core.pattern.GeneratorOptions
import com.pindou.app.core.pattern.PatternGenerator
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 离线渲染夹具（仅本地调试用，不参与常规回归）。
 *
 * 用 JVM 直接跑生产管线，把结果画成 PNG，避免每次改算法都要装机等 probe。
 * 参数走环境变量：
 *   HARNESS_RAW    源图裸数据（tools/img2raw.ps1 生成：int32 w, int32 h, w*h 个 ARGB）
 *   HARNESS_GRID   目标网格宽（高按源图比例推）
 *   HARNESS_LEVEL  NONE / LOW / MEDIUM / HIGH，默认 MEDIUM
 *   HARNESS_BG     "1" = 去背景
 *   HARNESS_OUT    输出 PNG 目录
 *   HARNESS_CROP   "x,y,w,h" 源图像素裁剪（可选）
 */
class HarnessTest {

    @Test
    fun render() {
        val rawPath = System.getenv("HARNESS_RAW") ?: return
        val gridW = System.getenv("HARNESS_GRID")?.toIntOrNull() ?: 78
        val level = FilterLevel.entries.firstOrNull {
            it.name.equals(System.getenv("HARNESS_LEVEL") ?: "MEDIUM", ignoreCase = true)
        } ?: FilterLevel.MEDIUM
        val removeBg = System.getenv("HARNESS_BG") == "1"
        val outDir = File(System.getenv("HARNESS_OUT") ?: "build/harness")
        outDir.mkdirs()

        // raw 由 PowerShell BinaryWriter 写出（小端），Java 默认大端，必须显式指定
        val buf = ByteBuffer.wrap(File(rawPath).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var w = buf.int
        var h = buf.int
        var pixels = IntArray(w * h)
        buf.asIntBuffer().get(pixels)
        // 可选裁剪：眼睛这种小区域单独看，避免整图缩放后看不清
        val crop = System.getenv("HARNESS_CROP")?.split(",")?.map { it.trim().toInt() }
        if (crop != null && crop.size == 4) {
            val (cx, cy, cw, ch) = crop
            val sub = IntArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                val sx = (cx + x).coerceIn(0, w - 1)
                val sy = (cy + y).coerceIn(0, h - 1)
                sub[y * cw + x] = pixels[sy * w + sx]
            }
            pixels = sub
            w = cw
            h = ch
        }

        val gridH = Math.round(gridW.toDouble() * h / w).toInt()
        println("HARNESS src=${w}x$h grid=${gridW}x$gridH")
        System.getenv("HARNESS_CELLS")?.let { spec ->
            val (x0, y0, x1, y1) = spec.split(",").map { it.trim().toInt() }
            // 与生产代码同一套映射（整数比例，不是 blockStart/End），否则统计会整体偏一格
            val darkCnt = IntArray(gridW * gridH)
            val brightCnt = IntArray(gridW * gridH)
            val totCnt = IntArray(gridW * gridH)
            for (py in 0 until h) {
                val gy = (py.toLong() * gridH / h).toInt()
                for (px in 0 until w) {
                    val gx = (px.toLong() * gridW / w).toInt()
                    val p2 = pixels[py * w + px]
                    val l2 = 0.299 * ((p2 shr 16) and 0xFF) + 0.587 * ((p2 shr 8) and 0xFF) +
                        0.114 * (p2 and 0xFF)
                    val k2 = gy * gridW + gx
                    totCnt[k2]++
                    if (l2 < 48) darkCnt[k2]++
                    if (l2 > 225) brightCnt[k2]++
                }
            }
            for (cy in y0..y1) {
                val sb = StringBuilder("row$cy ")
                for (cx in x0..x1) {
                    val k2 = cy * gridW + cx
                    val n = totCnt[k2].coerceAtLeast(1)
                    sb.append(String.format("|%2d:%2d/%2d", cx, darkCnt[k2] * 99 / n, brightCnt[k2] * 99 / n))
                }
                println(sb)
            }
            // 采样层（曝光修正 + area 平均 + 边界锐化）单独打一份，用来区分
            // "丢在采样" 还是 "丢在量化/清理"
            val exp = com.pindou.app.core.pattern.ExposureCorrector.correct(pixels, w, h)
            val s = com.pindou.app.core.pattern.ImagePreprocessor.sample(
                exp, w, h, gridW, gridH, level,
            )
            for (cy in y0..y1) {
                val sb = StringBuilder("samp$cy ")
                for (cx in x0..x1) {
                    val i = cy * gridW + cx
                    val hex = Integer.toHexString(s.argb[i]).takeLast(6)
                    sb.append(hex).append(if (s.edge?.get(i) == true) "* " else "  ")
                }
                println(sb)
            }
            if (s.spot != null) {
                for (cy in y0..y1) {
                    val sb = StringBuilder("spot$cy ")
                    for (cx in x0..x1) sb.append(s.spot!![cy * gridW + cx].toInt().toString().padStart(4))
                    println(sb)
                }
            }
        }
        val palette = PaletteJson.parse(
            File("src/main/assets/palettes/all_solid_272.json").readText(),
        )
        val result = PatternGenerator.generate(
            pixels, w, h,
            GeneratorOptions(
                gridW = gridW,
                gridH = gridH,
                removeBackground = removeBg,
                backgroundTolerance = 12.0,
                filterLevel = level,
            ),
            palette,
        )

        val cell = 12
        val ow = result.width * cell
        val oh = result.height * cell
        val argbOut = IntArray(ow * oh)
        val argbs = result.palette.colors.map { it.argb }
        for (y in 0 until result.height) {
            for (x in 0 until result.width) {
                val idx = result.cells[y * result.width + x]
                val rgb = if (idx < 0) 0xFFFFFFFF.toInt() else argbs[idx] or (0xFF shl 24)
                for (dy in 0 until cell) for (dx in 0 until cell) {
                    argbOut[(y * cell + dy) * ow + x * cell + dx] = rgb
                }
            }
        }
        val name = "g${gridW}_${level.name}${if (removeBg) "_bg" else ""}.png"
        val outFile = File(outDir, name)
        writePng(outFile, argbOut, ow, oh)
        println("HARNESS -> ${outFile.absolutePath}  grid=${result.width}x${result.height} " +
            "quality=${result.quality.score} colors=${result.usage().size} pixelArt=${result.pixelArt}")
        System.getenv("HARNESS_CELLS")?.let { spec ->
            val (x0, y0, x1, y1) = spec.split(",").map { it.trim().toInt() }
            for (cy in y0..y1) {
                val sb = StringBuilder("code$cy ")
                for (cx in x0..x1) {
                    val idx = result.cells[cy * result.width + cx]
                    val code = if (idx < 0) "--" else result.palette.colors[idx].code
                    sb.append(code.padEnd(6))
                }
                println(sb)
            }
        }
    }

    /** 极简 PNG 编码器：RGBA8888、filter=None。测试夹具用，不求压缩率。 */
    private fun writePng(file: File, argb: IntArray, w: Int, h: Int) {
        val raw = ByteArray(h * (1 + w * 4))
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 0
            for (x in 0 until w) {
                val p = argb[y * w + x]
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
            putInt(ihdr, 0, w); putInt(ihdr, 4, h)
            ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
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
