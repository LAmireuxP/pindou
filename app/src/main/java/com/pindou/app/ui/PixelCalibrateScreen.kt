package com.pindou.app.ui

import com.pindou.app.ui.icon.MorphIcons
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pindou.app.core.pattern.PixelCalibration
import com.pindou.app.core.pattern.PixelGridInspector
import com.pindou.app.ui.shadcn.TextButton
import com.pindou.app.ui.theme.ClayColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class CalibField { COLS, ROWS, CELL }

/**
 * 像素画导入的校准页。
 *
 * 这一页的可取之处不是参数多，而是顺序明确：
 * 红线定位 → 改列数 → 改一格大小。这里保留这个顺序，把原先
 * 三列滚轮、键盘图标和识别指标压成一条操作路径，图片区不再有
 * 白色卡片边框，用户看到的是一张图和一张可操作的网格。
 */
@Composable
fun PixelCalibrateScreen(vm: AppViewModel) {
    val bitmap = vm.imagePreview
    val grid = vm.pixelCalibration
    val analysis = vm.pixelGridAnalysis
    val conversion = vm.pixelCalibrationPreview
    val imgW = vm.imageW
    val imgH = vm.imageH

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var sheetHeightPx by remember { mutableIntStateOf(0) }
    var zoom by remember(bitmap) { mutableStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var showPreview by remember(bitmap) { mutableStateOf(false) }
    var editField by remember { mutableStateOf<CalibField?>(null) }

    val density = LocalDensity.current
    val sheetPx = if (sheetHeightPx > 0) {
        sheetHeightPx
    } else {
        with(density) { 286.dp.roundToPx() }
    }
    val canvasW = canvasSize.width.toFloat()
    val canvasH = canvasSize.height.toFloat()
    val stageBottom = (canvasH - sheetPx).coerceAtLeast(canvasH * 0.26f)
    val visibleH = (stageBottom - with(density) { 18.dp.toPx() }).coerceAtLeast(1f)
    val viewCenter = Offset(canvasW / 2f, visibleH / 2f)
    val baseFit = if (imgW > 0 && imgH > 0 && canvasW > 0f && visibleH > 0f) {
        min(
            (canvasW - with(density) { 28.dp.toPx() }) / imgW,
            (visibleH - with(density) { 24.dp.toPx() }) / imgH,
        )
    } else {
        1f
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ClayColors.Canvas),
    ) {
        CalibrationTopBar(vm)

        if (vm.pixelCalibrationBusy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = ClayColors.Primary,
                trackColor = ClayColors.HairlineSoft,
            )
        }

        CalibrationGuide(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 6.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { canvasSize = it },
        ) {
            if (bitmap == null || grid == null || imgW <= 0 || imgH <= 0) {
                EmptyCalibrationState(vm)
            } else {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                // 预览直接铺「完成」时那张图：色号、空格、背景移除全部一致。
                // 网格对不上（刚拖动、还没算完）就先不显示，避免拿旧结果骗人。
                val previewBitmap = remember(conversion, grid) {
                    val argb = conversion?.previewArgb
                    if (conversion == null || conversion.grid != grid ||
                        argb == null || argb.size != grid.cols * grid.rows
                    ) {
                        null
                    } else {
                        val pixels = IntArray(argb.size) { i ->
                            if (argb[i] == 0) 0xFFFFFFFF.toInt() else argb[i]
                        }
                        Bitmap.createBitmap(
                            pixels,
                            grid.cols,
                            grid.rows,
                            Bitmap.Config.ARGB_8888,
                        )
                    }
                }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .background(ClayColors.Stage)
                        .pointerInput(bitmap, canvasSize, sheetHeightPx) {
                            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                val old = zoom
                                val next = (old * zoomChange).coerceIn(0.8f, 12f)
                                // 缩放围绕双指中心；单指拖动只改平移。
                                val q = centroid - viewCenter
                                var nextPan = q - (q - pan) * (next / old) + panChange
                                val drawW = imgW * baseFit * next
                                val drawH = imgH * baseFit * next
                                val margin = with(density) { 36.dp.toPx() }
                                val boundX = (max(canvasW, drawW) / 2f + margin)
                                val boundY = (max(visibleH, drawH) / 2f + margin)
                                nextPan = Offset(
                                    x = nextPan.x.coerceIn(-boundX, boundX),
                                    y = nextPan.y.coerceIn(-boundY, boundY),
                                )
                                zoom = next
                                pan = nextPan
                            }
                        },
                ) {
                    val scale = baseFit * zoom
                    val drawW = imgW * scale
                    val drawH = imgH * scale
                    val left = viewCenter.x + pan.x - drawW / 2f
                    val top = viewCenter.y + pan.y - drawH / 2f
                    val right = left + drawW
                    val bottom = top + drawH

                    val cellPxW = grid.cellW.toFloat() * scale
                    val cellPxH = grid.cellH.toFloat() * scale
                    val originX = left + grid.offsetX.toFloat() * scale
                    val originY = top + grid.offsetY.toFloat() * scale
                    val gridW = grid.cols * cellPxW
                    val gridH = grid.rows * cellPxH

                    if (showPreview && previewBitmap != null) {
                        // 预览模式：直接铺采样后的格子颜色，空白格用白底，
                        // 用户看到的就是「完成」之后会生成的图纸。
                        drawImage(
                            image = previewBitmap.asImageBitmap(),
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(previewBitmap.width, previewBitmap.height),
                            dstOffset = IntOffset(
                                originX.roundToInt(),
                                originY.roundToInt(),
                            ),
                            dstSize = IntSize(
                                gridW.roundToInt().coerceAtLeast(1),
                                gridH.roundToInt().coerceAtLeast(1),
                            ),
                            filterQuality = FilterQuality.None,
                        )
                    } else {
                        // 不铺白色底板：图片本身就是画布，网格负责说明边界。
                        drawImage(
                            image = image,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(
                                drawW.roundToInt().coerceAtLeast(1),
                                drawH.roundToInt().coerceAtLeast(1),
                            ),
                            filterQuality = FilterQuality.None,
                        )
                    }
                    val minor = Color(0xFF53606D).copy(alpha = 0.16f)
                    val major = Color(0xFF1E2A35).copy(alpha = 0.40f)

                    val clipL = max(0f, left)
                    val clipT = max(0f, top)
                    val clipR = min(size.width, right)
                    val clipB = min(stageBottom, bottom)
                    if (clipR > clipL && clipB > clipT) {
                        clipRect(clipL, clipT, clipR, clipB) {
                            if (cellPxW >= 4f) {
                                for (k in 0..grid.cols) {
                                    if (k % 5 == 0) continue
                                    val x = originX + k * cellPxW
                                    drawLine(
                                        color = minor,
                                        start = Offset(x, top),
                                        end = Offset(x, min(bottom, stageBottom)),
                                        strokeWidth = 0.5.dp.toPx(),
                                    )
                                }
                            }
                            if (cellPxH >= 4f) {
                                for (k in 0..grid.rows) {
                                    if (k % 5 == 0) continue
                                    val y = originY + k * cellPxH
                                    drawLine(
                                        color = minor,
                                        start = Offset(left, y),
                                        end = Offset(right, y),
                                        strokeWidth = 0.5.dp.toPx(),
                                    )
                                }
                            }
                            var kx = 0
                            while (kx <= grid.cols) {
                                val x = originX + kx * cellPxW
                                drawLine(
                                    color = major,
                                    start = Offset(x, top),
                                    end = Offset(x, min(bottom, stageBottom)),
                                    strokeWidth = 0.8.dp.toPx(),
                                )
                                kx += 5
                            }
                            var ky = 0
                            while (ky <= grid.rows) {
                                val y = originY + ky * cellPxH
                                drawLine(
                                    color = major,
                                    start = Offset(left, y),
                                    end = Offset(right, y),
                                    strokeWidth = 0.8.dp.toPx(),
                                )
                                ky += 5
                            }
                        }
                    }

                    drawRect(
                        color = Color(0xFF1E2A35).copy(alpha = 0.62f),
                        topLeft = Offset(originX, originY),
                        size = Size(gridW, gridH),
                        style = Stroke(1.0.dp.toPx()),
                    )

                    val cross = Color(0xFFE5484D)
                    clipRect(0f, 0f, size.width, stageBottom) {
                        drawLine(
                            color = cross.copy(alpha = 0.44f),
                            start = Offset(0f, viewCenter.y),
                            end = Offset(size.width, viewCenter.y),
                            strokeWidth = 0.8.dp.toPx(),
                        )
                        drawLine(
                            color = cross.copy(alpha = 0.44f),
                            start = Offset(viewCenter.x, 0f),
                            end = Offset(viewCenter.x, stageBottom),
                            strokeWidth = 0.8.dp.toPx(),
                        )
                        drawCircle(
                            color = cross,
                            radius = 5.dp.toPx(),
                            center = viewCenter,
                            style = Stroke(1.2.dp.toPx()),
                        )
                        drawCircle(
                            color = cross,
                            radius = 1.3.dp.toPx(),
                            center = viewCenter,
                        )
                    }
                }

                if (previewBitmap != null) {
                    PreviewToggle(
                        showingPreview = showPreview,
                        onToggle = { showPreview = !showPreview },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 14.dp),
                    )
                }

                CalibrationSheet(
                    vm = vm,
                    grid = grid,
                    analysis = analysis,
                    onEdit = { editField = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { sheetHeightPx = it.height },
                )
            }
        }
    }

    if (editField != null) {
        val field = editField!!
        val initial = when (field) {
            CalibField.COLS -> grid?.cols?.toString().orEmpty()
            CalibField.ROWS -> grid?.rows?.toString().orEmpty()
            CalibField.CELL -> grid?.let { formatCell(it.cellW) }.orEmpty()
        }
        NumberInputDialog(
            title = when (field) {
                CalibField.COLS -> "网格列数"
                CalibField.ROWS -> "网格行数"
                CalibField.CELL -> "一格大小"
            },
            initial = initial,
            decimal = field == CalibField.CELL,
            onDismiss = { editField = null },
            onConfirm = { raw ->
                when (field) {
                    CalibField.COLS -> raw.toIntOrNull()?.let { vm.pixelCalibrationSetCols(it) }
                    CalibField.ROWS -> raw.toIntOrNull()?.let { vm.pixelCalibrationSetRows(it) }
                    CalibField.CELL -> raw.replace(',', '.').toFloatOrNull()
                        ?.let { vm.pixelCalibrationSetCellSize(it) }
                }
                editField = null
            },
        )
    }
}

