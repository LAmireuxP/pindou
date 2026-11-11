package com.pindou.app.ui.editor

import com.pindou.app.ui.icon.MorphIcons
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pindou.app.core.editor.BrushStamp
import com.pindou.app.core.editor.ColorReplaceOps
import com.pindou.app.core.editor.EditorEngine
import com.pindou.app.core.editor.RunRects
import com.pindou.app.ui.AppViewModel
import com.pindou.app.ui.CellCodeDrawer
import com.pindou.app.ui.CellCodeDrawer.drawCellCode
import com.pindou.app.ui.EditorTool
import com.pindou.app.ui.shadcn.ShSearchField
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 画布变换。字段必须是 Compose state，否则捏合/平移只改字段不会触发重绘。 */
private class CanvasTransform {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

/**
 * 空格底色。原来是 (x+y) 交替的棋盘格，等于每格一次 drawRect：
 * 一张 60×60 的图光空背景就 3600 次绘制，滑动上色每帧重录一遍必掉帧。
 */
private val EmptyCellColor = Color(0xFFEDEDE7)

private fun baseOrigin(canvas: Offset, engine: EditorEngine): Offset {
    val cellBase = min(canvas.x / engine.width, canvas.y / engine.height)
    return Offset(
        (canvas.x - cellBase * engine.width) / 2f,
        (canvas.y - cellBase * engine.height) / 2f,
    )
}

private fun drawOrigin(canvas: Offset, tf: CanvasTransform, engine: EditorEngine): Offset =
    baseOrigin(canvas, engine) + tf.offset

private fun cellPx(canvas: Offset, tf: CanvasTransform, engine: EditorEngine): Float =
    min(canvas.x / engine.width, canvas.y / engine.height) * tf.scale

private fun cellAt(view: Offset, canvas: Offset, tf: CanvasTransform, engine: EditorEngine): Int {
    val cell = cellPx(canvas, tf, engine)
    if (cell <= 0f) return -1
    val origin = drawOrigin(canvas, tf, engine)
    val x = ((view.x - origin.x) / cell).toInt()
    val y = ((view.y - origin.y) / cell).toInt()
    if (x < 0 || y < 0 || x >= engine.width || y >= engine.height) return -1
    return y * engine.width + x
}

/** 把画布拉回可视区：缩放到 1 倍时强制居中；放大后允许在四边内平移。 */
private fun clampTransform(canvas: Offset, engine: EditorEngine, tf: CanvasTransform) {
    if (canvas.x <= 0f || canvas.y <= 0f) return
    val cell = cellPx(canvas, tf, engine)
    if (cell <= 0f) return
    val base = baseOrigin(canvas, engine)
    val origin = drawOrigin(canvas, tf, engine)
    val contentW = cell * engine.width
    val contentH = cell * engine.height
    val x = if (contentW <= canvas.x) {
        (canvas.x - contentW) / 2f
    } else {
        origin.x.coerceIn(canvas.x - contentW, 0f)
    }
    val y = if (contentH <= canvas.y) {
        (canvas.y - contentH) / 2f
    } else {
        origin.y.coerceIn(canvas.y - contentH, 0f)
    }
    tf.offset = Offset(x - base.x, y - base.y)
}

private fun zoomAt(
    canvas: Offset,
    engine: EditorEngine,
    tf: CanvasTransform,
    focus: Offset,
    targetScale: Float,
) {
    val oldScale = tf.scale
    val newScale = targetScale.coerceIn(1f, 20f)
    if (abs(newScale - oldScale) < 0.001f) return
    val origin = drawOrigin(canvas, tf, engine)
    val k = newScale / oldScale
    val newOrigin = Offset(
        focus.x - (focus.x - origin.x) * k,
        focus.y - (focus.y - origin.y) * k,
    )
    tf.scale = newScale
    tf.offset = newOrigin - baseOrigin(canvas, engine)
    clampTransform(canvas, engine, tf)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel) {
    val engine = vm.editor ?: return
    val palette = vm.selectedPalette ?: return
    if (palette.colors.isEmpty()) return

    val safeColorIndex = vm.editorColor.coerceIn(0, palette.colors.lastIndex)
    val currentEntry = palette.colors[safeColorIndex]
    // 色号/ARGB 只跟色卡有关，别在每次重组（每条笔画都会重组一次）时 map 一遍
    val paletteCodes = remember(palette) { palette.colors.map { it.code } }
    val paletteArgb = remember(palette) { palette.colors.map { it.argb } }
    // 色卡弹层关掉后仍然保留文本测量结果：291 色第二次打开、来回滑都不再
    // 重新排版色号。放在 if (showPaletteSheet) 里面会随弹层一起销毁。
    val swatchMeasurer = rememberTextMeasurer(cacheSize = 128)
    val swatchTextStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )
    val swatchTextCache = remember(swatchTextStyle) {
        mutableMapOf<String, TextLayoutResult>()
    }

