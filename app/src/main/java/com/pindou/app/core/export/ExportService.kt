package com.pindou.app.core.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.pindou.app.core.pattern.PatternResult
import java.io.File
import java.io.FileOutputStream

/**
 * 导出落地：把渲染结果写成 PNG / PDF 文件并通过 FileProvider 分享。
 * 文件名规则：拼豆_<项目名>_<尺寸>_<时间戳>.<ext>
 */
object ExportService {

    sealed interface Exported {
        data class FileReady(val file: File, val uri: Uri) : Exported
        data class Failed(val message: String) : Exported
    }

    /** 渲染并保存 PNG（pattern=彩色带色号，symbol=符号版） */
    fun exportPng(
        context: Context,
        results: List<PatternResult>,
        projectName: String,
        symbol: Boolean = false,
    ): Exported = runCatching {
        require(results.isNotEmpty()) { "无页数据" }
        // 每页独立渲染（含标题栏），纵向拼接成一张长图
        val parts = results.mapIndexed { pageIndex, r ->
            val cellPx = defaultCellPx(r)
            val titleHeight = if (symbol) 0 else titleBarHeight(cellPx)
            val rendered = if (symbol) {
                ExportRenderer.renderSymbol(
                    r,
                    ExportRenderer.RenderOptions(cellPx = cellPx, showCodes = false),
                )
            } else {
                ExportRenderer.renderPattern(
                    r,
                    ExportRenderer.RenderOptions(
                        cellPx = cellPx,
                        // 色号不走位图点阵字：位图字体只有 7px 高，放大后仍糊，
                        // 统一改由 Android Canvas 用系统字体在最终分辨率上绘制
                        showCodes = false,
                        showGrid = true,
                        titleHeightPx = titleHeight,
                    ),
                )
            }
            val bmp = rendered.toBitmap()
            if (symbol) {
                drawSymbolLabels(bmp, r, cellPx)
            } else {
                val pageTag = if (results.size > 1) "  ·  第${pageIndex + 1}/${results.size}页" else ""
                drawTitleBar(
                    bmp,
                    titleHeight,
                    "$projectName  ·  ${r.palette.brandLabel}  ·  ${r.width}×${r.height}$pageTag",
                    // 只统计实际出豆的格子：开背景移除后 cells.size 仍是整幅网格，
                    // 拿它当"共 N 格"会把人吓一跳（样例图 5460 实际 3131）
                    "共 ${r.cells.count { it >= 0 }} 格 / ${r.usage().size} 色",
                )
                drawCellCodes(bmp, r, cellPx, titleHeight)
            }
            bmp
        }
        val totalHeight = parts.sumOf { it.height }
        val combined = Bitmap.createBitmap(parts[0].width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        var y = 0
        for (part in parts) {
            canvas.drawBitmap(part, 0f, y.toFloat(), null)
            y += part.height
            part.recycle()
        }
        val name = fileName(results.first(), if (symbol) "symbol" else "pattern", "png")
        val uri = saveToDownloads(context, combined, name, "image/png")
        combined.recycle()
        Exported.FileReady(File(name), uri)
    }.getOrElse { Exported.Failed(it.message ?: "导出失败") }

    /** 单页导出（多页版本委托入口） */
    fun exportPng(
        context: Context,
        result: PatternResult,
        projectName: String,
        symbol: Boolean = false,
    ): Exported = exportPng(context, listOf(result), projectName, symbol)

    /** 导出 .bpat 图纸文件（通用交换格式） */
    fun exportBpat(
        context: Context,
        jsonText: String,
        result: PatternResult,
    ): Exported = runCatching {
        val name = fileName(result, "bpat", "bpat")
        val uri = saveBytesToDownloads(
            context,
            jsonText.toByteArray(Charsets.UTF_8),
            name,
            "application/octet-stream",
        )
        Exported.FileReady(File(name), uri)
    }.getOrElse { Exported.Failed(it.message ?: "bpat 导出失败") }

    /**
     * 保存到系统「下载」目录（用户能在文件管理器里直接看到，且不会被缓存清理）。
     * API 29+ 走 MediaStore；旧版本落到应用外部目录（无需存储权限）。
     */
    private fun saveToDownloads(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        mime: String,
    ): Uri {
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/拼豆图纸")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: error("无法写入下载文件")
            uri
        } else {
            val dir = File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                "拼豆图纸",
            ).apply { mkdirs() }
            val file = File(dir, displayName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            uriFor(context, file)
        }
    }

