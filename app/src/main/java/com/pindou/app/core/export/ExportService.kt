package com.pindou.app.core.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
        result: PatternResult,
        projectName: String,
        symbol: Boolean = false,
        cellPx: Int = defaultCellPx(result),
    ): Exported = runCatching {
        val titleHeight = if (symbol) 0 else (cellPx * 1.6f).toInt()
        val rendered = if (symbol) {
            ExportRenderer.renderSymbol(result, ExportRenderer.RenderOptions(cellPx = cellPx))
        } else {
            ExportRenderer.renderPattern(
                result,
                ExportRenderer.RenderOptions(
                    cellPx = cellPx,
                    showCodes = true,
                    showGrid = true,
                    titleHeightPx = titleHeight,
                ),
            )
        }
        val bitmap = rendered.toBitmap()
        if (!symbol) {
            drawTitleBar(
                bitmap,
                titleHeight,
                "$projectName  ·  ${result.palette.brandLabel}  ·  ${result.width}×${result.height}",
                "共 ${result.cells.size} 格 / ${result.usage().size} 色",
            )
        }
        val name = fileName(result, if (symbol) "symbol" else "pattern", "png")
        val uri = saveToDownloads(context, bitmap, name, "image/png")
        bitmap.recycle()
        Exported.FileReady(File(name), uri)
    }.getOrElse { Exported.Failed(it.message ?: "导出失败") }

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
    ): Exported = runCatching {
        val cellPx = defaultCellPx(result)
        val titleHeight = (cellPx * 1.6f).toInt()
        val patternBmp = ExportRenderer
            .renderPattern(
                result,
                ExportRenderer.RenderOptions(
                    cellPx = cellPx,
                    showCodes = true,
                    showGrid = true,
                    titleHeightPx = titleHeight,
                ),
            )
            .toBitmap()
        drawTitleBar(
            patternBmp,
            titleHeight,
            "$projectName  ·  ${result.palette.brandLabel}  ·  ${result.width}×${result.height}",
            "共 ${result.cells.size} 格 / ${result.usage().size} 色",
        )

        // A4 @ 72dpi = 595 x 842 pt
        val pageW = 595
        val pageH = 842
        val doc = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // 按内容切分：整幅图纸若超过一页，按页高切片
        val contentW = patternBmp.width
        val contentH = patternBmp.height
        val scale = (pageW - 32f) / contentW
        val sliceH = ((pageH - 32f) / scale).toInt().coerceAtLeast(1)
        var y = 0
        var pageIndex = 0
        while (y < contentH) {
            val h = (contentH - y).coerceAtMost(sliceH)
            val slice = Bitmap.createBitmap(patternBmp, 0, y, contentW, h)
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex + 1).create())
            val c: Canvas = page.canvas
            val destW = contentW * scale
            val destH = h * scale
            c.drawBitmap(slice, null, android.graphics.RectF(16f, 16f, 16f + destW, 16f + destH), paint)
            doc.finishPage(page)
            slice.recycle()
            y += h
            pageIndex++
        }
        patternBmp.recycle()

        // 清单页
        val listPage = doc.startPage(
            PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex + 1).create(),
        )
        val lp = Paint().apply {
            color = android.graphics.Color.BLACK
            isAntiAlias = true
        }
        var ty = 40f
        for (line in ShoppingList.render(result, projectName).lines()) {
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
        val name = fileName(result, "print", "pdf")
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
            color = android.graphics.Color.parseColor("#1A1A1A")
            isFakeBoldText = true
        }
        val titleSize = (titleHeight * 0.42f).coerceAtLeast(10f)
        val subSize = (titleHeight * 0.30f).coerceAtLeast(8f)

        paint.textSize = titleSize
        canvas.drawText(title, titleHeight * 0.2f, titleHeight * 0.48f, paint)

        paint.isFakeBoldText = false
        paint.color = android.graphics.Color.parseColor("#6A6A6A")
        paint.textSize = subSize
        canvas.drawText(subtitle, titleHeight * 0.2f, titleHeight * 0.85f, paint)

        // 标题栏与图纸之间的分隔线
        val line = Paint().apply {
            color = android.graphics.Color.parseColor("#33000000")
            strokeWidth = 1f
        }
        canvas.drawLine(0f, titleHeight.toFloat(), bitmap.width.toFloat(), titleHeight.toFloat(), line)
    }

    /** 分享纯文本清单 */
    fun shareList(context: Context, result: PatternResult, projectName: String) {
        val text = ShoppingList.render(result, projectName)
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
        result.width <= 32 -> 64
        result.width <= 64 -> 40
        result.width <= 100 -> 28
        else -> 20
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