    var showPaletteSheet by remember { mutableStateOf(false) }
    var showManageSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }
    var showReplaceSheet by remember { mutableStateOf(false) }
    var replaceSource by remember { mutableIntStateOf(0) }
    var colorOpReport by remember { mutableStateOf<List<ColorReplaceOps.Move>>(emptyList()) }
    var longPressCell by remember { mutableStateOf<Int?>(null) }
    // 长按时只记下格子并高亮，等手指抬起再弹菜单：
    // 弹窗若在按下的中途创建，抬起事件会落到新窗口上被当成「点外部」直接关掉。
    var cellSheetOpen by remember { mutableStateOf(false) }
    var paletteQuery by remember { mutableStateOf("") }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val transform = remember { CanvasTransform() }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(engine.pageIndex, engine.width, engine.height) {
        transform.reset()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        // 顶栏：图纸信息在左，历史/更多/完成在右
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.padding(start = Spacing.sm)) {
                Text(
                    "${engine.width}×${engine.height}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${palette.brandLabel} · 第 ${engine.pageIndex + 1}/${engine.pageCount} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                UndoRedoButtons(vm, engine)
                IconButton(onClick = { showMenu = true }) {
                    Icon(MorphIcons.MoreVertical, contentDescription = "更多")
                }
                Button(
                    onClick = { vm.finishEditor() },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text("完成", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // 画布 + 悬浮工具（原图对比 / 缩放）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Spacing.xs)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .onSizeChanged { canvasSize = it },
        ) {
            EditorCanvas(
                engine = engine,
                paletteCodes = paletteCodes,
                paletteArgb = paletteArgb,
                transform = transform,
                showCodes = vm.editorShowCodes,
                showGrid = vm.editorShowGrid,
                // 传 lambda 而不是 Int：Int 参数会让整个编辑页在每条笔画结束时
                // 重组一次（色卡条、最近颜色、工具条全部重算）；lambda 只在 draw
                // 阶段读，revision 变化只失效画布。
                revisionProvider = { vm.editorRevision },
                tool = vm.editorTool,
                brushSize = vm.editorBrushSize,
                refImage = vm.refBitmap?.takeIf { vm.showRefOverlay }?.asImageBitmap(),
                refAlpha = vm.refOpacity,
                highlightCell = longPressCell,
                onTap = { cell -> handleTap(vm, engine, cell, vm.editorBrushSize) },
                onStrokeCell = { cell, session ->
                    handleStrokeCell(vm, engine, cell, session, vm.editorBrushSize)
                },
                onLongPress = { cell ->
                    if (cell >= 0) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        longPressCell = cell
                    }
                },
                onLongPressCommit = { cell ->
                    // 手指抬起才真正打开菜单，此时没有进行中的手势
                    if (cell >= 0 && longPressCell == cell) cellSheetOpen = true
                },
                onStrokeEnd = { vm.notifyEditorChanged() },
            )

            if (vm.refBitmap != null) {
                CanvasPill(
                    icon = MorphIcons.Eye,
                    text = if (vm.showRefOverlay) {
                        "原图 ${(vm.refOpacity * 100).roundToInt()}%"
                    } else {
                        "原图"
                    },
                    selected = vm.showRefOverlay,
                    onClick = { vm.toggleRefOverlay() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }

            // transform 的读取全部收进 ZoomControls：捏合/拖动时只重组这一行，
            // 避免整页重算。
            ZoomControls(
                canvasSize = canvasSize,
                engine = engine,
                transform = transform,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            )

            // 改色结果条：贴在画布顶上，不挡画布手势，6 秒自动收
            if (colorOpReport.isNotEmpty()) {
                ColorOpReportBar(
                    moves = colorOpReport,
                    palette = palette,
                    onUndo = {
                        vm.undoEditor()
                        colorOpReport = emptyList()
                    },
                    onDismiss = { colorOpReport = emptyList() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                )
            }
        }

        // 页码行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { vm.switchEditorPage(engine.pageIndex - 1) },
                    enabled = engine.pageIndex > 0,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        MorphIcons.ChevronLeft,
                        contentDescription = "上一页",
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    "第 ${engine.pageIndex + 1}/${engine.pageCount} 页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { vm.switchEditorPage(engine.pageIndex + 1) },
                    enabled = engine.pageIndex < engine.pageCount - 1,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        MorphIcons.ChevronRight,
                        contentDescription = "下一页",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            TextButton(
                onClick = { vm.addEditorPage(false) },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("+ 新页", style = MaterialTheme.typography.labelMedium)
            }
        }

        // 工具条：当前色 + 画笔/油漆桶/橡皮/吸管/移动
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorCubeButton(
                code = currentEntry.code,
                argb = currentEntry.argb,
                onClick = { showPaletteSheet = true },
            )
            EditorToolButton(
                MorphIcons.Brush,
                "画笔",
                vm.editorTool == EditorTool.PAINT,
                modifier = Modifier.weight(1f),
            ) { vm.editorTool = EditorTool.PAINT }
            EditorToolButton(
                MorphIcons.PaintBucket,
                "填充",
                vm.editorTool == EditorTool.FILL,
                modifier = Modifier.weight(1f),
            ) { vm.editorTool = EditorTool.FILL }
            EditorToolButton(
                MorphIcons.Eraser,
                "橡皮",
                vm.editorTool == EditorTool.ERASE,
                modifier = Modifier.weight(1f),
            ) { vm.editorTool = EditorTool.ERASE }
            EditorToolButton(
                MorphIcons.Pipette,
                "吸管",
                vm.editorTool == EditorTool.PICKER,
                modifier = Modifier.weight(1f),
            ) { vm.editorTool = EditorTool.PICKER }
            EditorToolButton(
                MorphIcons.Hand,
                "移动",
                vm.editorTool == EditorTool.PAN,
                modifier = Modifier.weight(1f),
            ) { vm.editorTool = EditorTool.PAN }
        }

        // 笔刷尺寸 + 显示开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "笔刷",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrushStamp.sizes.forEach { size ->
                CompactChip(
                    text = "${size}×${size}",
                    selected = vm.editorBrushSize == size,
                    onClick = { vm.editorBrushSize = size },
                )
            }
            Spacer(Modifier.weight(1f))
            CompactChip(
                text = "色号",
                selected = vm.editorShowCodes,
                onClick = { vm.editorShowCodes = !vm.editorShowCodes },
            )
            CompactChip(
                text = "网格",
                selected = vm.editorShowGrid,
                onClick = { vm.editorShowGrid = !vm.editorShowGrid },
            )
        }

        Text(
            when (vm.editorTool) {
                EditorTool.NONE -> "先选一个工具再编辑 · 单指拖动画布 · 双指缩放"
                EditorTool.PAINT -> "点按/划动上色 · 双指缩放 · 长按单格菜单"
                EditorTool.ERASE ->
                    "擦除 ${vm.editorBrushSize}×${vm.editorBrushSize} · 双指缩放 · 长按单格菜单"
                EditorTool.FILL -> "点按填充同色连通区域 · 双指缩放"
                EditorTool.PICKER -> "点按格子取色，取完自动切回画笔"
                EditorTool.PAN -> "单指拖动画布 · 双指缩放 · 点「适应」回到全图"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .padding(bottom = 2.dp),
        )
    }

    // 色卡选择
    if (showPaletteSheet) {
        val filtered = remember(palette.brand, paletteQuery) {
            palette.colors.withIndex().filter { (_, entry) ->
                paletteQuery.isBlank() ||
                    entry.code.contains(paletteQuery, ignoreCase = true) ||
                    entry.name?.contains(paletteQuery, ignoreCase = true) == true
            }
        }
        // 色卡要在弹层里来回滑：每个色块只留一个 Canvas 节点。原来的
        // Column/Box/背景/描边/Text 五层布局，在首次滑到新行时每格都要
        // 走一遍测量和组合，正是那一下的顿挫来源。
        val primaryColor = MaterialTheme.colorScheme.primary
        ModalBottomSheet(onDismissRequest = { showPaletteSheet = false }) {
            Column(
                Modifier
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(currentEntry.argb), RoundedCornerShape(10.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(10.dp),
                            ),
                    )
                    Column(Modifier.padding(start = Spacing.sm)) {
                        Text(currentEntry.code, style = MaterialTheme.typography.titleMedium)
                        Text(
                            currentEntry.name?.ifBlank { palette.brandLabel } ?: palette.brandLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${palette.colors.size} 色",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ShSearchField(
                    value = paletteQuery,
                    onValueChange = { paletteQuery = it },
                    placeholder = "搜索色号或颜色名",
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        if (paletteQuery.isNotEmpty()) {
                            IconButton(onClick = { paletteQuery = "" }) {
                                Icon(
                                    MorphIcons.X,
                                    contentDescription = "清空搜索",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                )

                if (vm.editorRecentColors.isNotEmpty()) {
                    Text(
                        "最近使用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        vm.editorRecentColors.take(8).forEach { index ->
                            val entry = palette.colors.getOrNull(index) ?: return@forEach
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color(entry.argb), RoundedCornerShape(9.dp))
                                    .border(
                                        width = if (index == safeColorIndex) 2.dp else 1.dp,
                                        color = if (index == safeColorIndex) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = RoundedCornerShape(9.dp),
                                    )
                                    .clickable {
                                        vm.selectEditorColor(index)
                                        showPaletteSheet = false
                                    },
                            )
                        }
                    }
                }

                Text(
                    if (paletteQuery.isBlank()) {
                        "点击颜色后自动切回画笔"
                    } else {
                        "匹配 ${filtered.size} / ${palette.colors.size} 色"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (filtered.isEmpty()) {
                    // 空结果要给出可执行出口，而不是留一块空白
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "没有匹配的色号",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { paletteQuery = "" }) { Text("清空搜索") }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 430.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            count = filtered.size,
                            key = { filtered[it].index },
                            contentType = { "swatch" },
                        ) { pos ->
                            val (index, entry) = filtered[pos]
                            PaletteSwatch(
                                code = entry.code,
                                name = entry.name,
                                argb = entry.argb,
                                selected = index == safeColorIndex,
                                measurer = swatchMeasurer,
                                textStyle = swatchTextStyle,
                                textCache = swatchTextCache,
                                primaryColor = primaryColor,
                                onClick = {
                                    vm.selectEditorColor(index)
                                    showPaletteSheet = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ⋮ 更多
    if (showMenu) {
        ModalBottomSheet(onDismissRequest = { showMenu = false }) {
            Column(
                Modifier
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "更多操作",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = Spacing.xs),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xs, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("参考图对比", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (vm.refBitmap == null) "当前图纸没有原图" else "在原图上对位涂色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = vm.showRefOverlay,
                        onCheckedChange = { vm.toggleRefOverlay() },
                        enabled = vm.refBitmap != null,
                    )
                }
                if (vm.showRefOverlay && vm.refBitmap != null) {
                    Text(
                        "透明度 ${(vm.refOpacity * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = vm.refOpacity,
                        onValueChange = { vm.updateRefOpacity(it) },
                        valueRange = 0.05f..1f,
                    )
                }
                MenuItemRow(
                    icon = MorphIcons.PaintBucket,
                    title = "色板管理",
                    subtitle = "换品牌 / 合并相近色",
                ) {
                    showMenu = false
                    showManageSheet = true
                }
                MenuItemRow(
                    icon = MorphIcons.Palette,
                    title = "替换相似颜色",
                    subtitle = "同色系并成一个色号，减少换豆",
                ) {
                    showMenu = false
                    // 默认源色取「当前画笔色」；画笔停在一个没用过的色号上时，
                    // 直接跳到用量最少的已用色——否则一进来就是「0 格 / 没有可替换的格子」，
                    // 用户还得自己翻色卡找哪个色真正在图上。
                    val cur = vm.editorColor.coerceIn(0, palette.colors.lastIndex)
                    val use = ColorReplaceOps.usage(engine.pages, palette.colors.size)
                    replaceSource = if (use.getOrElse(cur) { 0 } > 0) {
                        cur
                    } else {
                        use.indices.filter { use[it] > 0 }.minByOrNull { use[it] } ?: cur
                    }
                    showReplaceSheet = true
                }
                MenuItemRow(
                    icon = MorphIcons.Copy,
                    title = "复制当前页",
                    subtitle = "新页继承当前页内容与锁定状态",
                ) {
                    vm.addEditorPage(true)
                    showMenu = false
                }
                MenuItemRow(
                    icon = MorphIcons.Trash,
                    title = "删除当前页",
                    subtitle = if (engine.pageCount <= 1) "至少保留一页" else "可撤销",
                    enabled = engine.pageCount > 1,
                ) {
                    vm.removeEditorPage(engine.pageIndex)
                    showMenu = false
                }
                MenuItemRow(
                    icon = MorphIcons.SwapHorizontal,
                    title = "改尺寸",
                    subtitle = "所有页内容居中保留",
                ) {
                    showMenu = false
                    showResizeDialog = true
                }
                MenuItemRow(
                    icon = MorphIcons.Download,
                    title = "导出 .bpat 图纸",
                    subtitle = "包含全部页、色卡与锁定状态",
                ) {
                    showMenu = false
                    vm.exportBpatFile()
                }
            }
        }
    }

    // 改尺寸
    if (showResizeDialog) {
        var w by remember { mutableStateOf(engine.width.toString()) }
        var h by remember { mutableStateOf(engine.height.toString()) }
        AlertDialog(
            onDismissRequest = { showResizeDialog = false },
            title = { Text("改尺寸") },
            text = {
                Column {
                    Text(
                        "内容居中保留；施工进度会因尺寸变化作废",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.padding(top = Spacing.sm),
                    ) {
                        OutlinedTextField(
                            value = w,
                            onValueChange = { w = it.filter { ch -> ch.isDigit() }.take(3) },
                            label = { Text("宽") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = h,
                            onValueChange = { h = it.filter { ch -> ch.isDigit() }.take(3) },
                            label = { Text("高") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val nw = w.toIntOrNull() ?: 0
                    val nh = h.toIntOrNull() ?: 0
                    if (nw in 5..200 && nh in 5..200) {
                        vm.resizeEditor(nw, nh)
                        showResizeDialog = false
                    }
                }) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { showResizeDialog = false }) { Text("取消") }
            },
        )
    }

    // 色板管理
    if (showManageSheet) {
        var mergeThreshold by remember { mutableFloatStateOf(3f) }
        ModalBottomSheet(onDismissRequest = { showManageSheet = false }) {
            Column(
                Modifier
                    .padding(Spacing.lg)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text("换品牌色卡", style = MaterialTheme.typography.titleLarge)
                Text(
                    "全图按最近色重映射到所选品牌（仅列同规格）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    vm.palettes
                        .filter { it.beadSize == palette.beadSize }
                        .forEach { p ->
                            val selected = p.brand == palette.brand
                            TextButton(
                                onClick = {
                                    vm.applyPaletteRemap(p)
                                    showManageSheet = false
                                },
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = if (selected) {
                                        ClayColors.PrimaryInk
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                ),
                            ) { Text(if (selected) "✓${p.brandLabel}" else p.brandLabel) }
                        }
                }

                Text("合并相近色", style = MaterialTheme.typography.titleLarge)
                Text(
                    "ΔE 阈值 %.1f：阈值越大合并越激进".format(mergeThreshold),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = mergeThreshold,
                    onValueChange = { mergeThreshold = it },
                    valueRange = 1f..10f,
                )
                Button(
                    onClick = {
                        vm.mergeSimilarEditorColors(mergeThreshold.toDouble())
                        showManageSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("按当前阈值合并") }
            }
        }
    }

    // 替换相似颜色
    if (showReplaceSheet) {
        ColorReplaceSheet(
            vm = vm,
            palette = palette,
            engine = engine,
            initialSource = replaceSource,
            onDismiss = { showReplaceSheet = false },
            onReport = { colorOpReport = it },
        )
    }
    // 长按单格：底部弹层，动作区在手指可达范围内
    if (cellSheetOpen && longPressCell != null) {
        val cell = longPressCell!!
        if (cell !in engine.cells.indices) {
            cellSheetOpen = false
            longPressCell = null
            return
        }
        val colorIdx = engine.cells[cell]
        val cellEntry = palette.colors.getOrNull(colorIdx)
        val isLocked = engine.locked[cell]
        val sameColorCount = if (colorIdx >= 0) engine.cells.count { it == colorIdx } else 0
        // 连通块大小只跟格子内容有关：按格子 + 修订号缓存，避免每次重组都跑一遍 BFS
        val blockCount = remember(cell, vm.editorRevision) {
            if (colorIdx >= 0) engine.connectedRegionSize(cell) else 0
        }
        val cellX = cell % engine.width + 1
        val cellY = cell / engine.width + 1
        // 确认态跟着格子走：换一格后不能把上一格的「再点一次」带过来
        var replaceArmed by remember(cell) { mutableStateOf(false) }
        val closeSheet = {
            cellSheetOpen = false
            longPressCell = null
        }
        ModalBottomSheet(onDismissRequest = {
            cellSheetOpen = false
            longPressCell = null
        }) {
            Column(
                Modifier
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (cellEntry != null) Color(cellEntry.argb) else Color(0xFFF2F2F3),
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(12.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cellEntry == null) {
                            Text(
                                "空",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = Spacing.sm),
                    ) {
                        Text(
                            if (cellEntry != null) cellEntry.code else "空格",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            (cellEntry?.name?.takeIf { it.isNotBlank() }?.plus(" · ") ?: "") +
                                "第 $cellY 行 $cellX 列",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            if (isLocked) "已锁定" else "本页 $sameColorCount 格",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CellActionTile(
                        icon = MorphIcons.PaintBucket,
                        title = "填充此格",
                        subtitle = "用 ${currentEntry.code}",
                        enabled = !isLocked && colorIdx != safeColorIndex,
                        modifier = Modifier.weight(1f),
                    ) {
                        applyCellColor(vm, engine, cell, safeColorIndex)
                        closeSheet()
                    }
                    CellActionTile(
                        icon = MorphIcons.Eraser,
                        title = "清除此格",
                        subtitle = "留空",
                        enabled = !isLocked && colorIdx >= 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        applyCellColor(vm, engine, cell, -1)
                        closeSheet()
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CellActionTile(
                        icon = MorphIcons.Pipette,
                        title = "取此格颜色",
                        subtitle = if (cellEntry != null) "切到 ${cellEntry.code}" else "空格无颜色",
                        enabled = cellEntry != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        vm.selectEditorColor(colorIdx)
                        closeSheet()
                    }
                    CellActionTile(
                        icon = MorphIcons.Brush,
                        title = "整块换色",
                        subtitle = if (blockCount > 0) {
                            "连通 $blockCount 格 → ${currentEntry.code}"
                        } else {
                            "空格无连通区"
                        },
                        enabled = !isLocked && colorIdx >= 0 &&
                            colorIdx != safeColorIndex && blockCount > 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (engine.floodFill(cell, safeColorIndex)) {
                            vm.notifyEditorChanged()
                        }
                        closeSheet()
                    }
                }

                // 一整页同色可能是几百格，误触代价大：先点亮，再点一次才执行
                val needConfirm = sameColorCount >= 100
                CellActionTile(
                    icon = MorphIcons.SwapHorizontal,
                    title = if (replaceArmed) "再点一次执行" else "同色替换整页",
                    subtitle = if (replaceArmed) {
                        "将改 $sameColorCount 格 → ${currentEntry.code}"
                    } else {
                        "本页 $sameColorCount 格 → ${currentEntry.code}"
                    },
                    emphasis = replaceArmed,
                    enabled = !isLocked && colorIdx >= 0 && colorIdx != safeColorIndex,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (needConfirm && !replaceArmed) {
                        replaceArmed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else {
                        if (engine.replaceColorOnPage(colorIdx, safeColorIndex)) {
                            vm.notifyEditorChanged()
                        }
                        closeSheet()
                    }
                }

                CellActionTile(
                    icon = if (isLocked) MorphIcons.LockOpen else MorphIcons.Lock,
                    title = if (isLocked) "解锁此格" else "锁定此格",
                    subtitle = "锁定的格子不会被画笔、填充或替换改动",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    engine.setLocked(cell, !isLocked)
                    vm.notifyEditorChanged()
                    closeSheet()
                }

                CellActionTile(
                    icon = MorphIcons.Palette,
                    title = "替换相似颜色",
                    subtitle = if (cellEntry != null) {
                        "整图 ${cellEntry.code} 及相近色 → 指定色"
                    } else {
                        "空格没有颜色"
                    },
                    enabled = cellEntry != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val seed = colorIdx
                    closeSheet()
                    if (seed >= 0) {
                        replaceSource = seed
                        showReplaceSheet = true
                    }
                }
            }
        }
    }
}


/**
 * 撤销/重做按钮。单独抽出来是为了让「每条笔画结束 +1 的 revision」只重组这两个
 * 图标按钮，而不是整个编辑页——编辑页里有色卡网格、工具条、底栏，
 * 全量重组一次就是十几毫秒的掉帧。
 */
@Composable
private fun UndoRedoButtons(vm: AppViewModel, engine: EditorEngine) {
    val revision = vm.editorRevision
    IconButton(
        onClick = { vm.undoEditor() },
        enabled = remember(engine, revision) { engine.canUndo },
    ) {
        Icon(MorphIcons.Undo, contentDescription = "撤销")
    }
    IconButton(
        onClick = { vm.redoEditor() },
        enabled = remember(engine, revision) { engine.canRedo },
    ) {
        Icon(MorphIcons.Redo, contentDescription = "重做")
    }
}

private fun applyCellColor(vm: AppViewModel, engine: EditorEngine, cell: Int, color: Int) {
    val session = engine.startStroke()
    session.setCell(cell, color)
    engine.endStroke(session)
    vm.notifyEditorChanged()
}

private fun handleTap(vm: AppViewModel, engine: EditorEngine, cell: Int, brushSize: Int) {
    if (cell < 0) return
    when (vm.editorTool) {
        EditorTool.NONE -> Unit
        EditorTool.PICKER -> {
            val color = engine.cells.getOrNull(cell) ?: return
            if (color >= 0) vm.selectEditorColor(color)
        }
        EditorTool.FILL -> {
            if (engine.floodFill(cell, vm.editorColor)) vm.notifyEditorChanged()
        }
        EditorTool.PAINT -> {
            val session = engine.startStroke()
            stampBrush(engine, cell, brushSize, session, vm.editorColor)
            engine.endStroke(session)
            vm.notifyEditorChanged()
        }
        EditorTool.ERASE -> {
            val session = engine.startStroke()
            stampBrush(engine, cell, brushSize, session, -1)
            engine.endStroke(session)
            vm.notifyEditorChanged()
        }
        EditorTool.PAN -> Unit
    }
}

private fun stampBrush(
    engine: EditorEngine,
    center: Int,
    brushSize: Int,
    session: EditorEngine.StrokeSession,
    color: Int,
) {
    BrushStamp.forEachCell(engine.width, engine.height, center, brushSize) { index ->
        session.setCell(index, color)
    }
}

private fun handleStrokeCell(
    vm: AppViewModel,
    engine: EditorEngine,
    cell: Int,
    session: EditorEngine.StrokeSession,
    brushSize: Int,
) {
    if (cell < 0) return
    when (vm.editorTool) {
        EditorTool.NONE -> Unit
        EditorTool.PAINT -> stampBrush(engine, cell, brushSize, session, vm.editorColor)
        EditorTool.ERASE -> stampBrush(engine, cell, brushSize, session, -1)
        EditorTool.FILL, EditorTool.PICKER, EditorTool.PAN -> Unit
    }
}

@Composable
private fun ColorCubeButton(code: String, argb: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(52.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(27.dp)
                .background(Color(argb), RoundedCornerShape(7.dp))
                .border(1.dp, Color.Black.copy(alpha = 0.12f), RoundedCornerShape(7.dp)),
        )
        Text(
            code,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .padding(top = 1.dp),
        )
    }
}

@Composable
private fun EditorToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.outline,
                shape,
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) {
                ClayColors.PrimaryInk
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(19.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = if (selected) {
                ClayColors.PrimaryInk
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun CompactChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
                else MaterialTheme.colorScheme.outline,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                ClayColors.PrimaryInk
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun CanvasPill(
    icon: ImageVector,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(9999.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outline,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) ClayColors.PrimaryInk
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) ClayColors.PrimaryInk
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun ZoomControls(
    canvasSize: IntSize,
    engine: EditorEngine,
    transform: CanvasTransform,
    modifier: Modifier = Modifier,
) {
    // derivedStateOf 只在跨过阈值时通知：拖动平移只改 offset，
    // 不会每帧让这一行重组，更不会牵动编辑页其它区域。
    val canZoomOut by remember { derivedStateOf { transform.scale > 1.01f } }
    val canZoomIn by remember { derivedStateOf { transform.scale < 19.99f } }
    val canFit by remember {
        derivedStateOf { transform.scale > 1.01f || transform.offset != Offset.Zero }
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp),
            )
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val canvas = Offset(canvasSize.width.toFloat(), canvasSize.height.toFloat())
        val focus = Offset(canvas.x / 2f, canvas.y / 2f)
        ZoomButton("−", enabled = canZoomOut) {
            zoomAt(canvas, engine, transform, focus, transform.scale / 1.5f)
        }
        ZoomPercent(transform)
        ZoomButton("+", enabled = canZoomIn) {
            zoomAt(canvas, engine, transform, focus, transform.scale * 1.5f)
        }
        ZoomButton("适应", enabled = canFit) {
            transform.reset()
        }
    }
}

/** 百分比单独成一个重组作用域：捏合时每帧只重算这一个 Text。 */
@Composable
private fun ZoomPercent(transform: CanvasTransform) {
    Text(
        "${(transform.scale * 100).roundToInt()}%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(44.dp),
    )
}

@Composable
private fun ZoomButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun MenuItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = Spacing.sm)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CellActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    emphasis: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        emphasis -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    }
    val strokeColor = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
        emphasis -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(if (emphasis) 1.5.dp else 1.dp, strokeColor, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) {
                ClayColors.PrimaryInk
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            },
            modifier = Modifier.size(20.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EditorCanvas(
    engine: EditorEngine,
    paletteCodes: List<String>,
    paletteArgb: List<Int>,
    transform: CanvasTransform,
    showCodes: Boolean,
    showGrid: Boolean,
    revisionProvider: () -> Int,
    tool: EditorTool,
    brushSize: Int,
    refImage: ImageBitmap?,
    refAlpha: Float,
    highlightCell: Int?,
    onTap: (Int) -> Unit,
    onStrokeCell: (Int, EditorEngine.StrokeSession) -> Unit,
    onLongPress: (Int) -> Unit,
    onLongPressCommit: (Int) -> Unit,
    onStrokeEnd: () -> Unit,
) {
    // pointerInput 的 block 只在 key 变化时重建，会一直持有首次组合的 lambda。
    // 用 rememberUpdatedState 把回调换成可变引用，避免旧闭包写旧状态。
    val tapState by rememberUpdatedState(onTap)
    val strokeCellState by rememberUpdatedState(onStrokeCell)
    val longPressState by rememberUpdatedState(onLongPress)
    val longPressCommitState by rememberUpdatedState(onLongPressCommit)
    val strokeEndState by rememberUpdatedState(onStrokeEnd)

    val measurer = rememberTextMeasurer()
    // 换色卡后同色号可能对应不同明暗，旧布局的文字黑白会错；按色卡重建
    val layoutCache = remember(paletteArgb) {
        mutableMapOf<Pair<String, Int>, androidx.compose.ui.text.TextLayoutResult>()
    }
    val lockPath = remember {
        Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 0.30f)
            lineTo(-0.30f, 0f)
            close()
        }
    }
    val lockColor = Color(0xE6F59E0B)
    val lockOutline = Color(0xE6FFFFFF)

    // 交互中每帧只失效「绘制」、不触发重组：这两个 state 只在 Canvas 的
    // draw lambda 里读，Compose 只会重录显示列表，不会重新执行整个编辑页。
    // gestureActive 覆盖画笔、平移、双指缩放：手指一落下就隐藏色号，
    // 动画期间每帧省掉上千次 text layout + drawText，抬手立刻恢复。
    val liveTick = remember { mutableIntStateOf(0) }
    val gestureActive = remember { mutableStateOf(false) }
    val cellColors = remember(paletteArgb) { Array(paletteArgb.size) { Color(paletteArgb[it]) } }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(tool, engine, brushSize) {
                awaitEachGesture {
                    val canvas = Offset(size.width.toFloat(), size.height.toFloat())
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    val startCell = cellAt(start, canvas, transform, engine)
                    var last = start
                    var lastCell = startCell
                    var moved = false
                    var longPressed = false
                    var isTransform = false
                    var stroke: EditorEngine.StrokeSession? = null
                    var prevDist = 0f
                    var prevCentroid = Offset.Zero
                    // 笔画中的重绘节流：指针事件比屏幕刷新密，全跟会把 UI 线程塞满
                    var lastBumpNs = 0L

                    // finally 保证取消/异常也能恢复色号，不会留下「滑动后没字」的状态
                    try {
                        // 长按与拖动竞争：超时前没有指针事件 = 长按
                        var pending = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            awaitPointerEvent()
                        }
                        if (pending == null) {
                            longPressed = true
                            longPressState(startCell)
                        }

                        while (true) {
                            val event = pending ?: awaitPointerEvent()
                            pending = null
                            val pressed = event.changes.filter { it.pressed }

                            if (pressed.size >= 2) {
                                if (!isTransform) {
                                    isTransform = true
                                    gestureActive.value = true
                                    stroke?.let {
                                        engine.endStroke(it)
                                        strokeEndState()
                                    }
                                    stroke = null
                                }
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                                val dist = hypot(
                                    (a.x - b.x).toDouble(),
                                    (a.y - b.y).toDouble(),
                                ).toFloat()
                                if (prevDist > 0f && dist > 0f) {
                                    zoomAt(
                                        canvas,
                                        engine,
                                        transform,
                                        centroid,
                                        transform.scale * (dist / prevDist),
                                    )
                                }
                                if (prevCentroid != Offset.Zero) {
                                    transform.offset += centroid - prevCentroid
                                    clampTransform(canvas, engine, transform)
                                }
                                prevDist = dist
                                prevCentroid = centroid
                            } else if (pressed.size == 1) {
                                val change = pressed[0]
                                val pos = change.position
                                val totalMove = hypot(
                                    (pos.x - start.x).toDouble(),
                                    (pos.y - start.y).toDouble(),
                                ).toFloat()

                                if (!isTransform && !moved && totalMove > viewConfiguration.touchSlop) {
                                    moved = true
                                    gestureActive.value = true
                                    if (!longPressed &&
                                        (tool == EditorTool.PAINT || tool == EditorTool.ERASE)
                                    ) {
                                        stroke = engine.startStroke()
                                    }
                                }

                                // 未选工具时单指只平移画布，不落笔：新手进图先看，
                                // 手一滑也不会把颜色涂到格子上。
                                if (!isTransform &&
                                    (tool == EditorTool.PAN || tool == EditorTool.NONE) &&
                                    moved
                                ) {
                                    transform.offset += pos - last
                                    clampTransform(canvas, engine, transform)
                                } else if (!isTransform && moved && !longPressed &&
                                    (tool == EditorTool.PAINT || tool == EditorTool.ERASE)
                                ) {
                                    val session = stroke ?: engine.startStroke().also { stroke = it }
                                    val cellNow = cellPx(canvas, transform, engine)
                                    val stepPx = max(2f, cellNow / 2f)
                                    val distance = hypot(
                                        (pos.x - last.x).toDouble(),
                                        (pos.y - last.y).toDouble(),
                                    ).toFloat()
                                    val steps = max(1, (distance / stepPx).toInt())
                                    for (i in 1..steps) {
                                        val t = i.toFloat() / steps
                                        val p = Offset(
                                            last.x + (pos.x - last.x) * t,
                                            last.y + (pos.y - last.y) * t,
                                        )
                                        strokeCellState(cellAt(p, canvas, transform, engine), session)
                                    }
                                    // 8ms ≈ 120Hz 上限：画到哪儿立刻能看到，又不会每个
                                    // 指针事件都重录一遍整块画布
                                    val now = System.nanoTime()
                                    if (now - lastBumpNs >= 8_000_000L) {
                                        lastBumpNs = now
                                        liveTick.intValue++
                                    }
                                }

                                lastCell = cellAt(pos, canvas, transform, engine)
                                last = pos
                            }

                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }

                            if (pressed.isEmpty()) {
                                if (longPressed) {
                                    // 长按后抬起：交给上层开菜单，避免弹窗被这次抬起关掉。
                                    // 手指漂移超过 1.5 倍 touchSlop 视为误触，不弹菜单。
                                    val drift = hypot(
                                        (last.x - start.x).toDouble(),
                                        (last.y - start.y).toDouble(),
                                    ).toFloat()
                                    if (drift <= viewConfiguration.touchSlop * 1.5f) {
                                        longPressCommitState(startCell)
                                    }
                                }
                                if (!isTransform && !longPressed &&
                                    tool != EditorTool.PAN && tool != EditorTool.NONE
                                ) {
                                    val totalMove = hypot(
                                        (start.x - last.x).toDouble(),
                                        (start.y - last.y).toDouble(),
                                    )
                                    if (totalMove < viewConfiguration.touchSlop) tapState(lastCell)
                                }
                                stroke?.let {
                                    engine.endStroke(it)
                                    strokeEndState()
                                }
                                break
                            }
                        }
                    } finally {
                        gestureActive.value = false
                        liveTick.intValue++
                    }
                }
            },
    ) {
        // 只读不用的两个 state：draw 阶段读它们，笔画进行中每帧才会自动重绘
        revisionProvider()
        liveTick.intValue
        val interacting = gestureActive.value
        val canvas = Offset(size.width, size.height)
        val cell = cellPx(canvas, transform, engine)
        if (cell <= 0f) return@Canvas
        val origin = drawOrigin(canvas, transform, engine)

        val firstX = max(0, (-origin.x / cell).toInt())
        val firstY = max(0, (-origin.y / cell).toInt())
        val lastX = min(engine.width - 1, ((size.width - origin.x) / cell).toInt() + 1)
        val lastY = min(engine.height - 1, ((size.height - origin.y) / cell).toInt() + 1)

        val gridW = engine.width
        val cells = engine.cells

        // 空格底色整块铺一次。逐格绘制会让 60×60 的背景产生 3600 次
        // drawRect，帧时间耗在空区域上。
        drawRect(
            color = EmptyCellColor,
            topLeft = Offset(origin.x + firstX * cell, origin.y + firstY * cell),
            size = Size((lastX - firstX + 1) * cell, (lastY - firstY + 1) * cell),
        )

        // 有色格压成「同色矩形」再画：60×60 从 3600 次 drawRect 降到几百次，
        // 滑动上色每帧都要重录显示列表，这一步是帧时间的大头。
        RunRects.forEach(cells, gridW, firstX, lastX, firstY, lastY) { x0, x1, y0, y1, c ->
            drawRect(
                color = cellColors.getOrElse(c) { Color(0xFFCCCCCC) },
                topLeft = Offset(origin.x + x0 * cell, origin.y + y0 * cell),
                size = Size((x1 - x0 + 1) * cell, (y1 - y0 + 1) * cell),
            )
        }

        // 锁定标记单独一遍：合并矩形之后格子边界已经不可见，标记必须按格画。
        // 没有锁定格时整段跳过，滑动上色就完全不碰这一块。
        val locks = engine.locked
        var hasLock = false
        for (y in firstY..lastY) {
            val row = y * gridW
            for (x in firstX..lastX) {
                if (locks[row + x]) {
                    hasLock = true
                    break
                }
            }
            if (hasLock) break
        }
        if (hasLock) {
            for (y in firstY..lastY) {
                val row = y * gridW
                for (x in firstX..lastX) {
                    if (locks[row + x]) {
                        drawLockMark(lockPath, lockColor, lockOutline, cell, origin, x, y)
                    }
                }
            }
        }

        if (refImage != null && refAlpha > 0f) {
            drawImage(
                image = refImage,
                srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                srcSize = androidx.compose.ui.unit.IntSize(refImage.width, refImage.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(origin.x.toInt(), origin.y.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(
                    (engine.width * cell).toInt().coerceAtLeast(1),
                    (engine.height * cell).toInt().coerceAtLeast(1),
                ),
                alpha = refAlpha,
            )
        }

        if (showGrid) {
            val lineColor = Color.Black.copy(alpha = 0.14f)
            for (x in firstX..lastX + 1) {
                val px = origin.x + x * cell
                drawLine(
                    lineColor,
                    Offset(px, origin.y + firstY * cell),
                    Offset(px, origin.y + (lastY + 1) * cell),
                    1f,
                )
            }
            for (y in firstY..lastY + 1) {
                val py = origin.y + y * cell
                drawLine(
                    lineColor,
                    Offset(origin.x + firstX * cell, py),
                    Offset(origin.x + (lastX + 1) * cell, py),
                    1f,
                )
            }
        }

        // 手指按住画布时不画色号：几千个 text layout + drawText 每帧重排会直接掉帧，
        // 抬手后立刻恢复（interacting 只在 draw 阶段读，不会触发整页重组）。
        if (showCodes && !interacting && cell >= CellCodeDrawer.MIN_CELL_PX) {
            for (y in firstY..lastY) {
                for (x in firstX..lastX) {
                    val idx = y * engine.width + x
                    val colorIdx = engine.cells[idx]
                    if (colorIdx < 0) continue
                    val code = paletteCodes.getOrNull(colorIdx) ?: continue
                    val argb = paletteArgb.getOrNull(colorIdx) ?: 0xFFCCCCCC.toInt()
                    drawCellCode(
                        measurer = measurer,
                        code = code,
                        left = origin.x + x * cell,
                        top = origin.y + y * cell,
                        cell = cell,
                        argb = argb,
                        layoutCache = layoutCache,
                    )
                }
            }
        }

        if (tool == EditorTool.PICKER) {
            drawRect(
                color = ClayColors.BrandTeal.copy(alpha = 0.5f),
                topLeft = Offset.Zero,
                size = Size(size.width, 6f),
            )
        }

        // 长按菜单打开时把目标格圈出来，避免对话框挡住后不知道改的是哪一格
        if (highlightCell != null && highlightCell in engine.cells.indices) {
            val hx = highlightCell % engine.width
            val hy = highlightCell / engine.width
            val left = origin.x + hx * cell
            val top = origin.y + hy * cell
            val stroke = (cell * 0.10f).coerceIn(2f, 6f)
            drawRect(
                color = ClayColors.BrandBlue.copy(alpha = 0.95f),
                topLeft = Offset(left - stroke / 2f, top - stroke / 2f),
                size = Size(cell + stroke, cell + stroke),
                style = Stroke(width = stroke),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLockMark(
    path: Path,
    fill: Color,
    outline: Color,
    cell: Float,
    origin: Offset,
    x: Int,
    y: Int,
) {
    val left = origin.x + x * cell
    val top = origin.y + y * cell
    withTransform({
        translate(left + cell, top)
        scale(cell, cell, pivot = Offset.Zero)
    }) {
        drawPath(path, fill)
        drawPath(path, outline, style = Stroke(width = 0.10f))
    }
}
