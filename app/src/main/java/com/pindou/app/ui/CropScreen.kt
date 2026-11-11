package com.pindou.app.ui

import com.pindou.app.ui.icon.MorphIcons
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pindou.app.core.image.CropEdgeSide
import com.pindou.app.core.image.CropRect
import com.pindou.app.core.image.ImageTransforms
import com.pindou.app.ui.shadcn.Button
import com.pindou.app.ui.shadcn.OutlinedButton
import com.pindou.app.ui.shadcn.ShCard
import com.pindou.app.ui.shadcn.ShIconTile
import com.pindou.app.ui.shadcn.ShPageHeader
import com.pindou.app.ui.shadcn.TextButton
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** 一次拖动手势的语义：移动 / 角柄缩放 / 边柄缩放 / 重新框选 */
private sealed interface CropDrag {
    data class Move(val startRect: CropRect, val startX: Float, val startY: Float) : CropDrag
    data class Resize(val anchorX: Float, val anchorY: Float) : CropDrag
    data class Edge(val side: CropEdgeSide) : CropDrag
    data class New(val anchorX: Float, val anchorY: Float) : CropDrag
}

/** 选区辅助线，点击循环切换 */
private enum class CropGuides(val label: String) {
    THIRDS("三分线"),
    GOLDEN("黄金线"),
    CENTER("中心线"),
    DIAGONAL("对角线"),
    NONE("无辅助线"),
    ;

    fun next(): CropGuides = entries[(ordinal + 1) % entries.size]
}

