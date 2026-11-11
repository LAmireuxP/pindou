package com.pindou.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pindou.app.core.pattern.PixelCalibration
import com.pindou.app.ui.shadcn.Button
import com.pindou.app.ui.shadcn.OutlinedButton
import com.pindou.app.ui.shadcn.ShCard
import com.pindou.app.ui.shadcn.TextButton
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 像素图校准页。
 *
 * 导入后的自动识别只给初值。用户能看到真实网格压在图上，按方向键
 * 移网格、改行列和格宽；智能对齐按格线梯度吸附，阈值决定多强的
 * 格线信号才允许覆盖手动网格。
 */
@Composable
fun PixelCalibrateScreen(vm: AppViewModel) {
    val bitmap = vm.imagePreview
    val grid = vm.pixelCalibration
    val imgW = vm.imageW
    val imgH = vm.imageH
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
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
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.padding(start = Spacing.xs)) {
                Text(
                    "校准像素网格",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (vm.pixelCalibrationDetected) "网格已识别，可手动微调" else "未识别到规则格线，请手动对齐",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { vm.confirmPixelCalibration() },
                enabled = grid != null && !vm.pixelCalibrationBusy,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(if (vm.pixelCalibrationBusy) "处理中…" else "确认")
            }
        }

        if (bitmap == null || grid == null || imgW <= 0 || imgH <= 0) {
            Text(
                "没有可校准的像素图，请重新导入",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.md),
            )
            OutlinedButton(
                onClick = { vm.backToHome() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md),
            ) {
                Text("返回首页")
            }
            return@Column
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(356.dp)
                .padding(horizontal = Spacing.md)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0E1419))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .onSizeChanged { canvasSize = it }
                // 手势挂在整个画布上，不挂图片：图片四周的留白也能拖，
                // 手指不用非得压到像素块上。key 不能带 grid，否则拖动时
                // 协程重启，一整段拖动只有第一帧生效。
                .pointerInput(canvasSize, imgW, imgH) {
                    val g = vm.pixelCalibration ?: return@pointerInput
                    if (canvasSize.width == 0 || canvasSize.height == 0) return@pointerInput
                    val fit = min(
                        canvasSize.width.toFloat() / imgW,
                        canvasSize.height.toFloat() / imgH,
                    )
                    if (fit <= 0f || g.cols < 1) return@pointerInput
                    detectDragGestures { change, drag ->
                        change.consume()
                        // 手指往右拖，网格跟着往右走，位移换算回源图像素
                        vm.pixelCalibrationNudge(
                            drag.x.toDouble() / fit,
                            drag.y.toDouble() / fit,
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (canvasSize.width > 0 && canvasSize.height > 0) {
                val fit = min(
                    canvasSize.width.toFloat() / imgW,
                    canvasSize.height.toFloat() / imgH,
                )
                if (fit > 0f) {
                    val density = LocalDensity.current
                    val dispW = with(density) { (imgW * fit).toDp() }
                    val dispH = with(density) { (imgH * fit).toDp() }
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Box(
                        modifier = Modifier
                            .size(dispW, dispH),
                    ) {
                        Image(
                            bitmap = image,
                            contentDescription = "像素图画布",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.None,
                        )
                        CalibrationGridOverlay(
                            grid = grid,
                            scale = fit,
                            imgW = imgW,
                            imgH = imgH,
                        )
                    }
                }
            }
            Text(
                "拖动图片移动网格",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.86f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.46f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }

        Text(
            "${grid.cols}×${grid.rows} 格 · 单格 ${formatCell(grid.cellW)}×${formatCell(grid.cellH)} px" +
                " · 偏移 ${formatCell(grid.offsetX)}, ${formatCell(grid.offsetY)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
        )

        ShCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.sm),
        ) {
            Column(
                Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("网格位置", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "每按一次移动 1 像素，也可直接拖动图片",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NudgeButton(Icons.Rounded.KeyboardArrowLeft, "左移") {
                            vm.pixelCalibrationNudge(-1.0, 0.0)
                        }
                        NudgeButton(Icons.Rounded.ArrowUpward, "上移") {
                            vm.pixelCalibrationNudge(0.0, -1.0)
                        }
                        NudgeButton(Icons.Rounded.ArrowDownward, "下移") {
                            vm.pixelCalibrationNudge(0.0, 1.0)
                        }
                        NudgeButton(Icons.Rounded.KeyboardArrowRight, "右移") {
                            vm.pixelCalibrationNudge(1.0, 0.0)
                        }
                    }
                }

                CalibrationDivider()
                CalibrationStepper(
                    label = "网格列数",
                    value = grid.cols,
                    suffix = "列",
                    onMinus = { vm.pixelCalibrationSetCols(grid.cols - 1) },
                    onPlus = { vm.pixelCalibrationSetCols(grid.cols + 1) },
                )
                CalibrationStepper(
                    label = "网格行数",
                    value = grid.rows,
                    suffix = "行",
                    onMinus = { vm.pixelCalibrationSetRows(grid.rows - 1) },
                    onPlus = { vm.pixelCalibrationSetRows(grid.rows + 1) },
                )
                CalibrationStepper(
                    label = "单元格大小",
                    value = formatCell(grid.cellW),
                    suffix = "px",
                    onMinus = { vm.pixelCalibrationSetCellSize((grid.cellW - 1.0).toFloat()) },
                    onPlus = { vm.pixelCalibrationSetCellSize((grid.cellW + 1.0).toFloat()) },
                )

                CalibrationDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("智能对齐", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when {
                                vm.pixelRefineRunning -> "正在贴合网格线…"
                                vm.pixelCalibrationApplied -> "已吸附到实际格线"
                                vm.pixelCalibrationSmart -> "按格线梯度自动微调"
                                else -> "关闭时完全使用手动网格"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = vm.pixelCalibrationSmart,
                        onCheckedChange = { vm.pixelCalibrationSetSmart(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClayColors.Primary,
                        ),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("对齐阈值", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "${(vm.pixelCalibrationThreshold * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = ClayColors.PrimaryInk,
                    )
                }
                Slider(
                    value = vm.pixelCalibrationThreshold,
                    onValueChange = { vm.pixelCalibrationSetThreshold(it) },
                    valueRange = 0.01f..0.40f,
                    steps = 38,
                    colors = SliderDefaults.colors(
                        thumbColor = ClayColors.Primary,
                        activeTrackColor = ClayColors.Primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "当前格线强度 ${(vm.pixelCalibrationScore * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            if (vm.pixelCalibrationSmart &&
                                vm.pixelCalibrationScore > 0f &&
                                vm.pixelCalibrationScore < vm.pixelCalibrationThreshold
                            ) {
                                "低于阈值，确认时保留手动网格"
                            } else {
                                "阈值越高，越不容易改动手动网格"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { vm.pixelCalibrationAutoAlign() },
                        enabled = !vm.pixelRefineRunning,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(if (vm.pixelRefineRunning) "对齐中…" else "自动对齐")
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.pixelCalibrationReset() }) {
                Text("重新检测")
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { vm.confirmPixelCalibration() },
                enabled = !vm.pixelCalibrationBusy,
                modifier = Modifier.height(48.dp),
            ) {
                Text(if (vm.pixelCalibrationBusy) "正在生成…" else "确认网格，生成图纸")
            }
        }
    }
}

@Composable
private fun CalibrationGridOverlay(
    grid: PixelCalibration.Grid,
    scale: Float,
    imgW: Int,
    imgH: Int,
) {
    Canvas(Modifier.fillMaxSize()) {
        val w = imgW * scale
        val h = imgH * scale
        val halo = Color.Black.copy(alpha = 0.50f)
        val lineColor = ClayColors.Primary

        fun vertical(x: Float, width: Float) {
            if (x < -1f || x > w + 1f) return
            drawLine(halo, Offset(x, 0f), Offset(x, h), width + 2.2f)
            drawLine(lineColor, Offset(x, 0f), Offset(x, h), width)
        }
        fun horizontal(y: Float, width: Float) {
            if (y < -1f || y > h + 1f) return
            drawLine(halo, Offset(0f, y), Offset(w, y), width + 2.2f)
            drawLine(lineColor, Offset(0f, y), Offset(w, y), width)
        }

        // 格子太密时隔几格画一条，保证线距不小于 4px；否则 1:1 像素画
        // 在校准页上只剩一个外框，用户看不到自己的格子。
        val stepW = grid.cellW.toFloat() * scale
        val stepH = grid.cellH.toFloat() * scale
        val skipW = if (stepW > 0f) kotlin.math.ceil(4.0 / stepW).toInt().coerceAtLeast(1) else 1
        val skipH = if (stepH > 0f) kotlin.math.ceil(4.0 / stepH).toInt().coerceAtLeast(1) else 1
        for (k in 0..grid.cols step skipW) {
            val major = k % 10 == 0
            vertical(
                (grid.offsetX + k * grid.cellW).toFloat() * scale,
                if (major) 1.7.dp.toPx() else 0.8.dp.toPx(),
            )
        }
        for (k in 0..grid.rows step skipH) {
            val major = k % 10 == 0
            horizontal(
                (grid.offsetY + k * grid.cellH).toFloat() * scale,
                if (major) 1.7.dp.toPx() else 0.8.dp.toPx(),
            )
        }

        val left = (grid.offsetX * scale).toFloat()
        val top = (grid.offsetY * scale).toFloat()
        val right = (grid.right * scale).toFloat()
        val bottom = (grid.bottom * scale).toFloat()
        drawRect(
            color = Color.White.copy(alpha = 0.92f),
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            style = Stroke(3.dp.toPx()),
        )
        drawRect(
            color = ClayColors.Primary,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            style = Stroke(1.5.dp.toPx()),
        )
    }
}

@Composable
private fun NudgeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CalibrationStepper(
    label: String,
    value: Int,
    suffix: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    CalibrationStepperRow(label, value.toString(), suffix, onMinus, onPlus)
}

@Composable
private fun CalibrationStepper(
    label: String,
    value: String,
    suffix: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    CalibrationStepperRow(label, value, suffix, onMinus, onPlus)
}

@Composable
private fun CalibrationStepperRow(
    label: String,
    value: String,
    suffix: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onMinus,
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("−", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                "$value $suffix",
                modifier = Modifier.width(82.dp),
                style = MaterialTheme.typography.labelLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            OutlinedButton(
                onClick = onPlus,
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("+", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CalibrationDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

private fun formatCell(value: Double): String =
    if (abs(value - value.roundToInt()) < 0.05) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
