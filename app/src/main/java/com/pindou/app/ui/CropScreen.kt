package com.pindou.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pindou.app.core.image.CropRect
import com.pindou.app.core.image.ImageTransforms
import com.pindou.app.ui.shadcn.ShCard
import com.pindou.app.ui.shadcn.Button
import com.pindou.app.ui.shadcn.OutlinedButton
import com.pindou.app.ui.shadcn.ShChip
import com.pindou.app.ui.shadcn.ShPageHeader
import com.pindou.app.ui.shadcn.TextButton
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
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
 * 选区比例模式。
 *
 * - FREE   自由：拖动不约束，适合先框出主体再进参数页
 * - GRID   网格比例：锁定当前网格宽高比（像素画默认走这档，原画 56:50）
 * - SQUARE 正方形：锁定 1:1，照片默认走这档——方图套任意方阵都不会拉伸
 */
private enum class CropAspect(val label: String) {
    FREE("自由"),
    GRID("网格比例"),
    SQUARE("正方形 1:1"),
}

/**
 * 裁剪与旋转页：拖动选区、角柄缩放、旋转/镜像、按网格比例锁定。
 * 确认后才真正裁剪像素；旋转与镜像即时生效（作用于工作图）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CropScreen(vm: AppViewModel) {
    val bitmap = vm.imagePreview
    val rect = vm.cropRect
    val imgW = vm.imageW
    val imgH = vm.imageH
    // 像素画默认锁原画比例（56:50 不是正方形，锁 1:1 会切掉一行画像素）；
    // 照片默认 1:1，导入时选区已经吸附成中心正方形。
    var aspect by remember {
        mutableStateOf(if (vm.pixelArtGrid != null) CropAspect.GRID else CropAspect.SQUARE)
    }
    var guides by remember { mutableStateOf(CropGuides.THIRDS) }
    val preview = vm.cropPreview
    // 位图包装只做一次：每帧 asImageBitmap 会新建 wrapper，画布每帧都会重画
    val previewImage = remember(preview) { preview?.image?.asImageBitmap() }

    /** 网格尺寸变了就把选区吸附到新比例的最大居中框：否则「定了网格」和框是两张皮 */
    fun snapToGridRatio() {
        if (imgW <= 0 || imgH <= 0) return
        val ratio = if (vm.gridH > 0) vm.gridW.toFloat() / vm.gridH else 1f
        aspect = CropAspect.GRID
        vm.updateCropRect(ImageTransforms.centeredAspect(imgW, imgH, ratio))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ShPageHeader(
            title = "裁剪与旋转",
            subtitle = "先定网格尺寸，框内直接看每格落什么色",
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
                    ratio = when (aspect) {
                        CropAspect.FREE -> null
                        CropAspect.SQUARE -> 1f
                        CropAspect.GRID -> if (vm.gridH > 0) vm.gridW.toFloat() / vm.gridH else 1f
                    },
                    guides = guides,
                    preview = preview,
                    previewImage = previewImage,
                    gridW = vm.gridW,
                    gridH = vm.gridH,
                    onRectChange = { vm.updateCropRect(it) },
                )
            }
        }

        Text(
            buildString {
                append("选区 ${rect.width.roundToInt()}×${rect.height.roundToInt()} px")
                append(" · 网格 ${vm.gridW}×${vm.gridH}")
                preview?.takeIf { it.gridW == vm.gridW && it.gridH == vm.gridH }
                    ?.let { append(" · ${it.colorCount} 色") }
                if (vm.cropPreviewRunning) append(" · 预览计算中…")
                when (aspect) {
                    CropAspect.SQUARE -> append(" · 锁定 1:1")
                    CropAspect.GRID -> append(" · 锁定 ${vm.gridW}:${vm.gridH}")
                    CropAspect.FREE -> append(" · 自由比例")
                }
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            ShChip(
                text = "辅助线·${guides.label}",
                selected = guides != CropGuides.NONE,
                onClick = { guides = guides.next() },
            )
            CropAspect.entries.forEach { mode ->
                ShChip(
                    text = mode.label,
                    selected = aspect == mode,
                    onClick = {
                        aspect = mode
                        // 切换比例时把选区吸附到该比例的最大居中框：
                        // 否则「已锁定」只是约束下一次拖动，当前选区仍是歪的，
                        // 点下一步照样变形。
                        vm.updateCropRect(
                            when (mode) {
                                CropAspect.FREE -> rect
                                CropAspect.SQUARE -> ImageTransforms.centerSquare(imgW, imgH)
                                CropAspect.GRID -> ImageTransforms.centeredAspect(
                                    imgW, imgH,
                                    if (vm.gridH > 0) vm.gridW.toFloat() / vm.gridH else 1f,
                                )
                            },
                        )
                    },
                )
            }
            ShChip(
                text = if (vm.cropPreviewEnabled) "网格预览·开" else "网格预览·关",
                selected = vm.cropPreviewEnabled,
                onClick = { vm.toggleCropPreview() },
            )
        }

        // 裁剪页就把网格定死：先看每格颜色，再决定框多大，下一步不用回来改
        ShCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text("网格", style = MaterialTheme.typography.labelLarge)
                    GridStepper(value = vm.gridW) {
                        vm.setCropGridWidth(it)
                        snapToGridRatio()
                    }
                    Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GridStepper(value = vm.gridH) {
                        vm.setCropGridHeight(it)
                        snapToGridRatio()
                    }
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
                        ShChip(
                            text = "原画 $aw×$ah",
                            selected = vm.gridW == aw && vm.gridH == ah,
                            onClick = {
                                vm.applyArtGridAtCrop()
                                snapToGridRatio()
                            },
                        )
                    }
                    listOf(24, 36, 48, 64, 100).forEach { w ->
                        ShChip(
                            text = "$w",
                            selected = vm.gridW == w,
                            onClick = {
                                vm.setCropGridWidth(w)
                                snapToGridRatio()
                            },
                        )
                    }
                    ShChip(
                        text = if (vm.lockGridAspect) "锁比例" else "自由比例",
                        selected = vm.lockGridAspect,
                        onClick = { vm.toggleGridAspectLock() },
                    )
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