@Composable
private fun CalibrationTopBar(vm: AppViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(enabled = !vm.pixelCalibrationBusy) { vm.handleBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MorphIcons.ArrowLeft,
                contentDescription = "返回",
                tint = ClayColors.Ink,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = "像素校准",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { vm.confirmPixelCalibration() },
            enabled = vm.pixelCalibration != null && !vm.pixelCalibrationBusy,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text(
                if (vm.pixelCalibrationBusy) "生成中" else "完成",
                style = MaterialTheme.typography.labelLarge,
                color = if (vm.pixelCalibrationBusy) ClayColors.Muted else ClayColors.PrimaryInk,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 动作说明直接铺在页面上，不套卡片。
 *
 * 校准页只保留一段说明，用户先看红线和两个可调项，再动手。
 * 这里保持同样的阅读顺序：先告诉用户把线贴到格子上，
 * 再说明改哪两个数，最后补手势提示。卡片边框会把这段文字变成
 * 另一个视觉块，反而让页面更像参数表。
 */
@Composable
private fun CalibrationGuide(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            buildAnnotatedString {
                append("放大，让")
                withStyle(SpanStyle(color = Color(0xFFD94841), fontWeight = FontWeight.SemiBold)) {
                    append("红线")
                }
                append("贴住像素格；先调")
                withStyle(SpanStyle(color = ClayColors.PrimaryInk, fontWeight = FontWeight.SemiBold)) {
                    append("列数")
                }
                append("，再微调")
                withStyle(SpanStyle(color = ClayColors.PrimaryInk, fontWeight = FontWeight.SemiBold)) {
                    append("一格大小")
                }
                append("。")
            },
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
            color = ClayColors.Body,
        )
        Text(
            "双指缩放 · 单指拖动 · 每 5 格一条深线",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = ClayColors.Muted,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun EmptyCalibrationState(vm: AppViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "没有可校准的像素图，请重新导入",
            style = MaterialTheme.typography.bodyMedium,
            color = ClayColors.Muted,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { vm.backToHome() }) { Text("返回首页") }
    }
}

@Composable
private fun CalibrationSheet(
    vm: AppViewModel,
    grid: PixelCalibration.Grid,
    analysis: PixelGridInspector.Analysis?,
    onEdit: (CalibField) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.14f),
            )
            .clip(shape)
            .background(ClayColors.SurfaceCard)
            .border(1.dp, ClayColors.Hairline, shape)
            .padding(horizontal = 20.dp)
            // 底部留出系统手势条的高度：之前阈值行贴着屏幕底边，
            // 手势条会把滑块盖住一半。
            .padding(top = 7.dp, bottom = 18.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(34.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(ClayColors.SurfaceStrong),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${grid.cols} × ${grid.rows}",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                fontWeight = FontWeight.SemiBold,
                color = ClayColors.Ink,
            )
            Box(
                Modifier
                    .padding(start = 7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(ClayColors.Primary.copy(alpha = 0.10f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    when {
                        vm.pixelCalibrationBusy || vm.pixelAnalysisRunning -> "识别中"
                        vm.pixelRefineRunning -> "对齐中"
                        vm.pixelCalibrationApplied -> "已吸附"
                        vm.pixelCalibrationMethod == PixelGridInspector.Method.FALLBACK -> "手动网格"
                        vm.pixelCalibrationMethod == PixelGridInspector.Method.MANUAL -> "手动微调"
                        else -> "自动识别"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = ClayColors.PrimaryInk,
                    maxLines = 1,
                )
            }
            Text(
                buildAnalysisSummary(vm, analysis, grid.cols * grid.rows),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = ClayColors.Muted,
                modifier = Modifier.padding(start = 7.dp),
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { vm.pixelCalibrationRedetect() },
                enabled = !vm.pixelCalibrationBusy,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text(
                    "重新识别",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = ClayColors.PrimaryInk,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepBadge(1)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "贴住格子",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                    color = ClayColors.BodyStrong,
                )
                Text(
                    "拖动图片，或点箭头微调",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = ClayColors.Muted,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NudgeButton(MorphIcons.ChevronLeft, "左移") {
                    vm.pixelCalibrationNudge(-1.0, 0.0)
                }
                NudgeButton(MorphIcons.ArrowUp, "上移") {
                    vm.pixelCalibrationNudge(0.0, -1.0)
                }
                NudgeButton(MorphIcons.ArrowDown, "下移") {
                    vm.pixelCalibrationNudge(0.0, 1.0)
                }
                NudgeButton(MorphIcons.ChevronRight, "右移") {
                    vm.pixelCalibrationNudge(1.0, 0.0)
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ClayColors.HairlineSoft),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelColumn(
                step = 2,
                label = "网格列数",
                unit = null,
                current = grid.cols.toString(),
                previous = (grid.cols - 1).coerceAtLeast(PixelCalibration.MIN_COLS).toString(),
                next = (grid.cols + 1).coerceAtMost(PixelCalibration.MAX_COLS).toString(),
                onPrevious = { vm.pixelCalibrationSetCols(grid.cols - 1) },
                onNext = { vm.pixelCalibrationSetCols(grid.cols + 1) },
                onEdit = { onEdit(CalibField.COLS) },
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .width(1.dp)
                    .height(58.dp)
                    .background(ClayColors.HairlineSoft),
            )
            WheelColumn(
                step = 3,
                label = "一格大小",
                unit = "px",
                current = formatCell(grid.cellW),
                previous = formatCell(cellStep(grid.cellW).let {
                    (grid.cellW - it).coerceAtLeast(PixelCalibration.MIN_CELL)
                }),
                next = formatCell(cellStep(grid.cellW).let {
                    (grid.cellW + it).coerceAtMost(PixelCalibration.MAX_CELL)
                }),
                onPrevious = {
                    vm.pixelCalibrationSetCellSize(
                        (grid.cellW - cellStep(grid.cellW))
                            .coerceAtLeast(PixelCalibration.MIN_CELL)
                            .toFloat(),
                    )
                },
                onNext = {
                    vm.pixelCalibrationSetCellSize(
                        (grid.cellW + cellStep(grid.cellW))
                            .coerceAtMost(PixelCalibration.MAX_CELL)
                            .toFloat(),
                    )
                },
                onEdit = { onEdit(CalibField.CELL) },
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onEdit(CalibField.ROWS) }
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "行数",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = ClayColors.Muted,
                )
                Text(
                    grid.rows.toString(),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                    color = ClayColors.BodyStrong,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Icon(
                    MorphIcons.ChevronRight,
                    contentDescription = "调整行数",
                    tint = ClayColors.MutedSoft,
                    modifier = Modifier
                        .padding(start = 1.dp)
                        .size(14.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "智能对齐",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                color = ClayColors.BodyStrong,
            )
            Text(
                when {
                    vm.pixelRefineRunning -> "贴合中"
                    vm.pixelCalibrationApplied -> "已吸附"
                    vm.pixelCalibrationSmart -> "自动微调"
                    else -> "手动"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = ClayColors.Muted,
                modifier = Modifier.padding(start = 6.dp),
            )
            Switch(
                checked = vm.pixelCalibrationSmart,
                onCheckedChange = { vm.pixelCalibrationSetSmart(it) },
                modifier = Modifier
                    .padding(start = 6.dp)
                    .height(28.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = ClayColors.Primary,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = ClayColors.SurfaceStrong,
                    uncheckedBorderColor = ClayColors.Hairline,
                ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "贴合阈值",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = ClayColors.Muted,
            )
            Slider(
                value = vm.pixelCalibrationThreshold,
                onValueChange = { vm.pixelCalibrationSetThreshold(it) },
                valueRange = 0.05f..0.90f,
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .padding(horizontal = 5.dp),
                colors = SliderDefaults.colors(
                    thumbColor = ClayColors.Primary,
                    activeTrackColor = ClayColors.Primary,
                    inactiveTrackColor = ClayColors.SurfaceStrong,
                ),
            )
            Text(
                "${(vm.pixelCalibrationThreshold * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = ClayColors.PrimaryInk,
                textAlign = TextAlign.End,
                modifier = Modifier.width(32.dp),
            )
        }
    }
}

/** 数字步骤标记：把「先对齐、再列数、再格大小」写成看得见的顺序。 */
@Composable
private fun StepBadge(number: Int) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(ClayColors.Primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = ClayColors.PrimaryInk,
        )
    }
}

@Composable
private fun NudgeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(ClayColors.SurfaceSoft)
            .border(1.dp, ClayColors.Hairline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = ClayColors.BodyStrong,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun WheelColumn(
    step: Int,
    label: String,
    unit: String?,
    current: String,
    previous: String,
    next: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onEdit)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            StepBadge(step)
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.Medium,
                color = ClayColors.Muted,
                maxLines = 1,
            )
        }
        Text(
            previous,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = ClayColors.MutedSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onPrevious)
                .padding(vertical = 2.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onEdit),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ClayColors.Primary.copy(alpha = 0.28f)),
                )
                Text(
                    buildAnnotatedString {
                        append(current)
                        if (unit != null) {
                            withStyle(
                                SpanStyle(
                                    fontSize = 10.sp,
                                    color = ClayColors.Muted,
                                    fontWeight = FontWeight.Medium,
                                ),
                            ) {
                                append(" ")
                                append(unit)
                            }
                        }
                    },
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = ClayColors.Ink,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ClayColors.Primary.copy(alpha = 0.28f)),
                )
            }
        }
        Text(
            next,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = ClayColors.MutedSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onNext)
                .padding(vertical = 2.dp),
        )
    }
}

