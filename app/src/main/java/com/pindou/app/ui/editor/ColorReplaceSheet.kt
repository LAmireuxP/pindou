package com.pindou.app.ui.editor

import com.pindou.app.ui.icon.MorphIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pindou.app.core.editor.ColorReplaceOps
import com.pindou.app.core.editor.EditorEngine
import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.ui.AppViewModel
import com.pindou.app.ui.CellCodeDrawer
import com.pindou.app.ui.shadcn.ShChip
import com.pindou.app.ui.shadcn.ShSearchField
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 替换相似颜色 / 全图合并相近色。
 *
 * 两个动作共用一个色差滑杆：
 * - 指定目标色：源色 + ΔE ≤ 阈值的色一起变成目标色（阈值 0 = 只替换这一个色号）
 * - 不指定目标色：自动把相近色并到用量最多的色，并返回逐条明细
 * 锁定格全程跳过，明细里会写清楚跳过了多少格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorReplaceSheet(
    vm: AppViewModel,
    palette: BeadPalette,
    engine: EditorEngine,
    initialSource: Int,
    onDismiss: () -> Unit,
    onReport: (List<ColorReplaceOps.Move>) -> Unit,
) {
    val revision = vm.editorRevision
    val colorCount = palette.colors.size
    val safeSource = initialSource.coerceIn(0, colorCount - 1)

    // 用量统计跟着画布修订号走：改完颜色再打开就是新数据
    val usage = remember(revision, colorCount) {
        ColorReplaceOps.usage(engine.pages, colorCount)
    }
    val usedIndices = remember(revision, colorCount) {
        usage.indices.filter { usage[it] > 0 }.sortedByDescending { usage[it] }
    }

    var source by remember(safeSource) { mutableIntStateOf(safeSource) }
    var target by remember(safeSource, revision) {
        mutableIntStateOf(
            usedIndices.firstOrNull { it != safeSource } ?: safeSource,
        )
    }
    // 默认 5：MARD 色卡里同色系相邻色的 ΔE 多在 3~6，3 太小会让人以为功能没反应
    var threshold by remember { mutableFloatStateOf(5f) }
    var picking by remember { mutableIntStateOf(0) } // 0 关闭 / 1 选源色 / 2 选目标色
    var showAllColors by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var mergeHint by remember { mutableStateOf("") }

    val preview = remember(revision, source, target, threshold) {
        ColorReplaceOps.replaceSimilar(
            engine.pages, engine.locks, palette, source, target, threshold.toDouble(),
        )
    }
    val targetEntry = palette.colors.getOrNull(target)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text("替换相似颜色", style = MaterialTheme.typography.titleLarge)
            Text(
                "把同一色系里肉眼分不清的色号并成一个，减少换豆次数",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 源色 → 目标色
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ColorRoleCard(
                    label = "源色",
                    entry = palette.colors[source],
                    cells = usage[source],
                    modifier = Modifier.weight(1f),
                ) { picking = if (picking == 1) 0 else 1 }
                Text("→", style = MaterialTheme.typography.titleLarge)
                ColorRoleCard(
                    label = "目标色",
                    entry = targetEntry,
                    cells = usage.getOrElse(target) { 0 },
                    modifier = Modifier.weight(1f),
                ) { picking = if (picking == 2) 0 else 2 }
            }

            if (picking != 0) {
                val pickingTarget = picking == 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (pickingTarget) "选择目标色" else "选择源色",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    ShChip(
                        text = if (showAllColors) "只看已用" else "全部 $colorCount 色",
                        selected = showAllColors,
                        onClick = { showAllColors = !showAllColors },
                    )
                }
                ShSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索色号或颜色名",
                    modifier = Modifier.fillMaxWidth(),
                )
                val candidates = remember(showAllColors, query, revision, colorCount) {
                    val base = if (showAllColors || query.isNotBlank()) {
                        palette.colors.indices.toList()
                    } else {
                        usedIndices
                    }
                    if (query.isBlank()) base else base.filter { i ->
                        val e = palette.colors[i]
                        e.code.contains(query, ignoreCase = true) ||
                            e.name?.contains(query, ignoreCase = true) == true
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(candidates.size) { pos ->
                        val index = candidates[pos]
                        val entry = palette.colors[index]
                        val selected = index == if (pickingTarget) target else source
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (pickingTarget) target = index else source = index
                                    picking = 0
                                    query = ""
                                }
                                .padding(vertical = 2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(entry.argb), RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.Black.copy(alpha = 0.14f)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        MorphIcons.Check,
                                        contentDescription = null,
                                        tint = if (CellCodeDrawer.isLight(entry.argb)) {
                                            Color(0xFF18181B)
                                        } else {
                                            Color.White
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Text(
                                entry.code,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }

            // ΔE 滑杆
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("相似范围", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (threshold <= 0.05f) "仅同色号" else "ΔE ≤ %.1f".format(threshold),
                    style = MaterialTheme.typography.labelMedium,
                    color = ClayColors.PrimaryInk,
                )
            }
            Slider(
                value = threshold,
                onValueChange = {
                    threshold = it
                    mergeHint = ""
                },
                valueRange = 0f..12f,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "0 只换同色号",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "12 激进合并",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 预览
            val targetCode = targetEntry?.code ?: "?"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("将改动", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${preview.moves.size} 色 · ${preview.movedCells} 格 → $targetCode",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (preview.changed) {
                            ClayColors.PrimaryInk
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (preview.moves.isEmpty()) {
                    Text(
                        if (usage[source] == 0) {
                            "这个色号在当前图纸里没有格子，换一个源色"
                        } else {
                            "当前范围内没有可合并的颜色，把滑杆往右拉"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    preview.moves.take(8).forEach { move ->
                        val from = palette.colors.getOrNull(move.from)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Swatch(argb = from?.argb ?: 0, size = 14.dp)
                            Text(
                                "${from?.code ?: "?"} → $targetCode",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "%d 格 · ΔE %.1f".format(move.cells, move.deltaE),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (preview.moves.size > 8) {
                        Text(
                            "还有 ${preview.moves.size - 8} 个色号…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (preview.lockedSkipped > 0) {
                        Text(
                            "锁定格 ${preview.lockedSkipped} 个不会改动",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val moved = vm.replaceSimilarEditorColors(
                        from = source,
                        to = target,
                        maxDeltaE = threshold.toDouble(),
                    )
                    if (moved.isNotEmpty()) {
                        onReport(moved)
                        onDismiss()
                    }
                },
                enabled = preview.changed && target != source,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            ) {
                Text(
                    if (preview.changed) {
                        "替换 ${preview.moves.size} 色 · ${preview.movedCells} 格 → $targetCode"
                    } else {
                        "没有可替换的格子"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            OutlinedButton(
                onClick = {
                    val report = vm.mergeSimilarEditorColorsWithReport(threshold.toDouble())
                    if (report.isNotEmpty()) {
                        onReport(report)
                        onDismiss()
                    } else {
                        // 没有可合并项时给出提示，避免点了没反应
                        mergeHint = if (threshold <= 0.05f) {
                            "ΔE 为 0 只会合并同值与异品牌同色号；把滑杆往右拉再试"
                        } else {
                            "ΔE ≤ %.1f 下没有可合并的颜色，把滑杆往右拉".format(threshold)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(
                    "全图合并相近色 · ΔE ≤ %.1f".format(threshold),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (mergeHint.isNotEmpty()) {
                Text(
                    mergeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "合并时保留用量最多的色号；相同时保留色卡里靠前的色号",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColorRoleCard(
    label: String,
    entry: BeadPalette.Entry?,
    cells: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Swatch(argb = entry?.argb ?: 0, size = 34.dp)
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                entry?.code ?: "空",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$cells 格",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Swatch(argb: Int, size: Dp) {
    val fill = if (argb == 0) Color(0xFFE4E4E7) else Color(argb)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .border(1.dp, Color.Black.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
    )
}

/**
 * 替换 / 合并完成后的结果条。
 *
 * 贴在画布顶部，只占一行高度，点画布照常上色；6 秒后自动消失，
 * 撤销按钮留在条上，不打断当前操作。
 */
@Composable
fun ColorOpReportBar(
    moves: List<ColorReplaceOps.Move>,
    palette: BeadPalette,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalCells = moves.sumOf { it.cells }
    val shown = moves.take(3)
    LaunchedEffect(moves) {
        delay(6000)
        onDismiss()
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                MorphIcons.CircleCheck,
                contentDescription = null,
                tint = ClayColors.PrimaryInk,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "已改 ${totalCells} 格 · ${moves.size} 组",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    shown.forEach { move ->
                        val from = palette.colors.getOrNull(move.from)
                        val to = palette.colors.getOrNull(move.to)
                        Swatch(argb = from?.argb ?: 0, size = 9.dp)
                        Text(
                            "→",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Swatch(argb = to?.argb ?: 0, size = 9.dp)
                        Text(
                            to?.code ?: "?",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (moves.size > shown.size) {
                        Text(
                            "+${moves.size - shown.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(
                onClick = onUndo,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text(
                    "撤销",
                    style = MaterialTheme.typography.labelLarge,
                    color = ClayColors.PrimaryInk,
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MorphIcons.X,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

