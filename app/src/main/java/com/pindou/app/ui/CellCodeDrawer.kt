package com.pindou.app.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import kotlin.math.roundToInt

/**
 * 图纸格子上的色号标注统一绘制。
 *
 * 设计要点（解决"色号不显示"与"色号可读性"两个问题）：
 * - 色号标签**居中**放在格子正中，半透明底色按色块明暗自动选白/黑，压在颜色上也读得清；
 * - 字号同时满足「宽度不超出格子」和「高度不超过格子 32%」两个约束，
 *   只要格子 ≥ [MIN_CELL_PX] 就**一定画**（不再因差几像素而跳过）；
 * - 字号取整：调用方传入 [缓存][layoutCache] 时按 (色号,整数字号) 复用布局结果，
 *   捏合缩放时避免每帧上千次文本测量（预览画布在用）。
 */
object CellCodeDrawer {

    /** 小于该像素尺寸不绘制色号（画了也是糊的，此时以颜色清晰为先） */
    const val MIN_CELL_PX = 26f

    fun DrawScope.drawCellCode(
        measurer: TextMeasurer,
        code: String,
        left: Float,
        top: Float,
        cell: Float,
        argb: Int,
        layoutCache: MutableMap<Pair<String, Int>, TextLayoutResult>? = null,
    ) {
        if (cell < MIN_CELL_PX) return

        // 字号：同时满足宽和高约束
        //  - 宽度：code 宽 ≈ 字号 × 0.58 × 字符数，要 ≤ 格宽 × 0.88
        //  - 高度：字号 ≤ 格高 × 0.32
        val fitByWidth = cell * 0.88f / (code.length.coerceAtLeast(1) * 0.58f)
        val fitByHeight = cell * 0.32f
        val textPx = minOf(fitByWidth, fitByHeight).coerceIn(7f, 22f).roundToInt().coerceAtLeast(7)

        val chipHeight = (textPx * 1.4f).coerceAtMost(cell * 0.38f)
        val chipTop = top + (cell - chipHeight) / 2f

        val light = isLight(argb)
        val chipColor = if (light) Color.White.copy(alpha = 0.78f) else Color.Black.copy(alpha = 0.52f)
        val textColor = if (light) Color(0xFF1A1A1A) else Color(0xFFF7F7F7)

        // Density.toSp() 正确换算像素→sp（直接塞 Sp 会被再乘密度）
        val style = TextStyle(fontSize = textPx.toSp(), color = textColor)
        val measured = if (layoutCache != null) {
            layoutCache.getOrPut(code to textPx) { measurer.measure(code, style) }
        } else {
            measurer.measure(code, style)
        }

        drawRoundRect(
            color = chipColor,
            topLeft = Offset(left + cell * 0.04f, chipTop),
            size = Size(cell - cell * 0.08f, chipHeight),
            cornerRadius = CornerRadius(chipHeight * 0.30f),
        )
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                left + (cell - measured.size.width) / 2f,
                chipTop + (chipHeight - measured.size.height) / 2f,
            ),
        )
    }

    /** 背景色是否偏亮 */
    fun isLight(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r * 299 + g * 587 + b * 114 > 128 * 1000
    }
}