/**
 * 裁剪与旋转页。
 *
 * 自由模式不再锁选区形状，网格比例实时跟随选区；正方形模式同时锁
 * 选区和网格为 1:1。所有控制按钮统一 40dp 高、8dp 圆角。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CropScreen(vm: AppViewModel) {
    // 像素画走独立裁剪页：只保留「框住画布」这一件事。
    // 照片页的三分线、网格着色、旋转镜像在这里全是噪音，用户真正要调
    // 的网格在下一步「像素校准」里。
    if (vm.pixelArtMode) {
        PixelArtCropScreen(vm)
        return
    }
    val bitmap = vm.imagePreview
    val rect = vm.cropRect
    val imgW = vm.imageW
    val imgH = vm.imageH
    val preview = vm.cropPreview
    val previewImage = remember(preview) { preview?.image?.asImageBitmap() }
    var guides by remember { mutableStateOf(CropGuides.THIRDS) }

    val aspectRatio = if (vm.cropAspectMode == CropAspectMode.SQUARE) 1f else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ShPageHeader(
            title = "裁剪",
            subtitle = "拖角柄定范围，框内实时看每格落什么色",
            modifier = Modifier.padding(top = Spacing.sm),
        )

        if (bitmap == null || rect == null || imgW <= 0 || imgH <= 0) {
            Text(
                "没有可编辑的图片，请先导入一张照片",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { vm.backToHome() },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("返回首页")
            }
            return
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val density = LocalDensity.current
            val maxWpx = with(density) { maxWidth.toPx() }
            val maxHpx = with(density) { maxHeight.toPx() }
            val scale = remember(imgW, imgH, maxWpx, maxHpx) {
                minOf(maxWpx / imgW, maxHpx / imgH)
            }
            val dispW = with(density) { (imgW * scale).toDp() }
            val dispH = with(density) { (imgH * scale).toDp() }
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Box(Modifier.align(Alignment.Center).size(dispW, dispH)) {
                Image(
                    bitmap = image,
                    contentDescription = "待裁剪图片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                CropOverlay(
                    rect = rect,
                    scale = scale,
                    imgW = imgW,
                    imgH = imgH,
                    ratio = aspectRatio,
                    guides = guides,
                    preview = preview,
                    previewImage = previewImage,
                    gridW = vm.gridW,
                    gridH = vm.gridH,
                    onRectChange = vm::updateCropRect,
                )
            }
        }

        Text(
            buildString {
                append("选区 ${rect.width.roundToInt()}×${rect.height.roundToInt()} px")
                append(" · 网格 ${vm.gridW}×${vm.gridH}")
                preview?.takeIf { it.gridW == vm.gridW && it.gridH == vm.gridH }
                    ?.let { append(" · ${it.colorCount} 色") }
                if (vm.cropPreviewRunning) append(" · 计算中…")
                append(if (aspectRatio == null) " · 自由比例" else " · 锁定 1:1")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 旋转、镜像、重置：与下面比例行使用同一套 40dp / 8dp 控件
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OutlinedButton(
                onClick = { vm.rotateImage(false) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Icon(
                    MorphIcons.RotateCcw,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("左转", modifier = Modifier.padding(start = 3.dp))
            }
            OutlinedButton(
                onClick = { vm.rotateImage(true) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Icon(
                    MorphIcons.RotateCw,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("右转", modifier = Modifier.padding(start = 3.dp))
            }
            OutlinedButton(
                onClick = { vm.mirrorImage(true) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Icon(MorphIcons.FlipHorizontal, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("镜像", modifier = Modifier.padding(start = 3.dp))
            }
            OutlinedButton(
                onClick = { vm.resetImageTransforms() },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Icon(MorphIcons.RefreshCcw, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("重置", modifier = Modifier.padding(start = 3.dp))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            CropSegment(
                text = guides.label,
                selected = guides != CropGuides.NONE,
                modifier = Modifier.weight(1f),
                onClick = { guides = guides.next() },
            )
            CropSegment(
                text = "自由",
                selected = vm.cropAspectMode == CropAspectMode.FREE,
                modifier = Modifier.weight(1f),
                onClick = { vm.changeCropAspectMode(CropAspectMode.FREE) },
            )
            CropSegment(
                text = "正方形",
                selected = vm.cropAspectMode == CropAspectMode.SQUARE,
                modifier = Modifier.weight(1f),
                onClick = { vm.changeCropAspectMode(CropAspectMode.SQUARE) },
            )
            CropSegment(
                text = "网格预览",
                selected = vm.cropPreviewEnabled,
                modifier = Modifier.weight(1f),
                icon = MorphIcons.Grid,
                onClick = { vm.toggleCropPreview() },
            )
        }

        ShCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text("网格", style = MaterialTheme.typography.labelLarge)
                    GridStepper(value = vm.gridW) { vm.setCropGridWidth(it) }
                    Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GridStepper(value = vm.gridH) { vm.setCropGridHeight(it) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${vm.gridW * vm.gridH} 粒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    vm.pixelArtGrid?.let { (aw, ah) ->
                        GridPreset(
                            text = "原画 $aw×$ah",
                            selected = vm.gridW == aw && vm.gridH == ah,
                        ) { vm.applyArtGridAtCrop() }
                    }
                    listOf(24, 36, 48, 64, 100).forEach { w ->
                        GridPreset(
                            text = "$w",
                            selected = vm.gridW == w,
                        ) { vm.setCropGridWidth(w) }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { vm.backToHome() },
                modifier = Modifier.height(48.dp),
            ) {
                Text("返回")
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { vm.confirmCrop() },
                modifier = Modifier.height(48.dp),
            ) {
                Text("下一步")
            }
        }
    }
}

/**
 * 像素画专用裁剪页。
 *
 * 整页只有图、四角框和一个确认入口。
 * 用户在下一步的「像素校准」里才看到格子，不会在裁剪阶段同时面对
 * 选区比例、网格点数、格线预览三套坐标系。
 */
