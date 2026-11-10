package com.pindou.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pindou.app.ui.shadcn.ShCard
import com.pindou.app.ui.shadcn.ShIconTile
import com.pindou.app.ui.shadcn.ShPageHeader
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing

/**
 * 创建页：四个入口平铺。
 *
 * 之前创建 Tab 直接是参数页，没导入入口——用户点进来只能看到一套默认参数，
 * 找不到"从哪张图开始"。这里改成先选来源，再走各自的流程。
 */
@Composable
fun CreateScreen(
    vm: AppViewModel,
    onPickPhoto: () -> Unit,
    onPickChart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ShPageHeader(
            title = "创建",
            subtitle = "选一种来源开始做图纸",
            modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EntryCard(
                modifier = Modifier.weight(1f),
                title = "图片生成",
                subtitle = "照片转拼豆图纸",
                onClick = onPickPhoto,
            ) { ShIconTile(Icons.Rounded.AddPhotoAlternate, ClayColors.TintGreen, ClayColors.Primary) }
            EntryCard(
                modifier = Modifier.weight(1f),
                title = "像素画生成",
                subtitle = "按画像素 1:1 还原",
                onClick = onPickPhoto,
            ) { ShIconTile(Icons.Rounded.GridOn, ClayColors.TintSky, ClayColors.BrandBlue) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EntryCard(
                modifier = Modifier.weight(1f),
                title = "色号图识别",
                subtitle = "导入现成图纸，自动认格",
                onClick = onPickChart,
            ) { ShIconTile(Icons.Rounded.Palette, ClayColors.TintButter, ClayColors.BrandOchre) }
            EntryCard(
                modifier = Modifier.weight(1f),
                title = "空白画布",
                subtitle = "从零手绘",
                onClick = { vm.startBlankCanvas() },
            ) { ShIconTile(Icons.Rounded.Add, ClayColors.TintRose, ClayColors.BrandCoral) }
        }

        Spacer(Modifier.height(Spacing.xs))
        Text(
            "色号图识别会自动找网格线；找不到时可以手动调列数和格子大小对齐。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(Spacing.xxl))
    }
}

@Composable
private fun EntryCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    ShCard(
        modifier = modifier.height(146.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            icon()
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
