package com.pindou.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Redo
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.pindou.app.core.editor.EditorEngine
import com.pindou.app.ui.CellCodeDrawer.drawCellCode
import com.pindou.app.ui.AppViewModel
import com.pindou.app.ui.EditorTool
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
import com.pindou.app.ui.shadcn.ShChip
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 画布变换：offset 为唯一平移量；origin = baseOrigin(居中) + offset；缩放围绕视口点进行 */
private class CanvasTransform {
    var scale = 1f
    var offset = Offset.Zero

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel) {
    val engine = vm.editor ?: return
    val palette = vm.selectedPalette ?: return

    var showPaletteSheet by remember { mutableStateOf(false) }
    var showManageSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }
    var longPressCell by remember { mutableStateOf<Int?>(null) }
    val transform = remember { CanvasTransform() }
    // 页切换后重置画布视图，避免残留上一页的缩放位置错乱
    androidx.compose.runtime.LaunchedEffect(engine.pageIndex, engine.width, engine.height) {
        transform.reset()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${engine.width}×${engine.height} · ${palette.brandLabel}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = Spacing.sm),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.undoEditor() }, enabled = engine.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销")
                }
                IconButton(onClick = { vm.redoEditor() }, enabled = engine.canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "重做")
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
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

        // 画布
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Spacing.xs)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(12.dp),
                ),
        ) {
            EditorCanvas(
                engine = engine,
                paletteCodes = palette.colors.map { it.code },
                paletteArgb = palette.colors.map { it.argb },
                transform = transform,
                showCodes = vm.editorShowCodes,
                showGrid = vm.editorShowGrid,
                revision = vm.editorRevision,
                tool = vm.editorTool,
                refImage = vm.refBitmap?.takeIf { vm.showRefOverlay }?.asImageBitmap(),
                refAlpha = vm.refOpacity,
                onTap = { cell -> handleTap(vm, engine, cell) },
                onStrokeCell = { cell, session -> handleStrokeCell(vm, engine, cell, session) },
                onLongPress = { cell -> longPressCell = cell },
                onStrokeEnd = { vm.notifyEditorChanged() },
            )
        }

        // 页切换行（多页图纸）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "第 ${engine.pageIndex + 1}/${engine.pageCount} 页",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 箭头改图标按钮：纯文字 "‹" 在 360dp 屏上窄得像标点，点不准
                IconButton(
                    onClick = { vm.switchEditorPage(engine.pageIndex - 1) },
                    enabled = engine.pageIndex > 0,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一页",
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = { vm.switchEditorPage(engine.pageIndex + 1) },
                    enabled = engine.pageIndex < engine.pageCount - 1,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一页",
                        modifier = Modifier.size(20.dp),
                    )
                }
                TextButton(
                    onClick = { vm.addEditorPage(false) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("+ 新页", style = MaterialTheme.typography.labelMedium) }
                TextButton(
                    onClick = { vm.addEditorPage(true) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("复制", style = MaterialTheme.typography.labelMedium) }
                TextButton(
                    onClick = { vm.removeEditorPage(engine.pageIndex) },
                    enabled = engine.pageCount > 1,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("删除", style = MaterialTheme.typography.labelMedium) }
            }
        }
        // 工具行：色板入口 + 四个工具；显示开关单独一行，避免 360dp 宽度下被裁掉
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        Color(palette.colors[vm.editorColor].argb),
                        RoundedCornerShape(10.dp),
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .clickable { showPaletteSheet = true },
            )
            EditorToolButton(Icons.Filled.Brush, "画笔", vm.editorTool == EditorTool.PAINT) {
                vm.editorTool = EditorTool.PAINT
            }
            EditorToolButton(Icons.Filled.FormatPaint, "油漆桶", vm.editorTool == EditorTool.FILL) {
                vm.editorTool = EditorTool.FILL
            }
            EditorToolButton(Icons.Filled.FormatColorReset, "橡皮", vm.editorTool == EditorTool.ERASE) {
                vm.editorTool = EditorTool.ERASE
            }
            EditorToolButton(Icons.Filled.Colorize, "吸管", vm.editorTool == EditorTool.PICKER) {
                vm.editorTool = EditorTool.PICKER
            }
            Text(
                "点格子改色 · 长按取色",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShChip(
                text = "色号",
                selected = vm.editorShowCodes,
                onClick = { vm.editorShowCodes = !vm.editorShowCodes },
            )
            ShChip(
                text = "网格",
                selected = vm.editorShowGrid,
                onClick = { vm.editorShowGrid = !vm.editorShowGrid },
            )
        }
    }

    // 色卡选择
    if (showPaletteSheet) {
        ModalBottomSheet(onDismissRequest = { showPaletteSheet = false }) {
            Column(Modifier.padding(bottom = Spacing.xl)) {
                Text(
                    "选择颜色 · ${palette.brandLabel}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
                LazyVerticalGrid(columns = GridCells.Fixed(4)) {
                    items(palette.colors.size) { i ->
                        val entry = palette.colors[i]
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    vm.editorColor = i
                                    vm.editorTool = EditorTool.PAINT
                                    showPaletteSheet = false
                                }
                                .padding(Spacing.xs),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(Color(entry.argb), RoundedCornerShape(12.dp))
                                    .border(
                                        width = if (vm.editorColor == i) 3.dp else 1.dp,
                                        color = if (vm.editorColor == i) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.Black.copy(alpha = 0.15f)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                    ),
                            )
                            Text(
                                entry.code,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // ⋮ 菜单
    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text("更多操作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (vm.refBitmap != null) "参考图对比" else "参考图对比（无原图）",
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = vm.showRefOverlay,
                            onCheckedChange = { vm.toggleRefOverlay() },
                            enabled = vm.refBitmap != null,
                        )
                    }
                    if (vm.showRefOverlay && vm.refBitmap != null) {
                        Text("参考图透明度 ${(vm.refOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = vm.refOpacity,
                            onValueChange = { vm.updateRefOpacity(it) },
                            valueRange = 0.05f..1f,
                        )
                    }
                    TextButton(
                        onClick = {
                            showMenu = false
                            showManageSheet = true
                        },
                    ) { Text("色板管理（换品牌 / 合并相近色）") }
                    TextButton(
                        onClick = {
                            showMenu = false
                            showResizeDialog = true
                        },
                    ) { Text("改尺寸") }
                    TextButton(
                        onClick = {
                            showMenu = false
                            vm.exportBpatFile()
                        },
                    ) { Text("导出 .bpat 图纸文件") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMenu = false }) { Text("关闭") }
            },
        )
    }

    // 改尺寸对话框
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
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.sm)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                                        MaterialTheme.colorScheme.primary
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

    // 长按格子菜单
    longPressCell?.let { cell ->
        val colorEntry = if (engine.cells[cell] >= 0) palette.colors[engine.cells[cell]] else null
        AlertDialog(
            onDismissRequest = { longPressCell = null },
            title = { Text("格子 (${cell % engine.width + 1}, ${cell / engine.width + 1})") },
            text = {
                Text(
                    if (engine.cells[cell] >= 0) {
                        "色号 ${colorEntry?.code}${colorEntry?.name?.let { n -> " · $n" } ?: ""}"
                    } else {
                        "空格"
                    } + if (engine.locked[cell]) "（已锁定）" else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    engine.setLocked(cell, !engine.locked[cell])
                    vm.notifyEditorChanged()
                    longPressCell = null
                }) {
                    Icon(
                        if (engine.locked[cell]) Icons.Filled.LockOpen else Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(if (engine.locked[cell]) " 解锁" else " 锁定")
                }
            },
            dismissButton = {
                TextButton(onClick = { longPressCell = null }) { Text("关闭") }
            },
        )
    }
}

