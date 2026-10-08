package com.pindou.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pindou.app.core.image.CropRect
import com.pindou.app.core.image.ImageTransforms
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 一次拖动手势的语义：移动 / 角柄缩放 / 重新框选 */
private sealed interface CropDrag {
    data class Move(val startRect: CropRect, val startX: Float, val startY: Float) : CropDrag
    data class Resize(val anchorX: Float, val anchorY: Float) : CropDrag
    data class New(val anchorX: Float, val anchorY: Float) : CropDrag
}

/** 选区辅助线样式（chip 点击循环切换） */
private enum class CropGuides(val label: String) {
    THIRDS("三分线"),
    GOLDEN("黄金比例"),
    CENTER("中心十字"),
    DIAGONAL("对角线"),
    NONE("关闭"),
    ;

    fun next(): CropGuides = entries[(ordinal + 1) % entries.size]
}

/**
 * 裁剪与旋转页：拖动选区、角柄缩放、旋转/镜像、按网格比例锁定。
 * 确认后才真正裁剪像素；旋转与镜像即时生效（作用于工作图）。
 */
@Composable
fun CropScreen(vm: AppViewModel) {
    val bitmap = vm.imagePreview
    val rect = vm.cropRect
    val imgW = vm.imageW
    val imgH = vm.imageH
    var aspectLocked by remember { mutableStateOf(false) }
    var guides by remember { mutableStateOf(CropGuides.THIRDS) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            "裁剪与旋转",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(top = Spacing.lg),
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

        // 画布区：图像按比例缩放居中，选区叠加层与图像完全重合
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val density = LocalDensity.current
            val maxWpx = with(density) { maxWidth.toPx() }
            val maxHpx = with(density) { maxHeight.toPx() }
            val scale = remember(imgW, imgH, maxWpx, maxHpx) {
                minOf(maxWpx / imgW, maxHpx / imgH)
            }
            val dispW = with(density) { (imgW * scale).toDp() }
            val dispH = with(density) { (imgH * scale).toDp() }
            Box(Modifier.align(Alignment.Center).size(dispW, dispH)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "待裁剪图片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                CropOverlay(
                    rect = rect,
                    scale = scale,
                    imgW = imgW,
                    imgH = imgH,
                    aspectLocked = aspectLocked,
                    guides = guides,
                    gridW = vm.gridW,
                    gridH = vm.gridH,
                    onRectChange = { vm.updateCropRect(it) },
                )
            }
        }

        Text(
            buildString {
                append("选区 ${rect.width.roundToInt()}×${rect.height.roundToInt()} px")
                if (aspectLocked) append(" · 锁定 ${vm.gridW}:${vm.gridH}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip(
                selected = false,
                onClick = { vm.rotateImage(false) },
                label = { Text("左转") },
                leadingIcon = { androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = null) },
            )
            FilterChip(
                selected = false,
                onClick = { vm.rotateImage(true) },
                label = { Text("右转") },
                leadingIcon = { androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null) },
            )
            FilterChip(
                selected = false,
                onClick = { vm.mirrorImage(true) },
                label = { Text("镜像") },
                leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.Flip, contentDescription = null) },
            )
            FilterChip(
                selected = false,
                onClick = { vm.resetImageTransforms() },
                label = { Text("重置") },
                leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip(
                selected = guides != CropGuides.NONE,
                onClick = { guides = guides.next() },
                label = { Text("辅助线·${guides.label}") },
            )
            FilterChip(
                selected = aspectLocked,
                onClick = { aspectLocked = !aspectLocked },
                label = {
                    Text(if (aspectLocked) "已锁定 ${vm.gridW}:${vm.gridH}" else "锁定网格比例")
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.backToHome() }) {
                Text("返回")
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { vm.confirmCrop() },
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("下一步")
            }
        }
    }
}

/** 选区叠加层：遮罩、辅助线、边框、角柄与手势 */
@Composable
private fun CropOverlay(
    rect: CropRect,
    scale: Float,
    imgW: Int,
    imgH: Int,
    aspectLocked: Boolean,
    guides: CropGuides,
    gridW: Int,
    gridH: Int,
    onRectChange: (CropRect) -> Unit,
) {
    val currentRect by rememberUpdatedState(rect)
    val currentLocked by rememberUpdatedState(aspectLocked)
    val currentGuides by rememberUpdatedState(guides)
    val currentGridW by rememberUpdatedState(gridW)
    val currentGridH by rememberUpdatedState(gridH)
    val handleR = with(LocalDensity.current) { 22.dp.toPx() }
    var drag by remember { mutableStateOf<CropDrag?>(null) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(imgW, imgH, scale) {
                detectDragGestures(
                    onDragStart = { pos ->
                        drag = hitTest(pos, currentRect, scale, handleR)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val mode = drag ?: return@detectDragGestures
                        val r = currentRect
                        val ix = change.position.x / scale
                        val iy = change.position.y / scale
                        val maxW = imgW.toFloat()
                        val maxH = imgH.toFloat()
                        val ratio = if (currentLocked && currentGridH > 0) {
                            currentGridW.toFloat() / currentGridH
                        } else {
                            null
                        }
                        onRectChange(
                            when (mode) {
                                is CropDrag.Move -> ImageTransforms.movedBy(
                                    mode.startRect, ix - mode.startX, iy - mode.startY, maxW, maxH,
                                )
                                is CropDrag.Resize -> ImageTransforms.rectFromAnchor(
                                    mode.anchorX, mode.anchorY, ix, iy, ratio, maxW, maxH,
                                )
                                is CropDrag.New -> ImageTransforms.rectFromAnchor(
                                    mode.anchorX, mode.anchorY, ix, iy, ratio, maxW, maxH,
                                )
                            },
                        )
                    },
                    onDragEnd = { drag = null },
                    onDragCancel = { drag = null },
                )
            },
    ) {
        drawCropUi(currentRect, scale, handleR, currentGuides)
    }
}