private fun buildAnalysisSummary(
    vm: AppViewModel,
    analysis: PixelGridInspector.Analysis?,
    totalCells: Int,
): String {
    if (vm.pixelAnalysisRunning) return "· 分析中"
    val conversion = vm.pixelCalibrationPreview
    if (conversion != null && conversion.grid == vm.pixelCalibration) {
        // 粒数/色数按最终色号算，和成品图纸、采购清单一致
        val occupied = conversion.result.cells.filter { it >= 0 }
        return "· ${occupied.size} 粒 · ${occupied.distinct().size} 色"
    }
    return analysis?.let { "· $totalCells 粒 · ${it.colorCount} 色" } ?: ""
}

/** 图片区右上角的「看图纸 / 看原图」开关。 */
@Composable
private fun PreviewToggle(
    showingPreview: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ClayColors.SurfaceCard.copy(alpha = 0.94f))
            .border(1.dp, ClayColors.Hairline, RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (showingPreview) MorphIcons.EyeOff else MorphIcons.Eye,
            contentDescription = null,
            tint = ClayColors.PrimaryInk,
            modifier = Modifier.size(15.dp),
        )
        Text(
            if (showingPreview) "看原图" else "看图纸",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = ClayColors.PrimaryInk,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun NumberInputDialog(
    title: String,
    initial: String,
    decimal: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { raw ->
                    value = raw.filter {
                        it.isDigit() || (decimal && (it == '.' || it == ','))
                    }.take(8)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 滚轮步长：20px 一格走 0.25，8px 走 0.1，1px 走 0.01。 */
private fun cellStep(cellW: Double): Double = when {
    cellW >= 20.0 -> 0.25
    cellW >= 8.0 -> 0.10
    cellW >= 2.0 -> 0.02
    else -> 0.01
}

/** 1.0 显示 1，6.85 显示 6.85，不把整数硬写成两位小数。 */
private fun formatCell(value: Double): String {
    if (abs(value - value.roundToInt()) < 0.0001) return value.roundToInt().toString()
    return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}
