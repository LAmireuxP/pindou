package com.pindou.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.pindou.app.ui.shadcn.ShCard
import com.pindou.app.ui.shadcn.ShChip
import com.pindou.app.ui.shadcn.ShPageHeader
import com.pindou.app.ui.theme.Spacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var paletteMenu by remember { mutableStateOf(false) }
    val settings = vm.settings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ShPageHeader(
            title = "设置",
            subtitle = "默认参数只影响新建的图纸",
            modifier = Modifier.padding(top = Spacing.lg),
        )

        // 默认参数
        ShCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("默认网格尺寸", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf(29 to 29, 48 to 48, 58 to 58, 78 to 78).forEach { (w, h) ->
                        ShChip(
                            text = "${w}×${h}",
                            selected = settings.defaultGridW == w && settings.defaultGridH == h,
                            onClick = {
                                vm.viewModelScope.launch { vm.settingsStore.setDefaultGrid(w, h) }
                            },
                        )
                    }
                }

                Text(
                    "默认色卡",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
                val brands = vm.palettes.map { it.brand to it.brandLabel }.distinct()
                val currentLabel = brands.firstOrNull { it.first == settings.defaultPaletteBrand }?.second
                    ?: settings.defaultPaletteBrand
                ExposedDropdownMenuBox(
                    expanded = paletteMenu,
                    onExpandedChange = { paletteMenu = it },
                ) {
                    OutlinedTextField(
                        value = currentLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("打开新图纸时预选") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = paletteMenu) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = paletteMenu, onDismissRequest = { paletteMenu = false }) {
                        brands.forEach { (brand, label) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    vm.viewModelScope.launch { vm.settingsStore.setDefaultPalette(brand) }
                                    paletteMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // 外观
        ShCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("主题", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                        ShChip(
                            text = label,
                            selected = settings.themeMode == mode,
                            onClick = {
                                vm.viewModelScope.launch { vm.settingsStore.setThemeMode(mode) }
                            },
                        )
                    }
                }
            }
        }

        // 关于
        ShCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("关于", style = MaterialTheme.typography.titleLarge)
                Text(
                    "豆格 v0.6.0 · 纯离线生成，照片不上传",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "色卡数据：MARD/COCO/漫漫/盼盼/咪小窝 源自 Zippland/perler-beads（AGPL-3.0）；" +
                        "Perler/Hama/Artkal 源自 xuange6610/PindouAI（Apache-2.0）。色值为工程近似值，与实体豆可能存在色差。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "本项目以 AGPL-3.0 开源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(modifier = Modifier.padding(bottom = Spacing.lg)) {}
    }
}
