package com.pindou.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.pindou.app.core.export.ExportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pindou.app.ui.theme.Spacing

@Composable
fun PreviewScreen(vm: AppViewModel) {
    when (val s = vm.genState) {
        is GenState.Done -> PreviewContent(vm, s.result)
        else -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("还没有生成结果", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { vm.backToParams() }, modifier = Modifier.padding(top = Spacing.md)) {
                Text("返回参数设置")
            }
        }
    }
}

@Composable
private fun PreviewContent(vm: AppViewModel, result: com.pindou.app.core.pattern.PatternResult) {
    var showCodes by remember { mutableStateOf(true) }
    val usage = remember(result) { result.usage() }
    val total = usage.sumOf { it.second }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMsg by remember { mutableStateOf<String?>(null) }

    fun runExport(label: String, block: () -> ExportService.Exported?) {
        scope.launch {
            exportMsg = "正在导出$label…"
            val out = withContext(Dispatchers.IO) { block() }
            when (out) {
                is ExportService.Exported.FileReady -> {
                    exportMsg = "$label 已生成"
                    ExportService.shareFile(context, out)
                }
                is ExportService.Exported.Failed -> exportMsg = "$label 失败：${out.message}"
                null -> exportMsg = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.backToHome() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回项目库")
                }
                Text(
                    "${result.width}×${result.height} · ${result.palette.brandLabel}",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("色号", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = showCodes, onCheckedChange = { showCodes = it })
            }
        }

        Card(shape = MaterialTheme.shapes.large) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(result.width.toFloat() / result.height.toFloat()),
                ) {
                    PatternCanvas(result = result, showCodes = showCodes)
                }
                // 预览画布支持捏合缩放；1 倍下每格小于 26px 时色号画不出来，提示捏合放大
                val cellPxApprox = 1000f / result.width
                if (showCodes && cellPxApprox < 26f) {
                    Text(
                        "格子较小：双指捏合画布放大即可查看色号，双击复原",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = Spacing.sm, vertical = 4.dp),
                    )
                }
            }
        }

        Text(
            "共 $total 粒，${usage.size} 种颜色 · 用时 ${result.elapsedMs}ms",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("用量清单", style = MaterialTheme.typography.titleLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((usage.size.coerceAtMost(10) * 44 + 24).dp),
            ) {
                items(usage.size) { i ->
                    val (idx, count) = usage[i]
                    val entry = result.palette.colors[idx]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .background(Color(entry.argb), CircleShape),
                            )
                            Column {
                                Text(entry.code, style = MaterialTheme.typography.titleSmall)
                                entry.name?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Text(
                            "$count 粒",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }

        Text("导出", style = MaterialTheme.typography.titleLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                val projectName = vm.projectName
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(
                        onClick = {
                            runExport("PNG 图纸") {
                                ExportService.exportPng(context, result, projectName, symbol = false)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small,
                    ) { Text("PNG 图纸") }
                    Button(
                        onClick = {
                            runExport("符号版") {
                                ExportService.exportPng(context, result, projectName, symbol = true)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) { Text("符号版") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(
                        onClick = {
                            runExport("PDF 打印版") {
                                ExportService.exportPdf(context, result, projectName)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                    ) { Text("PDF 打印版") }
                    Button(
                        onClick = {
                            ExportService.shareList(context, result, projectName)
                        },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) { Text("分享清单") }
                }
                exportMsg?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Button(
                onClick = { vm.enterEditor() },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("编辑图纸")
            }
            Button(
                onClick = { vm.enterConstruction() },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) {
                Text("开始拼")
            }
            Button(
                onClick = { vm.backToParams() },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text("调整参数")
            }
        }
    }
}
