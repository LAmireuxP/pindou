package com.pindou.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import com.pindou.app.core.chart.ChartRecognizer
import com.pindou.app.ui.shadcn.Button
import com.pindou.app.ui.shadcn.ShCard
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 色号图校准页。
 *
 * 红线压在图纸的格线上，拖不动就按方向键；
 * 列/行数决定网格密度，格子大小决定一格占多少源图像素；智能对齐按梯度
 * 把线吸到真实格线上。底下实时显示采到多少个色块，调错了一眼能看出来。
 */
@Composable
fun ChartCalibrateScreen(vm: AppViewModel) {
    val bitmap = vm.chartBitmap
    val grid = vm.chartGrid
    val imgW = vm.chartW
    val imgH = vm.chartH
    val sample = vm.chartSample

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // 整页可滚：控件卡不设 maxHeight，避免「空白阈值」被卡片边缘切一半
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { vm.handleBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.padding(start = Spacing.xs)) {
                Text("校准网格", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (vm.chartDetected) "红线压住图纸的格线" else "自动识别失败，请手动调列数",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { vm.chartToPattern() },
                enabled = sample != null && !vm.chartBusy && (sample?.filledCells ?: 0) > 0,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("生成图纸", style = MaterialTheme.typography.labelLarge)
            }
        }

        // ---- 图纸 + 红线网格 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .padding(horizontal = Spacing.md)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0E1419))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .onSizeChanged { canvasSize = it }
                // key 不能带 grid：拖动会改 grid，key 一变手势协程就被重启，
                // 一整段拖动只生效第一帧，表现为"拖不动"。只按画布尺寸重建。
                .pointerInput(canvasSize, imgW, imgH) {
                    if (vm.chartGrid == null || canvasSize.width == 0) return@pointerInput
                    val fit = min(
                        canvasSize.width.toFloat() / imgW,
                        canvasSize.height.toFloat() / imgH,
                    )
                    if (fit <= 0f) return@pointerInput
                    detectDragGestures { change, drag ->
                        change.consume()
                        vm.chartMoveBy(drag.x / fit, drag.y / fit)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null && grid != null && canvasSize.width > 0 && canvasSize.height > 0) {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                ChartCanvas(image, imgW, imgH, grid, canvasSize)
            } else if (bitmap != null) {
                Text("正在识别…", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(
            text = if (vm.chartBusy) "采样中…" else
                "已识别 ${sample?.filledCells ?: 0} 个色块 · 空格 ${sample?.let { it.cols * it.rows - it.filledCells } ?: 0}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
        )

        // ---- 校准控件 ----
        ShCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.md),
        ) {
            Column(
                modifier = Modifier
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (grid != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("网格位置", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "拖动图纸也可移动",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NudgeButton(Icons.Rounded.KeyboardArrowLeft, "左移") { vm.chartNudge(-1, 0) }
                            NudgeButton(Icons.Rounded.ArrowUpward, "上移") { vm.chartNudge(0, -1) }
                            NudgeButton(Icons.Rounded.ArrowDownward, "下移") { vm.chartNudge(0, 1) }
                            NudgeButton(Icons.Rounded.KeyboardArrowRight, "右移") { vm.chartNudge(1, 0) }
                        }
                    }

                    Divider()
                    Stepper("网格列数", grid.cols, 3, 400) { vm.chartSetCols(it) }
                    Stepper("网格行数", grid.rows, 3, 400) { vm.chartSetRows(it) }
                    Stepper("单元格大小", grid.cellW.roundToInt().coerceAtLeast(1), 4, 512, suffix = "px") {
                        vm.chartSetCellSize(it.toFloat())
                    }

                    Divider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("智能对齐", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (vm.chartRefineRunning) "正在贴合网格线…" else "按格线梯度自动微调",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = vm.chartAutoAligned,
                            onCheckedChange = { on ->
                                if (on) vm.chartRefine() else vm.toast = "已关闭智能对齐"
                            },
                            enabled = !vm.chartRefineRunning,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = ClayColors.Primary,
                            ),
                        )
                    }

                    Divider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("空白阈值", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${(vm.chartThreshold * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = vm.chartThreshold,
                        onValueChange = { vm.chartSetThreshold(it) },
                        valueRange = 0.2f..0.95f,
                        colors = SliderDefaults.colors(
                            thumbColor = ClayColors.Primary,
                            activeTrackColor = ClayColors.Primary,
                        ),
                    )
                    Text(
                        "白底图纸里，白色豆子和纸背景同色：调高阈值会把白豆也判成空格，调低会把纸边判成色块。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartCanvas(
    image: ImageBitmap,
    imgW: Int,
    imgH: Int,
    grid: ChartRecognizer.ChartGrid,
    canvasSize: IntSize,
) {
    val lineColor = Color(0xFFEF4444)
    Canvas(Modifier.fillMaxSize()) {
        val fit = min(size.width / imgW, size.height / imgH)
        val dstW = (imgW * fit).roundToInt()
        val dstH = (imgH * fit).roundToInt()
        val ox = (size.width - dstW) / 2f
        val oy = (size.height - dstH) / 2f
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(imgW, imgH),
            dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
            dstSize = IntSize(dstW, dstH),
            filterQuality = FilterQuality.None,
        )
        val stroke = 1.dp.toPx()
        // 只画落进图内的线：网格挪出去以后不画一屏没用的红线
        for (k in 0..grid.cols) {
            val x = ox + (grid.offsetX + k * grid.cellW).toFloat() * fit
            if (x >= ox && x <= ox + dstW) {
                drawLine(lineColor, Offset(x, oy), Offset(x, oy + dstH), stroke)
            }
        }
        for (k in 0..grid.rows) {
            val y = oy + (grid.offsetY + k * grid.cellH).toFloat() * fit
            if (y >= oy && y <= oy + dstH) {
                drawLine(lineColor, Offset(ox, y), Offset(ox + dstW, y), stroke)
            }
        }
    }
}

@Composable
private fun NudgeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    suffix: String = "",
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBtn("−") { onChange((value - 1).coerceAtLeast(min)) }
            Text(
                text = "$value$suffix",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(64.dp),
            )
            StepBtn("＋") { onChange((value + 1).coerceAtMost(max)) }
        }
    }
}

@Composable
private fun StepBtn(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}
