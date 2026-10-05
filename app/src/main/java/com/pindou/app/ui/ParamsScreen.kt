package com.pindou.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.pattern.GenerationStage
import com.pindou.app.ui.theme.Spacing

private val GridPresets = listOf("29×29", "15×15", "58×58", "自定义")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamsScreen(vm: AppViewModel) {
    val bitmap = vm.imagePreview
    val palette = vm.selectedPalette
    var preset by remember { mutableStateOf(if (vm.gridW == vm.gridH && vm.gridW in 15..58) "${vm.gridW}×${vm.gridH}" else "自定义") }
    var paletteMenuOpen by remember { mutableStateOf(false) }
    val beadSizeTag = palette?.beadSize?.name ?: "MM5"
    val beadSizeLabel = if (beadSizeTag == "MM2_6") "2.6mm 小豆" else "5mm 普通豆"
    val availablePalettes = vm.palettesFor(beadSizeTag)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            "参数设置",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(top = Spacing.lg),
        )

        bitmap?.let {
            Card(shape = MaterialTheme.shapes.large) {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "导入的图片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        // 网格尺寸
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("网格尺寸", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    GridPresets.forEach { p ->
                        FilterChip(
                            selected = preset == p,
                            onClick = {
                                preset = p
                                when (p) {
                                    "29×29" -> { vm.gridW = 29; vm.gridH = 29 }
                                    "15×15" -> { vm.gridW = 15; vm.gridH = 15 }
                                    "58×58" -> { vm.gridW = 58; vm.gridH = 58 }
                                }
                            },
                            label = { Text(p) },
                        )
                    }
                }
                if (preset == "自定义") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        OutlinedTextField(
                            value = vm.gridW.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { vm.gridW = it.coerceIn(5, 200) } },
                            label = { Text("宽") },
                            modifier = Modifier.widthIn(min = 100.dp),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = vm.gridH.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { vm.gridH = it.coerceIn(5, 200) } },
                            label = { Text("高") },
                            modifier = Modifier.widthIn(min = 100.dp),
                            singleLine = true,
                        )
                    }
                }
                Text(
                    "共 ${vm.gridW * vm.gridH} 粒豆子",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 豆子规格 + 色卡
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("豆子与色卡", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FilterChip(
                        selected = beadSizeTag == "MM5",
                        onClick = {
                            val five = vm.palettesFor("MM5")
                            if (five.isNotEmpty() && palette?.beadSize?.name != "MM5") {
                                vm.selectPalette(five.firstOrNull { it.brand == "MARD" } ?: five.first())
                            }
                        },
                        label = { Text("5mm 普通豆") },
                    )
                    FilterChip(
                        selected = beadSizeTag == "MM2_6",
                        onClick = {
                            val mini = vm.palettesFor("MM2_6")
                            if (mini.isNotEmpty()) vm.selectPalette(mini.first())
                        },
                        label = { Text("2.6mm 小豆") },
                    )
                }
                if (palette != null) {
                    ExposedDropdownMenuBox(
                        expanded = paletteMenuOpen,
                        onExpandedChange = { paletteMenuOpen = it },
                    ) {
                        OutlinedTextField(
                            value = palette.brandLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("色卡（${availablePalettes.size} 套可选）") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = paletteMenuOpen) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = paletteMenuOpen, onDismissRequest = { paletteMenuOpen = false }) {
                            availablePalettes.forEach { p ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("${p.brandLabel}（${p.colors.size} 色）") },
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

        // 颜色数与进阶
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("限制最大颜色数", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = vm.limitColors, onCheckedChange = { vm.limitColors = it })
                }
                if (vm.limitColors) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Slider(
                            value = vm.maxColors.toFloat(),
                            onValueChange = { vm.maxColors = it.toInt() },
                            valueRange = 4f..48f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${vm.maxColors} 色", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("移除背景", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "自动去掉照片边缘的纯色背景（适合白底商品图/证件照）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = vm.removeBackground, onCheckedChange = { vm.removeBackground = it })
                }
                if (vm.removeBackground) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Slider(
                            value = vm.backgroundTolerance,
                            onValueChange = { vm.backgroundTolerance = it },
                            valueRange = 4f..30f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("容差 ${vm.backgroundTolerance.toInt()}", style = MaterialTheme.typography.titleSmall)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Floyd–Steinberg 抖动", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "进阶选项，默认关闭（抖动图更难拼）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = vm.dithering, onCheckedChange = { vm.dithering = it })
                }
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
                            GenerationStage.DOWNSAMPLE -> "降采样中…"
                            GenerationStage.BACKGROUND -> "移除背景中…"
                            GenerationStage.REDUCE -> "压缩颜色中…"
                            GenerationStage.MAP -> "色卡映射中…"
                            GenerationStage.CLEAN -> "杂色清理中…"
                            GenerationStage.DONE -> "完成"
                        },
                        style = MaterialTheme.typography.bodyMedium,
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

        Button(
            onClick = { vm.generate() },
            enabled = palette != null && vm.imagePixels != null &&
                vm.genState !is GenState.Running,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.lg),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (vm.genState is GenState.Running) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).widthIn(min = 18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text("生成图纸", modifier = Modifier.padding(start = 8.dp))
        }
    }
}
