package com.pindou.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.pattern.GenerationStage
import com.pindou.app.ui.shadcn.Button
import com.pindou.app.ui.shadcn.OutlinedButton
import com.pindou.app.ui.shadcn.ShCard
import com.pindou.app.ui.shadcn.ShChip
import com.pindou.app.ui.shadcn.ShPageHeader
import com.pindou.app.ui.shadcn.TextButton
import com.pindou.app.ui.theme.Spacing

// 与颂拼豆「原图尺寸」对齐：只选宽度，高度按图片比例推导
private val GridPresets = listOf("36", "52", "78", "104", "156", "200", "自适应", "自定义")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ParamsScreen(vm: AppViewModel) {
    val bitmap = vm.imagePreview
    val palette = vm.selectedPalette
    var preset by remember { mutableStateOf(if (vm.gridW.toString() in GridPresets) vm.gridW.toString() else "自定义") }
    var paletteMenuOpen by remember { mutableStateOf(false) }
    var showTextEditor by remember { mutableStateOf(false) }
    // 色卡筛选：全部 / 全色系套装 / 5mm 品牌 / 2.6mm 品牌
    var paletteFilter by remember { mutableStateOf("全部") }
    val allPalettes = vm.palettes
    val availablePalettes = remember(allPalettes, paletteFilter) {
        when (paletteFilter) {
            "全色系" -> allPalettes.filter { it.brand.startsWith("ALL") }
            "5mm 普通豆" -> allPalettes.filter { it.beadSize.name == "MM5" }
            "2.6mm 小豆" -> allPalettes.filter { it.beadSize.name == "MM2_6" && !it.brand.startsWith("ALL") }
            else -> allPalettes
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ShPageHeader(
            title = "参数设置",
            subtitle = "网格决定颗粒度，色卡决定能买到的豆子",
            modifier = Modifier.padding(top = Spacing.lg),
        )

        bitmap?.let {
            ShCard(modifier = Modifier.fillMaxWidth()) {
                Box {
                    // 文字位图比例极端（如 16×29），固定高度会把字裁掉；文字模式用 Fit + 自适应高度
                    val isTxt = vm.textSource != null
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "导入的图片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isTxt) Modifier.heightIn(max = 280.dp)
                                else Modifier.height(180.dp)
                            ),
                        contentScale = if (isTxt) ContentScale.Fit else ContentScale.Crop,
                    )
                    ShChip(
                        text = if (vm.textSource != null) "改文字" else "重新裁剪",
                        selected = false,
                        onClick = {
                            if (vm.textSource != null) showTextEditor = true else vm.openCrop()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(Spacing.xs),
                    )
                }
            }
        }

        // 文字拼豆改文字对话框
        if (showTextEditor) {
            var editText by remember { mutableStateOf(vm.textSource ?: "") }
            AlertDialog(
                onDismissRequest = { showTextEditor = false },
                title = { Text("改文字") },
                text = {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        label = { Text("输入文字（支持多行）") },
                        minLines = 2,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (editText.isNotBlank()) {
                                vm.regenerateFromText(editText)
                                showTextEditor = false
                            }
                        },
                        enabled = editText.isNotBlank(),
                    ) { Text("重新生成") }
                },
                dismissButton = { TextButton(onClick = { showTextEditor = false }) { Text("取消") } },
            )
        }

        // 图纸名称（留空则保存时回退默认名）
        OutlinedTextField(
            value = vm.projectName.takeIf { it != "未命名图纸" } ?: "",
            onValueChange = { vm.projectName = it },
            label = { Text("图纸名称") },
            placeholder = { Text("未命名图纸") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        // 网格尺寸
        ShCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("网格尺寸", style = MaterialTheme.typography.titleLarge)
                // 像素画原图：把「原画网格」放在最前面，一键就是 56×50。
                // 用户不需要理解块长检测，只需要知道"这张图原本就是 56×50 个画像素"。
                vm.pixelArtGrid?.let { (aw, ah) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "检测到像素画原图 · 原画 ${aw}×${ah}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "先还原画像素再落格，颜色不会被白底稀释",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ShChip(
                            text = "套用原画",
                            selected = vm.gridW == aw && vm.gridH == ah,
                            onClick = {
                                preset = "原画"
                                vm.applyArtGrid()
                            },
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    GridPresets.forEach { p ->
                        ShChip(
                            text = p,
                            selected = preset == p,
                            onClick = {
                                preset = p
                                when (p) {
                                    "36" -> vm.setGridWidthPreset(36)
                                    "52" -> vm.setGridWidthPreset(52)
                                    "78" -> vm.setGridWidthPreset(78)
                                    "104" -> vm.setGridWidthPreset(104)
                                    "156" -> vm.setGridWidthPreset(156)
                                    "200" -> vm.setGridWidthPreset(200)
                                    "自适应" -> vm.applyAdaptiveSize()
                                }
                            },
                        )
                    }
                }
                // 自适应激活时提供放大倍数（基于自适应基础尺寸整体缩放）
                if (preset == "自适应" && vm.imageW > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Text("放大倍数", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = vm.adaptiveScale,
                            onValueChange = { vm.updateAdaptiveScale(it) },
                            valueRange = 0.5f..4f,
                            steps = 13,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${vm.gridW}×${vm.gridH}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                if (preset == "自定义") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        OutlinedTextField(
                            value = vm.gridW.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { vm.setGridWidthInput(it) } },
                            label = { Text("宽") },
                            modifier = Modifier.widthIn(min = 100.dp),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = vm.gridH.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { vm.setGridHeightInput(it) } },
                            label = { Text("高") },
                            modifier = Modifier.widthIn(min = 100.dp),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            "保持宽高比",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = vm.lockGridAspect,
                            onCheckedChange = { vm.toggleGridAspectLock() },
                        )
                    }
                    Text(
                        if (vm.lockGridAspect) {
                            "改一边自动推另一边（比例 %.3f），图案不会被拉扁".format(
                                if (vm.imageW > 0 && vm.imageH > 0) {
                                    (vm.pixelArtGrid?.let { it.first.toDouble() / it.second }
                                        ?: (vm.imageW.toDouble() / vm.imageH))
                                } else 1.0,
                            )
                        } else {
                            "已解锁：宽高独立输入会按不同比例拉伸图案"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "共 ${vm.gridW * vm.gridH} 粒豆子",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 图片比例与网格比例差异大时提示拉伸（降采样是宽高独立映射）
                if (vm.imageW > 0 && vm.imageH > 0) {
                    val imgAspect = vm.imageW.toDouble() / vm.imageH
                    val gridAspect = vm.gridW.toDouble() / vm.gridH
                    val skew = if (imgAspect > gridAspect) imgAspect / gridAspect else gridAspect / imgAspect
                    if (skew > 1.15) {
                        Text(
                            "图片比例 ${vm.imageW}:${vm.imageH} 与网格 ${vm.gridW}:${vm.gridH} 不一致，图案会拉伸变形——点「自适应」按图片比例设定",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // 豆子规格 + 色卡
        ShCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("豆子与色卡", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${allPalettes.size} 套",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    listOf("全部", "全色系", "5mm 普通豆", "2.6mm 小豆").forEach { f ->
                        ShChip(text = f, selected = paletteFilter == f, onClick = { paletteFilter = f })
                    }
                }
                if (palette != null) {
                    // 不用 ExposedDropdownMenuBox：它把点击交给 TextField 风格的锚点，
                    // 自定义 Row 当锚点时点击会被吞掉，这里用普通 DropdownMenu 自己控开关。
                    Box {
                        // 触发器直接展示当前色卡的实际颜色，比一行纯文字更容易分辨
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                .clickable { paletteMenuOpen = true }
                                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(palette.brandLabel, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${palette.colors.size} 色 · ${if (palette.beadSize.name == "MM5") "5mm 普通豆" else "2.6mm 小豆"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            PaletteSwatches(palette)
                            Text("▾", style = MaterialTheme.typography.titleMedium)
                        }
                        DropdownMenu(
                            expanded = paletteMenuOpen,
                            onDismissRequest = { paletteMenuOpen = false },
                            modifier = Modifier.heightIn(max = 520.dp),
                        ) {
                            // 分组：全色系套装单独一段，其余按豆子规格分
                            val groups = listOf(
                                "全色系 · 2.6mm 融合豆" to availablePalettes.filter { it.brand.startsWith("ALL") },
                                "5mm 普通豆品牌色卡" to availablePalettes.filter {
                                    it.beadSize.name == "MM5" && !it.brand.startsWith("ALL")
                                },
                                "2.6mm 小豆品牌色卡" to availablePalettes.filter {
                                    it.beadSize.name == "MM2_6" && !it.brand.startsWith("ALL")
                                },
                            ).filter { it.second.isNotEmpty() }
                            groups.forEach { (title, list) ->
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = Spacing.md, top = Spacing.sm, bottom = 2.dp),
                                )
                                list.forEach { p ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                            ) {
                                                Text(
                                                    // 全色系的品牌名里已经带「24 色」，再拼一次会变成「24 色（24 色）」
                                                    if (p.brandLabel.contains("色")) p.brandLabel
                                                    else "${p.brandLabel}（${p.colors.size} 色）",
                                                    modifier = Modifier.weight(1f),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                PaletteSwatches(p)
                                            }
                                        },
                                        onClick = {
                                            vm.selectPalette(p)
                                            paletteMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 过滤等级 + 进阶选项
        ShCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("过滤等级", style = MaterialTheme.typography.titleLarge)
                Text(
                    "等级越高杂色越少。降噪只作用于格子，眼睛、嘴、高光等高对比细节会锁定保留",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    com.pindou.app.core.pattern.FilterLevel.entries.forEach { level ->
                        ShChip(
                            text = level.label,
                            selected = vm.filterLevel == level,
                            onClick = { vm.filterLevel = level },
                        )
                    }
                }
                Text(
                    "进阶选项",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                SwitchRow(
                    title = "限制最大颜色数",
                    subtitle = null,
                    checked = vm.limitColors,
                    onCheckedChange = { vm.limitColors = it },
                )
                if (vm.limitColors) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Slider(
                            value = vm.maxColors.toFloat(),
                            onValueChange = { vm.maxColors = it.toInt() },
                            valueRange = 4f..48f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${vm.maxColors} 色", style = MaterialTheme.typography.labelLarge)
                    }
                }
                SwitchRow(
                    title = "移除背景",
                    subtitle = "只去掉主体外的背景，主体内的同色区域保留（白底手绘图优先）",
                    checked = vm.removeBackground,
                    onCheckedChange = { vm.removeBackground = it },
                )
                if (vm.removeBackground) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Slider(
                            value = vm.backgroundTolerance,
                            onValueChange = { vm.backgroundTolerance = it },
                            valueRange = 4f..30f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("容差 ${vm.backgroundTolerance.toInt()}", style = MaterialTheme.typography.labelLarge)
                    }
                }
                SwitchRow(
                    title = "Floyd–Steinberg 抖动",
                    subtitle = "进阶选项，默认关闭（抖动图更难拼）",
                    checked = vm.dithering,
                    onCheckedChange = { vm.dithering = it },
                )
            }
        }

        // 生成中进度
        when (val s = vm.genState) {
            is GenState.Running -> {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        when (s.stage) {
                            GenerationStage.EXPOSURE -> "曝光修正中…"
                            GenerationStage.DOWNSAMPLE -> "降采样中…"
                            GenerationStage.BACKGROUND -> "移除背景中…"
                            GenerationStage.REDUCE -> "压缩颜色中…"
                            GenerationStage.MAP -> "色卡映射中…"
                            GenerationStage.CLEAN -> "杂色清理中…"
                            GenerationStage.MERGE -> "相似色号合并中…"
                            GenerationStage.LIMIT -> "限色合并中…"
                            GenerationStage.DONE -> "完成"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is GenState.Error -> Text(
                "生成失败：${s.message}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {}
        }

        // 自动优选的结论：说明它替用户选了什么，以及比原参数高多少分
        vm.autoTuneInfo?.let { info ->
            ShCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Spacing.md)) {
                    Text(
                        info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // 逐档得分：同一张图在四个过滤档下的还原度，
                    // 选中档高亮。用户能看见"选错一档损失几分"，不是黑箱。
                    if (vm.autoTuneScores.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.sm))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            val best = vm.autoTuneScores.maxOf { it.second }
                            vm.autoTuneScores.forEach { (label, score) ->
                                val picked = score == best
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = MaterialTheme.shapes.small,
                                    color = if (picked) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            label.removeSuffix("过滤"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            "$score",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (picked) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 生成按钮：文字模式用文字源重新栅格化，图片模式走常规 generate
        val isTextMode = vm.textSource != null
        val ready = palette != null &&
            (if (isTextMode) vm.textSource != null else vm.imagePixels != null)
        val idle = vm.genState !is GenState.Running
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // 自动优选只对图片有意义（文字模式的栅格化参数不同），文字模式隐藏
            if (!isTextMode) {
                Button(
                    onClick = { vm.autoTune() },
                    enabled = ready && idle && !vm.autoTuneRunning,
                    modifier = Modifier
                        .weight(1.2f)
                        .height(48.dp),
                ) {
                    Text("✦ 智能优选")
                }
            }
            Button(
                onClick = {
                    // 自动优选结果已经在手上：直接进预览，不要重算一遍把
                    // 刚展示的逐档得分清空
                    if (vm.autoTuneInfo != null && vm.genState is GenState.Done) {
                        vm.openPreview()
                    } else {
                        val text = vm.textSource
                        if (text != null) vm.regenerateFromText(text) else vm.generate()
                    }
                },
                enabled = ready && idle,
                modifier = Modifier
                    .weight(if (isTextMode) 1f else 1.2f)
                    .height(48.dp),
            ) {
                if (vm.genState is GenState.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).widthIn(min = 18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(
                    if (vm.autoTuneInfo != null && vm.genState is GenState.Done) "查看图纸" else "生成图纸",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** 开关行：标题 + 说明在左，Switch 在右，用于设置类卡片内的统一节奏 */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 色卡缩略色带：取前 [count] 个色号拼一条，用来快速区分名字相近的套装 */
@Composable
private fun PaletteSwatches(palette: BeadPalette, count: Int = 8) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .width((count * 6).dp)
            .height(14.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    ) {
        palette.colors.take(count).forEach { c ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(Color(c.argb)),
            )
        }
    }
}
