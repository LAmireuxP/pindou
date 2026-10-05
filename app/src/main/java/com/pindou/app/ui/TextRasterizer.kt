package com.pindou.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

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
        // 位图按网格数放大，保证字形有足够细节供降采样
        val scale = 32
        val w = (outWidth.coerceIn(5, 200) * scale).coerceAtMost(2048)
        val h = (outHeight.coerceIn(5, 200) * scale).coerceAtMost(2048)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val lines = text.split('\n').filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
        // 逐行缩放：以"最长行 × 行数"决定字号，占满画布 85%
        val maxLine = lines.maxOf { it.length }.coerceAtLeast(1)
        val byWidth = (w * 0.85f) / (maxLine * 0.62f)
        val byHeight = (h * 0.85f) / (lines.size * 1.25f)
        paint.textSize = minOf(byWidth, byHeight).coerceAtLeast(8f)

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