private fun handleTap(vm: AppViewModel, engine: EditorEngine, cell: Int) {
    if (cell < 0) return
    when (vm.editorTool) {
        EditorTool.PICKER -> {
            val c = engine.cells.getOrNull(cell) ?: return
            if (c >= 0) {
                vm.editorColor = c
                vm.editorTool = EditorTool.PAINT
            }
        }
        EditorTool.FILL -> {
            // 油漆桶：整块同色区域填充为当前色（含空格区域）
            if (engine.floodFill(cell, vm.editorColor)) vm.notifyEditorChanged()
        }
        EditorTool.PAINT -> {
            val s = engine.startStroke()
            s.setCell(cell, vm.editorColor)
            engine.endStroke(s)
            vm.notifyEditorChanged()
        }
        EditorTool.ERASE -> {
            val s = engine.startStroke()
            s.setCell(cell, -1)
            engine.endStroke(s)
            vm.notifyEditorChanged()
        }
    }
}

private fun handleStrokeCell(
    vm: AppViewModel,
    engine: EditorEngine,
    cell: Int,
    session: EditorEngine.StrokeSession,
) {
    if (cell < 0) return
    when (vm.editorTool) {
        EditorTool.PAINT -> session.setCell(cell, vm.editorColor)
        EditorTool.ERASE -> session.setCell(cell, -1)
        // 油漆桶按点击触发，拖动不连续填充
        EditorTool.FILL, EditorTool.PICKER -> {}
    }
}