    private fun saveBytesToDownloads(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mime: String,
    ): Uri {
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/拼豆图纸")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入下载文件")
            uri
        } else {
            val dir = File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                "拼豆图纸",
            ).apply { mkdirs() }
            val file = File(dir, displayName)
            file.writeBytes(bytes)
            uriFor(context, file)
        }
    }

    /** 导出 A4 分页 PDF：图纸分页 + 清单页 */
    fun exportPdf(
        context: Context,
        result: PatternResult,
        projectName: String,
    ): Exported = exportPdf(context, listOf(result), projectName)

    /** 多页 PDF：每页图纸独立渲染切分，末尾附聚合清单页 */
    fun exportPdf(
        context: Context,
        results: List<PatternResult>,
        projectName: String,
    ): Exported = runCatching {
        require(results.isNotEmpty()) { "无页数据" }

        // A4 @ 72dpi = 595 x 842 pt；宽图纸用横向，格子能放大约 45%，
        // 打印出来的色号才不是一排小点（纸张方向随图纸长宽比自动选）。
        val portraitW = 595
        val portraitH = 842
        val doc = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        var docPageIndex = 0
        for ((idx, r) in results.withIndex()) {
            val landscape = r.width >= r.height
            val pageW = if (landscape) portraitH else portraitW
            val pageH = if (landscape) portraitW else portraitH
            // PDF 内嵌的是位图：按 A4 宽度缩放后 1 格只剩 2~3pt，
            // 用屏幕档 cellPx 打印出来色号必然糊。这里按打印分辨率单独放大，
            // 保证每格文字落纸后仍有约 1mm 高（300dpi 下 ~12px）。
            val cellPx = pdfCellPx(r)
            val titleHeight = titleBarHeight(cellPx)
            val patternBmp = ExportRenderer
                .renderPattern(
                    r,
                    ExportRenderer.RenderOptions(
                        cellPx = cellPx,
                        showCodes = false,
                        showGrid = true,
                        titleHeightPx = titleHeight,
                    ),
                )
                .toBitmap()
            val pageTag = if (results.size > 1) "  ·  第${idx + 1}/${results.size}页" else ""
            drawTitleBar(
                patternBmp,
                titleHeight,
                "$projectName  ·  ${r.palette.brandLabel}  ·  ${r.width}×${r.height}$pageTag",
                "共 ${r.cells.count { it >= 0 }} 格 / ${r.usage().size} 色",
            )
            drawCellCodes(patternBmp, r, cellPx, titleHeight)

            // 按内容切分：整幅图纸若超过一页，按页高切片
            val contentW = patternBmp.width
            val contentH = patternBmp.height
            val scale = (pageW - 32f) / contentW
            val sliceH = ((pageH - 32f) / scale).toInt().coerceAtLeast(1)
            var y = 0
            while (y < contentH) {
                val h = (contentH - y).coerceAtMost(sliceH)
                val slice = Bitmap.createBitmap(patternBmp, 0, y, contentW, h)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, docPageIndex + 1).create())
                val c: Canvas = page.canvas
                val destW = contentW * scale
                val destH = h * scale
                c.drawBitmap(slice, null, android.graphics.RectF(16f, 16f, 16f + destW, 16f + destH), paint)
                doc.finishPage(page)
                slice.recycle()
                y += h
                docPageIndex++
            }
            patternBmp.recycle()
        }

        // 清单页固定纵向
        val pageW = portraitW
        val pageH = portraitH
        val listPage = doc.startPage(
            PdfDocument.PageInfo.Builder(pageW, pageH, docPageIndex + 1).create(),
        )
        val lp = Paint().apply {
            color = android.graphics.Color.BLACK
            isAntiAlias = true
        }
        var ty = 40f
        for (line in ShoppingList.render(results, projectName).lines()) {
            if (ty > pageH - 24) break
            val isTitle = line.startsWith("拼豆采购清单")
            lp.textSize = if (isTitle) 16f else 10f
            lp.isFakeBoldText = isTitle
            // 制表符用两空格代替（PDF 无制表位）
            listPage.canvas.drawText(line.replace("\t", "  "), 24f, ty, lp)
            ty += if (isTitle) 26f else 16f
        }
        doc.finishPage(listPage)

        val pdfBytes = java.io.ByteArrayOutputStream().use { bos ->
            doc.writeTo(bos)
            bos.toByteArray()
        }
        doc.close()
        val name = fileName(results.first(), "print", "pdf")
        val uri = saveBytesToDownloads(context, pdfBytes, name, "application/pdf")
        Exported.FileReady(File(name), uri)
    }.getOrElse { Exported.Failed(it.message ?: "PDF 导出失败") }

    /**
     * 用系统字体在预留的标题栏里绘制标题（点阵字体没有中文字形）。
     * 主标题 + 副标题两行，左侧对齐。
     */
    private fun drawTitleBar(bitmap: Bitmap, titleHeight: Int, title: String, subtitle: String) {
        if (titleHeight <= 0) return
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#171717")
            isFakeBoldText = true
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val padX = titleHeight * 0.24f
        val titleSize = (titleHeight * 0.32f).coerceAtLeast(11f)
        val subSize = (titleHeight * 0.21f).coerceAtLeast(9f)

        // 两行都按字体度量定位：先落下 ascent 的绝对值，再用第一行的 descent +
        // 6% 行距推第二行基线。不同 ROM 默认行高不一致，写死基线会顶出画面。
        paint.textSize = titleSize
        val titleBaseline = titleHeight * 0.13f - paint.ascent()
        canvas.drawText(title, padX, titleBaseline, paint)
        val titleDescent = paint.descent()

        paint.isFakeBoldText = false
        paint.color = android.graphics.Color.parseColor("#52525B")
        paint.textSize = subSize
        val subBaseline = titleBaseline + titleDescent + titleHeight * 0.06f - paint.ascent()
        canvas.drawText(subtitle, padX, subBaseline, paint)

        // 标题栏与图纸之间的分隔线
        val line = Paint().apply {
            color = android.graphics.Color.parseColor("#33000000")
            strokeWidth = 1f
        }
        canvas.drawLine(0f, titleHeight.toFloat(), bitmap.width.toFloat(), titleHeight.toFloat(), line)
    }

    /**
     * 色号标注：系统字体 + 抗锯齿，在最终像素分辨率上绘制。
     * 字号按格子高度 46% 取，宽度超格再等比缩；底色深浅自动切黑/白字。
     * 复杂度 O(格数)，78×70 约 5500 次 drawText，几十毫秒。
     */
    private fun drawCellCodes(bitmap: Bitmap, r: PatternResult, cell: Int, titleHeight: Int) {
        if (cell < 16) return
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        // 46% 格高是「两字符刚好不贴边」的上限；再大就会压到网格线。
        // 宽度按 measureText 实测收敛，色号 3 字符（如 A01 之外的 3 位码）自动缩字。
        val baseSize = cell * 0.46f
        val maxWidth = cell * 0.88f
        for (gy in 0 until r.height) {
            for (gx in 0 until r.width) {
                val idx = r.cells[gy * r.width + gx]
                if (idx < 0) continue
                val color = r.palette.colors[idx]
                paint.textSize = baseSize
                val measured = paint.measureText(color.code)
                if (measured > maxWidth) {
                    paint.textSize = baseSize * maxWidth / measured
                }
                paint.color = codeTextColor(color.argb)
                val cx = gx * cell + cell / 2f
                val cy = titleHeight + gy * cell + cell / 2f
                // 基线按字体度量居中，避免不同字号的数字上下偏移
                val baseline = cy - (paint.ascent() + paint.descent()) / 2f
                canvas.drawText(color.code, cx, baseline, paint)
            }
        }
    }

    /**
     * 符号版标注：格子符号（大）+ 色号（小）+ 底部对照表，同样走系统字体。
     * 符号分配规则必须与 ExportRenderer.renderSymbol 完全一致：
     * usage() 按用量降序，第 i 个颜色拿 SYMBOLS[i % size]。
     */
    private fun drawSymbolLabels(bitmap: Bitmap, r: PatternResult, cell: Int) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val usedIndices = r.usage().map { it.first }
        val symbolOf = HashMap<Int, Char>(usedIndices.size)
        usedIndices.forEachIndexed { i, colorIdx ->
            symbolOf[colorIdx] = ExportRenderer.SYMBOLS[i % ExportRenderer.SYMBOLS.size]
        }

        // 标题
        paint.textAlign = Paint.Align.LEFT
        paint.color = 0xFF333333.toInt()
        paint.textSize = (cell * 0.55f).coerceAtLeast(14f)
        paint.isFakeBoldText = true
        canvas.drawText("SYMBOL CHART", cell * 0.25f, cell * 0.68f, paint)
        paint.isFakeBoldText = false

        // 每格：符号居中偏上，色号贴底
        val titleH = cell
        val symSize = cell * 0.52f
        val codeSize = (cell * 0.26f).coerceAtLeast(9f)
        for (gy in 0 until r.height) {
            for (gx in 0 until r.width) {
                val idx = r.cells[gy * r.width + gx]
                if (idx < 0) continue
                val sym = symbolOf[idx] ?: continue
                val cx = gx * cell + cell / 2f
                val top = titleH + gy * cell
                paint.textAlign = Paint.Align.CENTER
                paint.color = 0xFF333333.toInt()
                paint.textSize = symSize
                canvas.drawText(sym.toString(), cx, top + cell * 0.58f, paint)
                if (cell >= 24) {
                    paint.textSize = codeSize
                    paint.color = 0xFF6A6A6A.toInt()
                    canvas.drawText(
                        r.palette.colors[idx].code,
                        cx,
                        top + cell - cell * 0.12f,
                        paint,
                    )
                }
            }
        }

        // 对照表：4 列，行高 cell/2，与 renderSymbol 布局一致
        val legendTop = r.height * cell + titleH + cell / 2
        val colW = bitmap.width / 4
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = (cell * 0.32f).coerceAtLeast(10f)
        paint.color = 0xFF333333.toInt()
        usedIndices.forEachIndexed { i, colorIdx ->
            val col = i % 4
            val row = i / 4
            val x = col * colW + cell / 3 + 8f
            val y = legendTop + row * (cell / 2)
            canvas.drawText(
                "${symbolOf[colorIdx]} ${r.palette.colors[colorIdx].code}",
                x,
                y + cell * 0.34f,
                paint,
            )
        }
    }

    /** 分享纯文本清单 */
    fun shareList(context: Context, result: PatternResult, projectName: String) {
        shareList(context, listOf(result), projectName)
    }

    /** 分享多页聚合清单 */
    fun shareList(context: Context, results: List<PatternResult>, projectName: String) {
        val text = ShoppingList.render(results, projectName)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "拼豆采购清单 · $projectName")
        }
        context.startActivity(Intent.createChooser(intent, "分享采购清单"))
    }

    /** 分享已导出的文件 */
    fun shareFile(context: Context, exported: Exported.FileReady) {
        val mime = when (exported.file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "bpat" -> "application/octet-stream"
            else -> "image/png"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, exported.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享图纸"))
    }

    private fun defaultCellPx(result: PatternResult): Int = when {
        result.width <= 32 -> 88
        result.width <= 64 -> 60
        result.width <= 100 -> 48
        else -> 32
    }

    /** PDF 专用：比屏幕档再大一截，抵消 A4 缩放 */
    private fun pdfCellPx(result: PatternResult): Int = when {
        result.width <= 32 -> 120
        result.width <= 64 -> 84
        result.width <= 100 -> 64
        else -> 44
    }

    private fun titleBarHeight(cell: Int): Int = (cell * 1.8f).toInt()

    /**
     * 色号字色：按 WCAG 相对亮度取黑/白里对比度更高的一方。
     * 直接拿 0.5 亮度切会出现「中灰底 + 深灰字」这种 2:1 的低对比组合。
     */
    private fun codeTextColor(argb: Int): Int {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin(argb and 0xFF)
        val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
        // 与黑/白的对比度：(L+0.05)/0.05 与 1.05/(L+0.05)，分界 L≈0.179
        return if (lum > 0.179) 0xFF1C1C1E.toInt() else 0xFFF7F7F7.toInt()
    }

    private fun fileName(result: PatternResult, kind: String, ext: String): String {
        val ts = System.currentTimeMillis() / 1000
        return "拼豆_${result.width}x${result.height}_$kind$ts.$ext"
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun ExportRenderer.RenderedBitmap.toBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }
}
