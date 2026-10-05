package com.pindou.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import com.pindou.app.core.pattern.PatternResult
import com.pindou.app.ui.CellCodeDrawer.drawCellCode

/** 空格底色（棋盘浅灰） */
private val EmptyCellA = Color(0xFFEFEFEA)
private val EmptyCellB = Color(0xFFE2E2DC)

/**
 * 图纸画布：按格绘制色卡颜色 + 网格线 + 色号标注。
 * 色号绘制在格子下沿标签内（见 [CellCodeDrawer]），不会遮住颜色块
 */
@Composable
fun PatternCanvas(
    result: PatternResult,
    showCodes: Boolean,
    modifier: Modifier = Modifier,
    cellMinPxForCodes: Float = CellCodeDrawer.MIN_CELL_PX,
) {
    val measurer = rememberTextMeasurer()

    Canvas(modifier.fillMaxSize()) {
        val cell = size.width / result.width
        val drawCodes = showCodes && cell >= cellMinPxForCodes
        for (y in 0 until result.height) {
            for (x in 0 until result.width) {
                val idx = result.cells[y * result.width + x]
                val left = x * cell
                val top = y * cell
                val cellColor = if (idx >= 0) {
                    Color(result.palette.colors[idx].argb)
                } else {
                    if ((x + y) % 2 == 0) EmptyCellA else EmptyCellB
                }
                drawRect(
                    color = cellColor,
                    topLeft = Offset(left, top),
                    size = Size(cell, cell),
                )
                if (drawCodes && idx >= 0) {
                    drawCellCode(
                        measurer = measurer,
                        code = result.palette.colors[idx].code,
                        left = left,
                        top = top,
                        cell = cell,
                        argb = result.palette.colors[idx].argb,
                    )
                }
            }
        }
        val line = Color.Black.copy(alpha = 0.12f)
        for (i in 0..result.width) {
            drawLine(line, Offset(i * cell, 0f), Offset(i * cell, size.height), 1f)
        }
        for (i in 0..result.height) {
            drawLine(line, Offset(0f, i * cell), Offset(size.width, i * cell), 1f)
        }
    }
}
