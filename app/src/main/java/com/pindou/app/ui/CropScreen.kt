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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.outlined.RestartAlt
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pindou.app.core.image.CropRect
import com.pindou.app.core.image.ImageTransforms
import com.pindou.app.ui.shadcn.Button
import com.pindou.app.ui.shadcn.OutlinedButton
import com.pindou.app.ui.shadcn.ShChip
import com.pindou.app.ui.shadcn.ShPageHeader
import com.pindou.app.ui.shadcn.TextButton
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 一次拖动手势的语义：移动 / 角柄缩放 / 重新框选 */
private sealed interface CropDrag {
    data class Move(val startRect: CropRect, val startX: Float, val startY: Float) : CropDrag
    data class Resize(val anchorX: Float, val anchorY: Float) : CropDrag
    data class Edge(val side: CropEdge) : CropDrag
    data class New(val anchorX: Float, val anchorY: Float) : CropDrag
}

/** 可拖拽的四条边 */
private enum class CropEdge { LEFT, RIGHT, TOP, BOTTOM }

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
        ShPageHeader(
            title = "裁剪与旋转",
            subtitle = "拖动选区、四角调整大小；框选更大更准",
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

        // 变换操作是命令而非筛选，按 shadcn outline 按钮等宽排布，触控高度 40dp 更好点
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OutlinedButton(
                onClick = { vm.rotateImage(false) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) {
                androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Filled.RotateLeft,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("左转", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(
                onClick = { vm.rotateImage(true) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) {
                androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Filled.RotateRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("右转", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(
                onClick = { vm.mirrorImage(true) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Flip,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("镜像", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(
                onClick = { vm.resetImageTransforms() },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) {
                androidx.compose.material3.Icon(
                    Icons.Outlined.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text("重置", modifier = Modifier.padding(start = 4.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ShChip(
                text = "辅助线·${guides.label}",
                selected = guides != CropGuides.NONE,
                onClick = { guides = guides.next() },
            )
            ShChip(
                text = if (aspectLocked) "已锁定 ${vm.gridW}:${vm.gridH}" else "锁定网格比例",
                selected = aspectLocked,
                onClick = { aspectLocked = !aspectLocked },
            )
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
    // 命中半径：角柄 56dp、边柄 28dp。44dp 是 Material 的下限，但手指实际
    // 落点在 50dp 以上才稳；边柄跟着放大，靠内段的移动手势仍然保留。
    val cornerHitR = with(LocalDensity.current) { 56.dp.toPx() }
    val edgeHitR = with(LocalDensity.current) { 28.dp.toPx() }
    var drag by remember { mutableStateOf<CropDrag?>(null) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(imgW, imgH, scale) {
                detectDragGestures(
                    onDragStart = { pos ->
                        drag = hitTest(pos, currentRect, scale, cornerHitR, edgeHitR)
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
                                is CropDrag.Edge -> edgeResize(mode.side, r, ix, iy, ratio, maxW, maxH)
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
        drawCropUi(currentRect, scale, currentGuides)
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
    if (bestCorner >= 0 && bestDist <= cornerHitR) {
        val anchorX = if (bestCorner == 0 || bestCorner == 2) rect.right else rect.left
        val anchorY = if (bestCorner < 2) rect.bottom else rect.top
        return CropDrag.Resize(anchorX, anchorY)
    }
    // 边柄：命中边线附近、且落在该边中段（两端让位给角柄）
    val spanH = cb - ct
    val margin = spanH * 0.22f
    val inTopPart = pos.y > ct + margin && pos.y < cb - margin
    val spanW = cr - cl
    val marginW = spanW * 0.22f
    val inLeftPart = pos.x > cl + marginW && pos.x < cr - marginW
    when {
        abs(pos.x - cl) <= edgeHitR && inTopPart && spanH > 2 * margin -> return CropDrag.Edge(CropEdge.LEFT)
        abs(pos.x - cr) <= edgeHitR && inTopPart && spanH > 2 * margin -> return CropDrag.Edge(CropEdge.RIGHT)
        abs(pos.y - ct) <= edgeHitR && inLeftPart && spanW > 2 * marginW -> return CropDrag.Edge(CropEdge.TOP)
        abs(pos.y - cb) <= edgeHitR && inLeftPart && spanW > 2 * marginW -> return CropDrag.Edge(CropEdge.BOTTOM)
    }
    val ix = pos.x / scale
    val iy = pos.y / scale
    return if (ix >= rect.left && ix <= rect.right && iy >= rect.top && iy <= rect.bottom) {
        CropDrag.Move(rect, ix, iy)
    } else {
        CropDrag.New(ix, iy)
    }
}

private fun DrawScope.drawCropUi(rect: CropRect, scale: Float, guides: CropGuides) {
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

    // 边框：白色打底 + 主色蓝内描边。浅色照片上白框会隐身，深色照片上纯蓝又会糊，
    // 双层描边保证任何底图上选区边界都看得见。
    drawRect(Color.White.copy(alpha = 0.9f), Offset(cl, ct), Size(w, h), style = Stroke(5.dp.toPx()))
    drawRect(ClayColors.Primary, Offset(cl, ct), Size(w, h), style = Stroke(2.dp.toPx()))
    // 角柄：L 形括弧（拖拽语义清晰、视觉面积大）
    val arm = 26.dp.toPx()
    val bracket = listOf(
        // 左上
        Offset(cl, ct) to Offset(cl + arm, ct),
        Offset(cl, ct) to Offset(cl, ct + arm),
        // 右上
        Offset(cr, ct) to Offset(cr - arm, ct),
        Offset(cr, ct) to Offset(cr, ct + arm),
        // 左下
        Offset(cl, cb) to Offset(cl + arm, cb),
        Offset(cl, cb) to Offset(cl, cb - arm),
        // 右下
        Offset(cr, cb) to Offset(cr - arm, cb),
        Offset(cr, cb) to Offset(cr, cb - arm),
    )
    // 先画一层白色粗线作对比垫底，再叠主色，避免角柄在浅色背景里消失
    bracket.forEach { (a, b) -> drawLine(Color.White, a, b, 11.dp.toPx(), cap = StrokeCap.Round) }
    bracket.forEach { (a, b) -> drawLine(ClayColors.Primary, a, b, 6.dp.toPx(), cap = StrokeCap.Round) }
    // 边柄：四边中点短条（视觉比圆点更明显，命中区仍是整条边中段）
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

/** 拖一条边：自由模式只动该边；锁定比例时另一轴联动并保持原中心 */
private fun edgeResize(
    side: CropEdge,
    r: CropRect,
    ix: Float,
    iy: Float,
    ratio: Float?,
    maxW: Float,
    maxH: Float,
): CropRect = when (side) {
    CropEdge.LEFT -> {
        val right = r.right
        if (ratio == null) {
            CropRect(ix.coerceIn(0f, right - 1f), r.top, right, r.bottom)
        } else {
            // 锁比例时从对边中点向手指方向拉（rectFromAnchor 负责等比与边界钳制）
            ImageTransforms.rectFromAnchor(
                right, r.top + r.height / 2f, ix, iy, ratio, maxW, maxH,
            )
        }
    }
    CropEdge.RIGHT -> {
        val left = r.left
        if (ratio == null) {
            CropRect(left, r.top, ix.coerceIn(left + 1f, maxW), r.bottom)
        } else {
            ImageTransforms.rectFromAnchor(
                left, r.top + r.height / 2f, ix, iy, ratio, maxW, maxH,
            )
        }
    }
    CropEdge.TOP -> {
        val bottom = r.bottom
        if (ratio == null) {
            CropRect(r.left, iy.coerceIn(0f, bottom - 1f), r.right, bottom)
        } else {
            ImageTransforms.rectFromAnchor(
                r.left + r.width / 2f, bottom, ix, iy, ratio, maxW, maxH,
            )
        }
    }
    CropEdge.BOTTOM -> {
        val top = r.top
        if (ratio == null) {
            CropRect(r.left, top, r.right, iy.coerceIn(top + 1f, maxH))
        } else {
            ImageTransforms.rectFromAnchor(
                r.left + r.width / 2f, top, ix, iy, ratio, maxW, maxH,
            )
        }
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
