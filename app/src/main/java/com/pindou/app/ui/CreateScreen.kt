package com.pindou.app.ui

import com.pindou.app.ui.icon.MorphIcons
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pindou.app.ui.shadcn.ShCard
import com.pindou.app.ui.shadcn.ShIconTile
import com.pindou.app.ui.shadcn.ShPageHeader
import com.pindou.app.ui.shadcn.TextButton
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing

/**
 * 创建页：全应用唯一的新建入口。
 *
 * 照片、像素画、色号图、文字、空白画布都从这里进；项目库不再重复放
 * 「导入照片 / 空白画布」，避免用户不知道到底该点哪边。
 */
@Composable
fun CreateScreen(
    vm: AppViewModel,
    onPickPhoto: () -> Unit,
    onPickPixel: () -> Unit,
    onPickChart: () -> Unit,
) {
    var textDialogOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ShPageHeader(
            title = "创建图纸",
            subtitle = "选一个来源，生成可以直接照着拼的图纸",
            modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs),
        )

        PhotoEntry(onClick = onPickPhoto)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            EntryCard(
                modifier = Modifier.weight(1f),
                title = "导入像素画",
                subtitle = "保留原格子，逐格还原",
                icon = MorphIcons.Grid,
                iconBg = ClayColors.TintMint,
                iconTint = ClayColors.BrandTeal,
                onClick = onPickPixel,
            )
            EntryCard(
                modifier = Modifier.weight(1f),
                title = "色号图识别",
                subtitle = "现成图纸，自动认格",
                icon = MorphIcons.Palette,
                iconBg = ClayColors.TintButter,
                iconTint = ClayColors.BrandOchre,
                onClick = onPickChart,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            EntryCard(
                modifier = Modifier.weight(1f),
                title = "文字拼豆",
                subtitle = "打字排版生成图纸",
                icon = MorphIcons.Type,
                iconBg = ClayColors.TintRose,
                iconTint = ClayColors.BrandPink,
                onClick = { textDialogOpen = true },
            )
            EntryCard(
                modifier = Modifier.weight(1f),
                title = "空白画布",
                subtitle = "${vm.gridW}×${vm.gridH} · 从零手绘",
                icon = MorphIcons.Plus,
                iconBg = ClayColors.TintSky,
                iconTint = ClayColors.PrimaryInk,
                onClick = {
                    vm.startNewProject()
                    vm.startBlankCanvas()
                },
            )
        }

        Spacer(Modifier.height(Spacing.xs))
        Text(
            "不知道怎么选：普通照片用「导入照片」；图片本身就是像素画、想保留原格子，" +
                "用「导入像素画」；带色号的现成图纸用「色号图识别」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        // 玻璃底栏叠在内容上，尾部留出滚动余量
        Spacer(Modifier.height(104.dp))
    }

    if (textDialogOpen) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textDialogOpen = false },
            title = { Text("文字拼豆") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("输入文字（支持多行）") },
                        minLines = 2,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "按当前网格 ${vm.gridW}×${vm.gridH} 与色卡 " +
                            "${vm.selectedPalette?.brandLabel ?: ""} 生成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val content = text.trim()
                        if (content.isNotBlank()) {
                            vm.startNewProject()
                            vm.generateFromText(content)
                            textDialogOpen = false
                        }
                    },
                    enabled = text.isNotBlank(),
                ) { Text("生成") }
            },
            dismissButton = {
                TextButton(onClick = { textDialogOpen = false }) { Text("取消") }
            },
        )
    }
}

/** 主入口：照片生成。白卡 + 品牌淡蓝图标块 + 推荐标签，层级高于其他入口。 */
@Composable
private fun PhotoEntry(onClick: () -> Unit) {
    ShCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        "推荐",
                        style = MaterialTheme.typography.labelSmall,
                        color = ClayColors.PrimaryInk,
                    )
                }
                Text(
                    "导入照片",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                Text(
                    "自动去背景、简化配色，生成好拼的图纸",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = Spacing.md)
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(ClayColors.TintSky),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MorphIcons.ImagePlus,
                    contentDescription = null,
                    tint = ClayColors.PrimaryInk,
                    modifier = Modifier.size(30.dp),
                )
            }
            Icon(
                MorphIcons.ArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = Spacing.xs)
                    .size(18.dp),
            )
        }
    }
}

@Composable
private fun EntryCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    onClick: () -> Unit,
) {
    ShCard(
        modifier = modifier.height(142.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            ShIconTile(icon, iconBg, iconTint)
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