/** 命中测试：角柄优先，其次框内移动，框外重新框选 */
private fun hitTest(pos: Offset, rect: CropRect, scale: Float, handleR: Float): CropDrag {
    val cl = rect.left * scale
    val ct = rect.top * scale
    val cr = rect.right * scale
    val cb = rect.bottom * scale
    var bestCorner: Int = -1
    var bestDist = Float.MAX_VALUE
    val corners = arrayOf(Offset(cl, ct), Offset(cr, ct), Offset(cl, cb), Offset(cr, cb))
    corners.forEachIndexed { i, c ->
        val d = hypot(pos.x - c.x, pos.y - c.y)
        if (d < bestDist) {
            bestDist = d
            bestCorner = i
        }
    }
    if (bestCorner >= 0 && bestDist <= handleR) {
        val anchorX = if (bestCorner == 0 || bestCorner == 2) rect.right else rect.left
        val anchorY = if (bestCorner < 2) rect.bottom else rect.top
        return CropDrag.Resize(anchorX, anchorY)
    }
    val ix = pos.x / scale
    val iy = pos.y / scale
    return if (ix >= rect.left && ix <= rect.right && iy >= rect.top && iy <= rect.bottom) {
        CropDrag.Move(rect, ix, iy)
    } else {
        CropDrag.New(ix, iy)
    }
}

private fun DrawScope.drawCropUi(rect: CropRect, scale: Float, handleR: Float, guides: CropGuides) {
    val cl = rect.left * scale
    val ct = rect.top * scale
    val cr = rect.right * scale
    val cb = rect.bottom * scale
    val w = cr - cl
    val h = cb - ct

    // 四块遮罩
    val scrim = Color.Black.copy(alpha = 0.45f)
    drawRect(scrim, Offset(0f, 0f), Size(size.width, ct.coerceIn(0f, size.height)))
    drawRect(scrim, Offset(0f, cb), Size(size.width, (size.height - cb).coerceAtLeast(0f)))
    drawRect(scrim, Offset(0f, ct), Size(cl.coerceAtLeast(0f), h.coerceAtLeast(0f)))
    drawRect(scrim, Offset(cr, ct), Size((size.width - cr).coerceAtLeast(0f), h.coerceAtLeast(0f)))

    // 选区内辅助线
    if (w > 0f && h > 0f) {
        drawGuides(guides, cl, ct, w, h)
    }

    // 边框与角柄
    drawRect(Color.White, Offset(cl, ct), Size(w, h), style = Stroke(2.dp.toPx()))
    val dotR = handleR * 0.45f
    arrayOf(Offset(cl, ct), Offset(cr, ct), Offset(cl, cb), Offset(cr, cb)).forEach { c ->
        drawCircle(Color.White, dotR, c)
        drawCircle(ClayColors.BrandPink, dotR * 0.45f, c)
    }
}

private fun DrawScope.drawGuides(guides: CropGuides, cl: Float, ct: Float, w: Float, h: Float) {
    if (guides == CropGuides.NONE) return
    val line = Color.White.copy(alpha = 0.35f)
    val lw = 1.dp.toPx()
    when (guides) {
        CropGuides.THIRDS -> {
            for (i in 1..2) {
                drawLine(line, Offset(cl + w * i / 3f, ct), Offset(cl + w * i / 3f, ct + h), lw)
                drawLine(line, Offset(cl, ct + h * i / 3f), Offset(cl + w, ct + h * i / 3f), lw)
            }
        }
        CropGuides.GOLDEN -> {
            for (t in floatArrayOf(0.382f, 0.618f)) {
                drawLine(line, Offset(cl + w * t, ct), Offset(cl + w * t, ct + h), lw)
                drawLine(line, Offset(cl, ct + h * t), Offset(cl + w, ct + h * t), lw)
            }
        }
        CropGuides.CENTER -> {
            drawLine(line, Offset(cl + w / 2f, ct), Offset(cl + w / 2f, ct + h), lw)
            drawLine(line, Offset(cl, ct + h / 2f), Offset(cl + w, ct + h / 2f), lw)
            drawCircle(line, 3.dp.toPx(), Offset(cl + w / 2f, ct + h / 2f))
        }
        CropGuides.DIAGONAL -> {
            drawLine(line, Offset(cl, ct), Offset(cl + w, ct + h), lw)
            drawLine(line, Offset(cl + w, ct), Offset(cl, ct + h), lw)
        }
        CropGuides.NONE -> {}
    }
}
