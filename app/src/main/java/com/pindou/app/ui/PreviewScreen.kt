package com.pindou.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pindou.app.core.export.ExportService
import com.pindou.app.ui.shadcn.Button
import com.pindou.app.ui.shadcn.OutlinedButton
import com.pindou.app.ui.shadcn.ShCard
import com.pindou.app.ui.shadcn.SectionTitle
import com.pindou.app.ui.shadcn.TextButton
import com.pindou.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            Text("还没有生成结果", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { vm.backToParams() }, modifier = Modifier.padding(top = Spacing.md)) {
                Text("返回参数设置")
            }
        }
    }
}

@Composable
private fun PreviewContent(vm: AppViewModel, result: com.pindou.app.core.pattern.PatternResult) {
    var showCodes by remember { mutableStateOf(true) }
    val pages = remember(result, vm.projectPages) { vm.currentPages().ifEmpty { listOf(result) } }
    var pageIdx by remember(pages) { mutableIntStateOf(0) }
    val currentPage = pages[pageIdx.coerceIn(0, pages.size - 1)]
    val usage = remember(pages) {
        val counts = HashMap<Int, Int>()
        for (p in pages) for (c in p.cells) if (c >= 0) counts.merge(c, 1, Int::plus)
        counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
    val total = usage.sumOf { it.second }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMsg by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember {
        mutableStateOf<Pair<String, () -> ExportService.Exported?>?>(null)
    }

    fun doExport(label: String, block: () -> ExportService.Exported?) {
        scope.launch {
            exportMsg = "正在导出$label…"
            val out = withContext(Dispatchers.IO) { block() }
            when (out) {
                is ExportService.Exported.FileReady -> {
                    exportMsg = "$label 已保存到下载目录：${out.file.name}"
                    ExportService.shareFile(context, out)
                }
                is ExportService.Exported.Failed -> exportMsg = "$label 失败：${out.message}"
                null -> exportMsg = null
            }
        }
    }

    // API 26–28 首次导出前申请存储权限；29+ 不会触发
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingExport
        pendingExport = null
        if (granted && pending != null) {
            doExport(pending.first, pending.second)
        } else if (!granted) {
            exportMsg = "未授予存储权限，无法写入系统下载目录"
        }
    }

    fun runExport(label: String, block: () -> ExportService.Exported?) {
        if (ExportService.needsLegacyStoragePermission(context)) {
            pendingExport = label to block
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            doExport(label, block)
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
                .padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.backFromPreview() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上一步")
            }
            Column(Modifier.weight(1f)) {
                Text("图纸预览", style = MaterialTheme.typography.displayMedium)
                Text(
                    "${result.width}×${result.height} · ${result.palette.brandLabel}" +
                        if (result.framed) " · 已自动取景" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("色号", style = MaterialTheme.typography.labelLarge)
            Switch(
                checked = showCodes,
                onCheckedChange = { showCodes = it },
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        ShCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                if (pages.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.sm, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { if (pageIdx > 0) pageIdx-- },
                            enabled = pageIdx > 0,
                        ) { Text("‹ 上一页") }
                        Text(
                            "${pageIdx + 1} / ${pages.size}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        TextButton(
                            onClick = { if (pageIdx < pages.size - 1) pageIdx++ },
                            enabled = pageIdx < pages.size - 1,
                        ) { Text("下一页 ›") }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(result.width.toFloat() / result.height.toFloat()),
                ) {
                    PatternCanvas(result = currentPage, showCodes = showCodes)
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
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = Spacing.sm, vertical = 6.dp),
                    )
                }
            }
        }

        // 统计条：三段等分，数字用 titleLarge，标签用 labelSmall
        ShCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.padding(vertical = Spacing.sm)) {
                    Stat("$total", "总粒数", Modifier.weight(1f))
                    Stat("${usage.size}", "颜色数", Modifier.weight(1f))
                    // 历史项目/编辑后的结果没有评分（EMPTY.score=0），显示占位而不是"0 分"
                    val q = result.quality
                    Stat(if (q.score > 0) "${q.score}" else "—", "还原度", Modifier.weight(1f))
                    Stat("${result.elapsedMs}ms", "生成耗时", Modifier.weight(1f))
                }
                // 评分明细：分数是结论，这三个比例是原因，用户可以据此决定换不换参数
                if (result.quality.score > 0) {
                    Text(
                        if (result.pixelArt) {
                            // 像素画没有照片纹理，碎点/糊边是色卡覆盖限制，不是转换失误；
                            // 这里只报逐格色差，避免"看着很准却写着失真明显"的自相矛盾
                            "逐像素还原原画 · 平均色差 ${"%.1f".format(result.quality.meanDeltaE)}" +
                                " · P90 ${"%.1f".format(result.quality.p90DeltaE)}" +
                                " · ${result.quality.colorCount} 色"
                        } else {
                            "${result.quality.label} · 碎点 ${"%.1f".format(result.quality.breakRate * 100)}%" +
                                " · 糊边 ${"%.1f".format(result.quality.flattenRate * 100)}%" +
                                " · 平均色差 ${"%.1f".format(result.quality.meanDeltaE)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    )
                }
            }
        }

        SectionTitle("用量清单")
        ShCard(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((usage.size.coerceAtMost(10) * 48 + 16).dp),
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .background(Color(entry.argb), CircleShape)
                                    // 描边：T01 这类近白/全透明色号在白卡上会整块消失，加发丝线兜底
                                    .border(1.dp, Color.Black.copy(alpha = 0.12f), CircleShape),
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
                        Text("$count 粒", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        SectionTitle("导出")
        ShCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                val projectName = vm.projectName
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Button(
                        onClick = {
                            runExport("PNG 图纸") {
                                ExportService.exportPng(context, pages, projectName, symbol = false)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("PNG 图纸") }
                    OutlinedButton(
                        onClick = {
                            runExport("符号版") {
                                ExportService.exportPng(context, pages, projectName, symbol = true)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("符号版") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    OutlinedButton(
                        onClick = {
                            runExport("PDF 打印版") {
                                ExportService.exportPdf(context, pages, projectName)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("PDF 打印版") }
                    TextButton(
                        onClick = { ExportService.shareList(context, pages, projectName) },
                        modifier = Modifier.weight(1f),
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
                .padding(bottom = Spacing.lg, top = Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (vm.canAdjustParams) {
                OutlinedButton(
                    onClick = { vm.backToParams() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("调整参数")
                }
            }
            OutlinedButton(
                onClick = { vm.enterEditor() },
                modifier = Modifier.weight(1f),
            ) {
                Text("编辑图纸")
            }
            Button(
                onClick = { vm.enterConstruction() },
                modifier = Modifier.weight(1f),
            ) {
                Text("开始拼")
            }
        }
    }
}

/** 统计格：数字 + 标签上下叠放 */
@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