@Composable
private fun EditorToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 选中态用主色实底方块（shadcn toggle 语义），未选中走浅灰底 + 发丝描边，
    // 否则四个工具里只有一个有边界，其它三个看着像悬浮图标。40dp 触控区。
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                1.dp,
                if (selected) Color.Transparent else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
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
    revision: Int,
    tool: EditorTool,
    refImage: ImageBitmap?,
    refAlpha: Float,
    onTap: (Int) -> Unit,
    onStrokeCell: (Int, EditorEngine.StrokeSession) -> Unit,
    onLongPress: (Int) -> Unit,
    onStrokeEnd: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    // 色号布局缓存：按 (色号,整数字号) 复用，缩放时避免每帧重复测量
    val layoutCache = remember { mutableMapOf<Pair<String, Int>, androidx.compose.ui.text.TextLayoutResult>() }
    // 锁定标记三角形路径缓存（避免每格每帧 new Path()）
    val lockPath = remember {
        Path().apply {
            moveTo(0f, 0f); lineTo(0f, 0.3f); lineTo(-0.3f, 0f); close()
        }
    }
    val lockColor = Color(0x8C000000) // 55% alpha, 避免每格 copy(alpha=...)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(tool, engine) {
                awaitEachGesture {
                    val canvas = Offset(size.width.toFloat(), size.height.toFloat())
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var last = start
                    var lastCell = cellAt(start, canvas, transform, engine)
                    var moved = false
                    var longPressed = false
                    var isTransform = false
                    var stroke: EditorEngine.StrokeSession? = null
                    var prevDist = 0f
                    var prevCentroid = Offset.Zero

                    // 长按竞争：down 后一段时间内无任何指针事件 → 长按。
                    // 注意 pending 只消费一次，否则会无限重放同一事件卡死主线程
                    var pending = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        awaitPointerEvent()
                    }
                    if (pending == null) {
                        longPressed = true
                        onLongPress(cellAt(last, canvas, transform, engine))
                    }

                    while (true) {
                        val event = pending ?: awaitPointerEvent()
                        pending = null
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.size >= 2) {
                            if (!isTransform) {
                                isTransform = true
                                stroke?.let {
                                    engine.endStroke(it)
                                    onStrokeEnd()
                                }
                                stroke = null
                            }
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                            val dist = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
                            if (prevDist > 0f) {
                                val oldScale = transform.scale
                                val newScale = (oldScale * (dist / prevDist)).coerceIn(1f, 20f)
                                val origin = drawOrigin(canvas, transform, engine)
                                val k = newScale / oldScale
                                val newOrigin = Offset(
                                    centroid.x - (centroid.x - origin.x) * k,
                                    centroid.y - (centroid.y - origin.y) * k,
                                )
                                transform.scale = newScale
                                transform.offset = newOrigin - baseOrigin(canvas, engine)
                            }
                            if (prevCentroid != Offset.Zero) {
                                transform.offset += centroid - prevCentroid
                            }
                            prevDist = dist
                            prevCentroid = centroid
                        } else if (pressed.size == 1) {
                            val c = pressed[0]
                            val pos = c.position
                            val delta = hypot(
                                (pos.x - start.x).toDouble(),
                                (pos.y - start.y).toDouble(),
                            ).toFloat()
                            if (!moved && delta > viewConfiguration.touchSlop) {
                                moved = true
                                if (!longPressed && tool != EditorTool.PICKER && tool != EditorTool.FILL) {
                                    stroke = engine.startStroke()
                                }
                            }
                            if (!isTransform && moved && !longPressed && tool != EditorTool.PICKER && tool != EditorTool.FILL) {
                                stroke = stroke ?: engine.startStroke()
                                // 路径插值：快速划动时补格
                                val stepPx = max(2f, cellPx(canvas, transform, engine) / 2f)
                                val d = hypot(
                                    (pos.x - last.x).toDouble(),
                                    (pos.y - last.y).toDouble(),
                                ).toFloat()
                                val steps = max(1, (d / stepPx).toInt())
                                for (i in 1..steps) {
                                    val t = i.toFloat() / steps
                                    val p = Offset(
                                        last.x + (pos.x - last.x) * t,
                                        last.y + (pos.y - last.y) * t,
                                    )
                                    onStrokeCell(cellAt(p, canvas, transform, engine), stroke!!)
                                }
                            }
                            if (!moved) lastCell = cellAt(pos, canvas, transform, engine)
                            last = pos
                        }

                        event.changes.forEach { change ->
                            if (change.positionChanged()) change.consume()
                        }

                        if (pressed.isEmpty()) {
                            if (!isTransform && !longPressed) {
                                val totalMove = hypot(
                                    (start.x - last.x).toDouble(),
                                    (start.y - last.y).toDouble(),
                                )
                                if (totalMove < viewConfiguration.touchSlop) {
                                    onTap(lastCell)
                                }
                            }
                            stroke?.let {
                                engine.endStroke(it)
                                onStrokeEnd()
                            }
                            break
                        }
                    }
                }
            },
    ) {
        // 订阅引擎 revision：撤销/重做/页操作等不经 CanvasTransform 的变更也触发重绘
        revision
        val canvas = Offset(size.width, size.height)
        val cell = cellPx(canvas, transform, engine)
        val origin = drawOrigin(canvas, transform, engine)

        val firstX = max(0, (-origin.x / cell).toInt())
        val firstY = max(0, (-origin.y / cell).toInt())
        val lastX = min(engine.width - 1, ((size.width - origin.x) / cell).toInt() + 1)
        val lastY = min(engine.height - 1, ((size.height - origin.y) / cell).toInt() + 1)

        for (y in firstY..lastY) {
            for (x in firstX..lastX) {
                val idx = y * engine.width + x
                val left = origin.x + x * cell
                val top = origin.y + y * cell
                val colorIdx = engine.cells[idx]
                if (colorIdx >= 0) {
                    drawRect(
                        color = Color(paletteArgb[colorIdx]),
                        topLeft = Offset(left, top),
                        size = Size(cell, cell),
                    )
                } else {
                    drawRect(
                        color = if ((x + y) % 2 == 0) Color(0xFFEFEFEA) else Color(0xFFE2E2DC),
                        topLeft = Offset(left, top),
                        size = Size(cell, cell),
                    )
                }
                if (engine.locked[idx]) {
                    // 复用同一 Path（单位尺寸），通过 canvas 变换平移到格子角
                    withTransform({
                        translate(left + cell, top)
                        scale(cell, cell, pivot = Offset.Zero)
                    }) {
                        drawPath(lockPath, lockColor)
                    }
                }
            }
        }

        // 参考图叠加：铺在格子上方、色号下方，透明度可调
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
            val lineColor = Color.Black.copy(alpha = 0.15f)
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

        if (showCodes) {
            for (y in firstY..lastY) {
                for (x in firstX..lastX) {
                    val idx = y * engine.width + x
                    val colorIdx = engine.cells[idx]
                    if (colorIdx < 0) continue
                    drawCellCode(
                        measurer = measurer,
                        code = paletteCodes[colorIdx],
                        left = origin.x + x * cell,
                        top = origin.y + y * cell,
                        cell = cell,
                        argb = paletteArgb[colorIdx],
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
    }
}
