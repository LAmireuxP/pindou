package com.pindou.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.pindou.app.ui.CellCodeDrawer

/**
 * 色卡弹层里的单个色块。
 *
 * 整格只用一个 Canvas：底色、描边、选中勾和色号都在一次绘制里完成。
 * 色卡有 291 色，弹层滑动时新进入视口的行如果每格都搭
 * Column -> Box -> Text 三层布局，首帧组合预算会被吃满；单节点能把
 * 这部分固定开销压掉，滑动时只做绘制。
 *
 * 色号布局按「色号」缓存在 [textCache] 里：滑走再滑回来不重新排版。
 */
@Composable
internal fun PaletteSwatch(
    code: String,
    name: String?,
    argb: Int,
    selected: Boolean,
    measurer: TextMeasurer,
    textStyle: TextStyle,
    textCache: MutableMap<String, TextLayoutResult>,
    primaryColor: Color,
    onClick: () -> Unit,
) {
    val codeLayout = remember(code, measurer, textStyle) {
        textCache.getOrPut(code) { measurer.measure(code, style = textStyle) }
    }
    val light = remember(argb) { CellCodeDrawer.isLight(argb) }
    val description = remember(code, name) {
        if (name.isNullOrBlank()) code else "$code $name"
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
    ) {
        val swatchSize = 50.dp.toPx()
        val swatchLeft = (size.width - swatchSize) / 2f
        val swatchTop = 2.dp.toPx()
        val corner = 10.dp.toPx()

        drawRoundRect(
            color = Color(argb),
            topLeft = Offset(swatchLeft, swatchTop),
            size = Size(swatchSize, swatchSize),
            cornerRadius = CornerRadius(corner),
        )
        drawRoundRect(
            color = if (selected) primaryColor else Color.Black.copy(alpha = 0.14f),
            topLeft = Offset(swatchLeft, swatchTop),
            size = Size(swatchSize, swatchSize),
            cornerRadius = CornerRadius(corner),
            style = Stroke(width = if (selected) 2.5.dp.toPx() else 1.dp.toPx()),
        )

        if (selected) {
            val cx = swatchLeft + swatchSize / 2f
            val cy = swatchTop + swatchSize / 2f
            val check = Path().apply {
                moveTo(cx - 7.dp.toPx(), cy)
                lineTo(cx - 2.dp.toPx(), cy + 5.dp.toPx())
                lineTo(cx + 8.dp.toPx(), cy - 6.dp.toPx())
            }
            drawPath(
                path = check,
                color = if (light) Color(0xFF18181B) else Color.White,
                style = Stroke(
                    width = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        drawText(
            textLayoutResult = codeLayout,
            topLeft = Offset(
                x = (size.width - codeLayout.size.width) / 2f,
                y = swatchTop + swatchSize + 2.dp.toPx(),
            ),
        )
    }
}