/** 网格宽高步进器：−/值/+ 一组，40dp 触控高度，点一次改一格 */
@Composable
private fun GridStepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange((value - 1).coerceIn(5, 200)) },
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text("−", style = MaterialTheme.typography.labelLarge)
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
            Text("+", style = MaterialTheme.typography.labelLarge)
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
    onRectChange: (CropRect) -> Unit,
) {
    val currentRect by rememberUpdatedState(rect)
    val currentRatio by rememberUpdatedState(ratio)
    val currentGuides by rememberUpdatedState(guides)
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
                        val ratio = currentRatio
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
        drawCropUi(
            rect = currentRect,
            scale = scale,
            guides = currentGuides,
            preview = preview,
            previewImage = previewImage,
            gridW = gridW,
            gridH = gridH,
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

private fun DrawScope.drawCropUi(
    rect: CropRect,
    scale: Float,
    guides: CropGuides,
    preview: CropGridPreview?,
    previewImage: androidx.compose.ui.graphics.ImageBitmap?,
    gridW: Int,
    gridH: Int,
) {
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

    // 选区内的网格着色：一趟 drawImage 铺开整张缩略位图（最近邻放大），
    // 不是逐格 drawRect——后者 56×50 就 2800 次绘制，拖动时必然掉帧。
    // 预览的 rect 与当前选区不一致（正在拖动 / 防抖还没算完）时不画，
    // 否则色块会歪着跟手跑。此时只画网格线，位置永远准。
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
        // 每格小于 6px 时线宽和格子同量级，会糊成一片灰，这时不画线
        if (min(cellW, cellH) >= 6f) {
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

/** 预览矩形与当前选区是否同一个（拖动会产生亚像素差，0.5px 内视为一致） */
private fun rectsMatch(a: CropRect, b: CropRect): Boolean =
    abs(a.left - b.left) < 0.5f && abs(a.top - b.top) < 0.5f &&
        abs(a.right - b.right) < 0.5f && abs(a.bottom - b.bottom) < 0.5f

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
    // 构图辅助线与网格线是两套语义：辅助线是"看构图的主线"，压黑加粗；
    // 网格线是"数量刻度"，留白细线。都画白线时两者在彩色色块上糊成一片，
    // 分不清哪条是三分线、哪条是格子边。
    val line = Color.Black.copy(alpha = 0.62f)
    // 深色底图上纯黑会隐身：垫一层极淡白边只做轮廓，主色仍是黑
    val halo = Color.White.copy(alpha = 0.30f)
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
