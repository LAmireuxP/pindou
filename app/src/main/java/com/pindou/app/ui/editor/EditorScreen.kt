package com.pindou.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pindou.app.core.editor.EditorEngine
import com.pindou.app.ui.CellCodeDrawer.drawCellCode
import com.pindou.app.ui.AppViewModel
import com.pindou.app.ui.EditorTool
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
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
    var longPressCell by remember { mutableStateOf<Int?>(null) }
    val transform = remember { CanvasTransform() }

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
                    Icon(Icons.Filled.Redo, contentDescription = "重做")
                }
                TextButton(onClick = { vm.finishEditor() }) {
                    Text("完成")
                }
            }
        }

        // 画布
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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
                onTap = { cell -> handleTap(vm, engine, cell) },
                onStrokeCell = { cell, session -> handleStrokeCell(vm, engine, cell, session) },
                onLongPress = { cell -> longPressCell = cell },
                onStrokeEnd = { vm.notifyEditorChanged() },
            )
        }

        // 工具行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
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
                        .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .clickable { showPaletteSheet = true },
                )
                EditorToolButton(Icons.Filled.Brush, "画笔", vm.editorTool == EditorTool.PAINT) {
                    vm.editorTool = EditorTool.PAINT
                }
                EditorToolButton(Icons.Filled.FormatColorReset, "橡皮", vm.editorTool == EditorTool.ERASE) {
                    vm.editorTool = EditorTool.ERASE
                }
                EditorToolButton(Icons.Filled.Colorize, "吸管", vm.editorTool == EditorTool.PICKER) {
                    vm.editorTool = EditorTool.PICKER
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("色号", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = vm.editorShowCodes,
                    onCheckedChange = { vm.editorShowCodes = it },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Text("网格", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = vm.editorShowGrid,
                    onCheckedChange = { vm.editorShowGrid = it },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
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
        EditorTool.PICKER -> {}
    }
}

@Composable
private fun EditorToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
    onTap: (Int) -> Unit,
    onStrokeCell: (Int, EditorEngine.StrokeSession) -> Unit,
    onLongPress: (Int) -> Unit,
    onStrokeEnd: () -> Unit,
) {
    val measurer = rememberTextMeasurer()

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
                                if (!longPressed && tool != EditorTool.PICKER) {
                                    stroke = engine.startStroke()
                                }
                            }
                            if (!isTransform && moved && !longPressed && tool != EditorTool.PICKER) {
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
                    val p = Path().apply {
                        moveTo(left + cell, top)
                        lineTo(left + cell, top + cell * 0.3f)
                        lineTo(left + cell * 0.7f, top)
                        close()
                    }
                    drawPath(p, Color.Black.copy(alpha = 0.55f))
                }
            }
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