@Composable
private fun PixelArtCropScreen(vm: AppViewModel) {
    val bitmap = vm.imagePreview
    val rect = vm.cropRect
    val imgW = vm.imageW
    val imgH = vm.imageH
    val isSquare = vm.cropAspectMode == CropAspectMode.SQUARE
    val ratio = if (isSquare) 1f else null
    val fullSelected = vm.cropOriginalPreset && rect != null && !isSquare &&
        abs(rect.left) < 0.5f && abs(rect.top) < 0.5f &&
        abs(rect.right - imgW) < 0.5f && abs(rect.bottom - imgH) < 0.5f

    Column(
        Modifier
            .fillMaxSize()
            .background(ClayColors.Canvas),
    ) {
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
                    .clickable { vm.handleBack() },
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
                "选择范围",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            // 右侧留出与返回键等宽的占位，标题保持视觉居中；
            // 确认入口只保留底部整行按钮，避免同一屏两个「下一步」。
            Spacer(Modifier.size(44.dp))
        }

        Text(
            "拖动四角框住画面主体，下一步再校准像素格子",
            style = MaterialTheme.typography.bodySmall,
            color = ClayColors.Muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        if (bitmap == null || rect == null || imgW <= 0 || imgH <= 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "没有可裁剪的图片，请重新导入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.backToHome() }) { Text("返回首页") }
            }
            return@Column
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val density = LocalDensity.current
            val maxWpx = with(density) { maxWidth.toPx() }
            val maxHpx = with(density) { maxHeight.toPx() }
            val scale = remember(imgW, imgH, maxWpx, maxHpx) {
                minOf(maxWpx / imgW, maxHpx / imgH)
            }
            val dispW = with(density) { (imgW * scale).toDp() }
            val dispH = with(density) { (imgH * scale).toDp() }
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Box(Modifier.align(Alignment.Center).size(dispW, dispH)) {
                Image(
                    bitmap = image,
                    contentDescription = "待裁剪像素画",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                CropOverlay(
                    rect = rect,
                    scale = scale,
                    imgW = imgW,
                    imgH = imgH,
                    ratio = ratio,
                    guides = CropGuides.NONE,
                    preview = null,
                    previewImage = null,
                    gridW = vm.gridW,
                    gridH = vm.gridH,
                    showGrid = false,
                    showGuides = false,
                    onRectChange = vm::updateCropRect,
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(ClayColors.SurfaceCard)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                CropSegment(
                    text = "原图",
                    selected = fullSelected,
                    modifier = Modifier.weight(1f),
                ) {
                    vm.selectCropOriginal()
                }
                CropSegment(
                    text = "自由",
                    selected = !isSquare && !fullSelected,
                    modifier = Modifier.weight(1f),
                ) {
                    vm.changeCropAspectMode(CropAspectMode.FREE)
                }
                CropSegment(
                    text = "正方形",
                    selected = isSquare,
                    modifier = Modifier.weight(1f),
                ) {
                    vm.changeCropAspectMode(CropAspectMode.SQUARE)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "选区 ${rect.width.roundToInt()} × ${rect.height.roundToInt()} px",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "下一步：像素校准",
                    style = MaterialTheme.typography.labelMedium,
                    color = ClayColors.PrimaryInk,
                )
            }

            Button(
                onClick = { vm.confirmCrop() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Icon(MorphIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("下一步", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** 比例 / 辅助线按钮：和旋转按钮同高同圆角，选中态用品牌淡蓝底 */
@Composable
private fun CropSegment(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .border(
                1.dp,
                if (selected) ClayColors.Primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) ClayColors.PrimaryInk else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.size(3.dp))
            }
            Text(
                text,
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) ClayColors.PrimaryInk else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GridPreset(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .border(
                1.dp,
                if (selected) ClayColors.Primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) ClayColors.PrimaryInk else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 网格宽高步进器：−/值/+ 一组，点一次改一格 */
@Composable
private fun GridStepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange((value - 1).coerceIn(5, 200)) },
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(MorphIcons.Minus, contentDescription = "减小网格", modifier = Modifier.size(14.dp))
        }
        Text(
            "$value",
            modifier = Modifier.widthIn(min = 34.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedButton(
            onClick = { onChange((value + 1).coerceIn(5, 200)) },
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(MorphIcons.Plus, contentDescription = "增大网格", modifier = Modifier.size(14.dp))
        }
    }
}

/** 选区叠加层：遮罩、网格着色、辅助线、边框、角柄与手势 */
@Composable
private fun CropOverlay(
    rect: CropRect,
    scale: Float,
    imgW: Int,
    imgH: Int,
    ratio: Float?,
    guides: CropGuides,
    preview: CropGridPreview?,
    previewImage: androidx.compose.ui.graphics.ImageBitmap?,
    gridW: Int,
    gridH: Int,
    showGrid: Boolean = true,
    showGuides: Boolean = true,
    onRectChange: (CropRect) -> Unit,
) {
    val currentRect by rememberUpdatedState(rect)
    val currentRatio by rememberUpdatedState(ratio)
    val currentGuides by rememberUpdatedState(guides)
    val cornerHitR = with(LocalDensity.current) { 56.dp.toPx() }
    val edgeHitR = with(LocalDensity.current) { 28.dp.toPx() }
    var drag by remember { mutableStateOf<CropDrag?>(null) }
    val haptic = LocalHapticFeedback.current

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(imgW, imgH, scale) {
                detectDragGestures(
                    onDragStart = { pos ->
                        drag = hitTest(pos, currentRect, scale, cornerHitR, edgeHitR)
                        if (drag !is CropDrag.New) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val mode = drag ?: return@detectDragGestures
                        val r = currentRect
                        val ix = change.position.x / scale
                        val iy = change.position.y / scale
                        val maxW = imgW.toFloat()
                        val maxH = imgH.toFloat()
                        val lockedRatio = currentRatio
                        onRectChange(
                            when (mode) {
                                is CropDrag.Move -> ImageTransforms.movedBy(
                                    mode.startRect,
                                    ix - mode.startX,
                                    iy - mode.startY,
                                    maxW,
                                    maxH,
                                )
                                is CropDrag.Resize -> ImageTransforms.rectFromAnchor(
                                    mode.anchorX,
                                    mode.anchorY,
                                    ix,
                                    iy,
                                    lockedRatio,
                                    maxW,
                                    maxH,
                                )
                                is CropDrag.Edge -> resizeEdge(
                                    side = mode.side,
                                    rect = r,
                                    x = ix,
                                    y = iy,
                                    ratio = lockedRatio,
                                    maxW = maxW,
                                    maxH = maxH,
                                )
                                is CropDrag.New -> ImageTransforms.rectFromAnchor(
                                    mode.anchorX,
                                    mode.anchorY,
                                    ix,
                                    iy,
                                    lockedRatio,
                                    maxW,
                                    maxH,
                                )
                            },
                        )
                    },
                    onDragEnd = { drag = null },
                    onDragCancel = { drag = null },
                )
            },
    ) {
        drawCropUi(
            rect = currentRect,
            scale = scale,
            guides = currentGuides,
            preview = preview,
            previewImage = previewImage,
            gridW = gridW,
            gridH = gridH,
            showGrid = showGrid,
            showGuides = showGuides,
        )
    }
}

/** 命中测试：角柄优先 → 边柄中段 → 框内移动 → 框外重新框选 */
private fun hitTest(
    pos: Offset,
    rect: CropRect,
    scale: Float,
    cornerHitR: Float,
    edgeHitR: Float,
): CropDrag {
    val cl = rect.left * scale
    val ct = rect.top * scale
    val cr = rect.right * scale
    val cb = rect.bottom * scale
    var bestCorner = -1
    var bestDist = Float.MAX_VALUE
    val corners = arrayOf(Offset(cl, ct), Offset(cr, ct), Offset(cl, cb), Offset(cr, cb))
    corners.forEachIndexed { i, c ->
        val d = hypot(pos.x - c.x, pos.y - c.y)
        if (d < bestDist) {
            bestDist = d
            bestCorner = i
        }
    }
    if (bestCorner >= 0 && bestDist <= cornerHitR) {
        val anchorX = if (bestCorner == 0 || bestCorner == 2) rect.right else rect.left
        val anchorY = if (bestCorner < 2) rect.bottom else rect.top
        return CropDrag.Resize(anchorX, anchorY)
    }

    val spanH = cb - ct
    val margin = spanH * 0.22f
    val inTopPart = pos.y > ct + margin && pos.y < cb - margin
    val spanW = cr - cl
    val marginW = spanW * 0.22f
    val inLeftPart = pos.x > cl + marginW && pos.x < cr - marginW
    when {
        abs(pos.x - cl) <= edgeHitR && inTopPart && spanH > 2 * margin ->
            return CropDrag.Edge(CropEdgeSide.LEFT)
        abs(pos.x - cr) <= edgeHitR && inTopPart && spanH > 2 * margin ->
            return CropDrag.Edge(CropEdgeSide.RIGHT)
        abs(pos.y - ct) <= edgeHitR && inLeftPart && spanW > 2 * marginW ->
            return CropDrag.Edge(CropEdgeSide.TOP)
        abs(pos.y - cb) <= edgeHitR && inLeftPart && spanW > 2 * marginW ->
            return CropDrag.Edge(CropEdgeSide.BOTTOM)
    }

    val ix = pos.x / scale
    val iy = pos.y / scale
    return if (ix >= rect.left && ix <= rect.right && iy >= rect.top && iy <= rect.bottom) {
        CropDrag.Move(rect, ix, iy)
    } else {
        CropDrag.New(ix, iy)
    }
}

private fun DrawScope.drawCropUi(
    rect: CropRect,
    scale: Float,
    guides: CropGuides,
    preview: CropGridPreview?,
    previewImage: androidx.compose.ui.graphics.ImageBitmap?,
    gridW: Int,
    gridH: Int,
    showGrid: Boolean = true,
    showGuides: Boolean = true,
) {
    val cl = rect.left * scale
    val ct = rect.top * scale
    val cr = rect.right * scale
    val cb = rect.bottom * scale
    val w = cr - cl
    val h = cb - ct

    val scrim = Color.Black.copy(alpha = 0.45f)
    drawRect(scrim, Offset(0f, 0f), Size(size.width, ct.coerceIn(0f, size.height)))
    drawRect(scrim, Offset(0f, cb), Size(size.width, (size.height - cb).coerceAtLeast(0f)))
    drawRect(scrim, Offset(0f, ct), Size(cl.coerceAtLeast(0f), h.coerceAtLeast(0f)))
    drawRect(scrim, Offset(cr, ct), Size((size.width - cr).coerceAtLeast(0f), h.coerceAtLeast(0f)))

    val matches = preview != null && previewImage != null && rectsMatch(preview.rect, rect)
    if (w > 1f && h > 1f) {
        if (matches) {
            drawImage(
                image = previewImage!!,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(previewImage.width, previewImage.height),
                dstOffset = IntOffset(cl.roundToInt(), ct.roundToInt()),
                dstSize = IntSize(
                    w.roundToInt().coerceAtLeast(1),
                    h.roundToInt().coerceAtLeast(1),
                ),
                filterQuality = FilterQuality.None,
            )
        }
        val gw = if (matches) preview!!.gridW else gridW
        val gh = if (matches) preview!!.gridH else gridH
        val cellW = w / gw.coerceAtLeast(1)
        val cellH = h / gh.coerceAtLeast(1)
        if (showGrid && min(cellW, cellH) >= 6f) {
            val line = Color.White.copy(alpha = if (matches) 0.20f else 0.35f)
            val lw = 1.dp.toPx()
            for (i in 1 until gw) {
                val x = cl + cellW * i
                drawLine(line, Offset(x, ct), Offset(x, ct + h), lw)
            }
            for (j in 1 until gh) {
                val y = ct + cellH * j
                drawLine(line, Offset(cl, y), Offset(cl + w, y), lw)
            }
        }
    }

    if (showGuides && w > 0f && h > 0f) {
        drawGuides(guides, cl, ct, w, h)
    }

    drawRect(Color.White.copy(alpha = 0.9f), Offset(cl, ct), Size(w, h), style = Stroke(5.dp.toPx()))
    drawRect(ClayColors.Primary, Offset(cl, ct), Size(w, h), style = Stroke(2.dp.toPx()))

    val arm = 26.dp.toPx()
    val bracket = listOf(
        Offset(cl, ct) to Offset(cl + arm, ct),
        Offset(cl, ct) to Offset(cl, ct + arm),
        Offset(cr, ct) to Offset(cr - arm, ct),
        Offset(cr, ct) to Offset(cr, ct + arm),
        Offset(cl, cb) to Offset(cl + arm, cb),
        Offset(cl, cb) to Offset(cl, cb - arm),
        Offset(cr, cb) to Offset(cr - arm, cb),
        Offset(cr, cb) to Offset(cr, cb - arm),
    )
    bracket.forEach { (a, b) -> drawLine(Color.White, a, b, 11.dp.toPx(), cap = StrokeCap.Round) }
    bracket.forEach { (a, b) -> drawLine(ClayColors.Primary, a, b, 6.dp.toPx(), cap = StrokeCap.Round) }

    val barLen = 34.dp.toPx()
    val bars = listOf(
        Offset(cl, (ct + cb) / 2f - barLen / 2f) to Offset(cl, (ct + cb) / 2f + barLen / 2f),
        Offset(cr, (ct + cb) / 2f - barLen / 2f) to Offset(cr, (ct + cb) / 2f + barLen / 2f),
        Offset((cl + cr) / 2f - barLen / 2f, ct) to Offset((cl + cr) / 2f + barLen / 2f, ct),
        Offset((cl + cr) / 2f - barLen / 2f, cb) to Offset((cl + cr) / 2f + barLen / 2f, cb),
    )
    bars.forEach { (a, b) -> drawLine(Color.White, a, b, 13.dp.toPx(), cap = StrokeCap.Round) }
    bars.forEach { (a, b) -> drawLine(ClayColors.Primary, a, b, 7.dp.toPx(), cap = StrokeCap.Round) }
}

private fun rectsMatch(a: CropRect, b: CropRect): Boolean =
    abs(a.left - b.left) < 0.5f && abs(a.top - b.top) < 0.5f &&
        abs(a.right - b.right) < 0.5f && abs(a.bottom - b.bottom) < 0.5f

/**
 * 拖边：自由模式只移动当前边；锁定比例时只读取与边垂直的坐标，
 * 另一轴围绕原中心展开。旧实现把手指横纵坐标都喂给角柄算法，
 * 拖动时会因另一轴抖动反复回弹。
 */
private fun resizeEdge(
    side: CropEdgeSide,
    rect: CropRect,
    x: Float,
    y: Float,
    ratio: Float?,
    maxW: Float,
    maxH: Float,
): CropRect {
    if (ratio != null) {
        val target = when (side) {
            CropEdgeSide.LEFT, CropEdgeSide.RIGHT -> x
            CropEdgeSide.TOP, CropEdgeSide.BOTTOM -> y
        }
        return ImageTransforms.edgeResizeLocked(rect, side, target, ratio, maxW, maxH)
    }
    return when (side) {
        CropEdgeSide.LEFT ->
            CropRect(x.coerceIn(0f, rect.right - 1f), rect.top, rect.right, rect.bottom)
        CropEdgeSide.RIGHT ->
            CropRect(rect.left, rect.top, x.coerceIn(rect.left + 1f, maxW), rect.bottom)
        CropEdgeSide.TOP ->
            CropRect(rect.left, y.coerceIn(0f, rect.bottom - 1f), rect.right, rect.bottom)
        CropEdgeSide.BOTTOM ->
            CropRect(rect.left, rect.top, rect.right, y.coerceIn(rect.top + 1f, maxH))
    }
}

private fun DrawScope.drawGuides(guides: CropGuides, cl: Float, ct: Float, w: Float, h: Float) {
    if (guides == CropGuides.NONE) return
    // 构图辅助线用黑色主线，网格线保持浅白细线，两者语义和视觉都分开
    val line = Color.Black.copy(alpha = 0.68f)
    val halo = Color.White.copy(alpha = 0.32f)
    val lw = 1.8.dp.toPx()
    val haloW = lw + 1.4.dp.toPx()
    fun seg(a: Offset, b: Offset) {
        drawLine(halo, a, b, haloW, cap = StrokeCap.Round)
        drawLine(line, a, b, lw, cap = StrokeCap.Round)
    }
    when (guides) {
        CropGuides.THIRDS -> {
            for (i in 1..2) {
                seg(Offset(cl + w * i / 3f, ct), Offset(cl + w * i / 3f, ct + h))
                seg(Offset(cl, ct + h * i / 3f), Offset(cl + w, ct + h * i / 3f))
            }
        }
        CropGuides.GOLDEN -> {
            for (t in floatArrayOf(0.382f, 0.618f)) {
                seg(Offset(cl + w * t, ct), Offset(cl + w * t, ct + h))
                seg(Offset(cl, ct + h * t), Offset(cl + w, ct + h * t))
            }
        }
        CropGuides.CENTER -> {
            seg(Offset(cl + w / 2f, ct), Offset(cl + w / 2f, ct + h))
            seg(Offset(cl, ct + h / 2f), Offset(cl + w, ct + h / 2f))
            drawCircle(halo, 4.dp.toPx(), Offset(cl + w / 2f, ct + h / 2f))
            drawCircle(line, 3.dp.toPx(), Offset(cl + w / 2f, ct + h / 2f))
        }
        CropGuides.DIAGONAL -> {
            seg(Offset(cl, ct), Offset(cl + w, ct + h))
            seg(Offset(cl + w, ct), Offset(cl, ct + h))
        }
        CropGuides.NONE -> {}
    }
}
