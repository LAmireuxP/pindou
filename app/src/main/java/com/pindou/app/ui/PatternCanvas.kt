package com.pindou.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import com.pindou.app.core.pattern.PatternResult
import com.pindou.app.ui.CellCodeDrawer.drawCellCode
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 空格底色（棋盘浅灰） */
private val EmptyCellA = Color(0xFFEFEFEA)
private val EmptyCellB = Color(0xFFE2E2DC)

/** 预览画布的缩放/平移状态（换图纸时重建） */
private class PreviewTransform {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

/**
 * 图纸画布：按格绘制色卡颜色 + 网格线 + 色号标注，支持双指捏合缩放（1~20 倍）与平移。
 *
 * - 色号画在格子下沿标签内（见 [CellCodeDrawer]），格子放大到 ≥26px 时自动出现，
 *   解决"大图纸预览时色号看不清"的问题；
 * - 单指拖动不消费（交给外层页面滚动），双指才进入缩放/平移；双击复原；
 * - 只绘制可见格，放大后不会全量重绘；色号文字布局按 (色号,整数字号) 缓存。
 */
@Composable
fun PatternCanvas(
    result: PatternResult,
    showCodes: Boolean,
    modifier: Modifier = Modifier,
    cellMinPxForCodes: Float = CellCodeDrawer.MIN_CELL_PX,
) {
    val measurer = rememberTextMeasurer()
    val layoutCache = remember { mutableMapOf<Pair<String, Int>, TextLayoutResult>() }
    val transform = remember(result) { PreviewTransform() }

    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(result) {
                detectTapGestures(onDoubleTap = {
                    transform.scale = 1f
                    transform.offset = Offset.Zero
                })
            }
            .pointerInput(result) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var prevDist = 0f
                    var prevCentroid = Offset.Zero
                    var isTransform = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // 双指：以质心为缩放中心捏合，并跟随质心平移
                            isTransform = true
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                            val dist = hypot(
                                (a.x - b.x).toDouble(),
                                (a.y - b.y).toDouble(),
                            ).toFloat()
                            if (prevDist > 0f) {
                                val old = transform.scale
                                val new = (old * (dist / prevDist)).coerceIn(1f, 20f)
                                val k = new / old
                                transform.offset = Offset(
                                    centroid.x - (centroid.x - transform.offset.x) * k,
                                    centroid.y - (centroid.y - transform.offset.y) * k,
                                )
                                transform.scale = new
                            }
                            if (prevCentroid != Offset.Zero) {
                                transform.offset += centroid - prevCentroid
                            }
                            prevDist = dist
                            prevCentroid = centroid
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (isTransform) {
                            // 缩放后余下的单指继续平移画布
                            val c = pressed[0]
                            transform.offset += c.position - c.previousPosition
                            if (c.positionChanged()) c.consume()
                            prevDist = 0f
                            prevCentroid = Offset.Zero
                        } else {
                            // 纯单指：不消费，让外层页面正常滚动
                            prevDist = 0f
                            prevCentroid = Offset.Zero
                        }
                        // 平移钳制：内容始终铺满画布（1 倍时自动回正）
                        val s = transform.scale
                        transform.offset = Offset(
                            transform.offset.x.coerceIn(size.width - size.width * s, 0f),
                            transform.offset.y.coerceIn(size.height - size.height * s, 0f),
                        )
                    }
                }
            },
    ) {
        val cell = size.width / result.width * transform.scale
        val origin = transform.offset

        // 只算可见格范围（放大时避免全量重绘）
        val firstX = max(0, (-origin.x / cell).toInt())
        val firstY = max(0, (-origin.y / cell).toInt())
        val lastX = min(result.width - 1, ((size.width - origin.x) / cell).toInt() + 1)
        val lastY = min(result.height - 1, ((size.height - origin.y) / cell).toInt() + 1)

        for (y in firstY..lastY) {
            for (x in firstX..lastX) {
                val idx = result.cells[y * result.width + x]
                val left = origin.x + x * cell
                val top = origin.y + y * cell
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
            }
        }
        val line = Color.Black.copy(alpha = 0.12f)
        for (i in firstX..lastX + 1) {
            val px = origin.x + i * cell
            drawLine(line, Offset(px, origin.y + firstY * cell), Offset(px, origin.y + (lastY + 1) * cell), 1f)
        }
        for (i in firstY..lastY + 1) {
            val py = origin.y + i * cell
            drawLine(line, Offset(origin.x + firstX * cell, py), Offset(origin.x + (lastX + 1) * cell, py), 1f)
        }
        if (showCodes && cell >= cellMinPxForCodes) {
            for (y in firstY..lastY) {
                for (x in firstX..lastX) {
                    val idx = result.cells[y * result.width + x]
                    if (idx < 0) continue
                    drawCellCode(
                        measurer = measurer,
                        code = result.palette.colors[idx].code,
                        left = origin.x + x * cell,
                        top = origin.y + y * cell,
                        cell = cell,
                        argb = result.palette.colors[idx].argb,
                        layoutCache = layoutCache,
                    )
                }
            }
        }
    }
}
