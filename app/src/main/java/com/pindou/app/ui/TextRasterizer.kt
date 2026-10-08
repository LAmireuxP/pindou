package com.pindou.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.roundToInt

/**
 * 文字拼豆：把文字栅格化为位图后交给常规管线处理。
 * 渲染为「黑字白底」，背景移除可配合使用（自动去掉白底）。
 */
object TextRasterizer {

    /**
     * @param text 文字内容（支持中英文与 emoji，取决于系统字体）
     * @param outWidth/outHeight 输出位图尺寸（按此比例排版，管线再降采样到网格）
     */
    fun rasterize(text: String, outWidth: Int, outHeight: Int): Bitmap {
        // 位图按网格数放大，保证字形有足够细节供降采样。
        // 注意：宽高必须按同一系数整体钳制到 2048 以内——分别钳制会破坏
        // 宽高比，导致画布预览与按网格降采样出的图纸不对应（文字被拉伸）。
        val scale = 32
        val rawW = outWidth.coerceIn(5, 200) * scale
        val rawH = outHeight.coerceIn(5, 200) * scale
        val k = minOf(1.0, 2048.0 / maxOf(rawW, rawH))
        // 至少保留每格 1px（极端小系数下兜底），比例不变
        val w = (rawW * k).roundToInt().coerceAtLeast(outWidth.coerceIn(5, 200))
        val h = (rawH * k).roundToInt().coerceAtLeast(outHeight.coerceIn(5, 200))

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val lines = text.split('\n').filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
        // 逐行缩放：以"最长实际宽度 × 行数"决定字号，占满画布 85%。
        // 不能用固定 0.62 字符宽度系数——中文全角宽度 ≈ 1.0×字号，粗体更宽，
        // 旧算法会让中文行超出画布左右被裁。这里先按高度估字号，再用 measureText
        // 实测最宽行，超宽则按比例回缩，保证任何字符集都不越界。
        val byHeight = (h * 0.85f) / (lines.size * 1.25f)
        paint.textSize = byHeight.coerceAtLeast(8f)
        val widest = lines.maxByOrNull { paint.measureText(it) } ?: lines[0]
        val measured = paint.measureText(widest)
        if (measured > w * 0.85f) {
            paint.textSize = (paint.textSize * (w * 0.85f) / measured).coerceAtLeast(8f)
        }

        val metrics = paint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) * 1.15f
        val totalHeight = lineHeight * lines.size
        var baseline = (h - totalHeight) / 2f - metrics.ascent

        for (line in lines) {
            canvas.drawText(line, w / 2f, baseline, paint)
            baseline += lineHeight
        }
        return bitmap
    }
}