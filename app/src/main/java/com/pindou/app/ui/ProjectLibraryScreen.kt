package com.pindou.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pindou.app.core.data.ProjectEntity
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 项目库首页：新建入口 + 项目卡片网格 */
@Composable
fun ProjectLibraryScreen(vm: AppViewModel, onPickImage: () -> Unit) {
    // 必须订阅（collectAsState）而非读 .value：StateFlow 配 WhileSubscribed 时读取不会启动上游查询
    val projects by vm.projects.collectAsState()
    var renameTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var textDialogOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProjectEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = "拼豆图纸",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(top = Spacing.lg),
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = ClayColors.BrandPink,
                contentColor = ClayColors.OnPrimary,
            ),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    vm.startNewProject()
                    onPickImage()
                },
        ) {
            Column(Modifier.padding(Spacing.lg)) {
                Text("导入一张照片", style = MaterialTheme.typography.titleLarge)
                Text(
                    "选一张图，一键生成拼豆图纸",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = ClayColors.BrandMint),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        vm.startNewProject()
                        vm.startBlankCanvas()
                    },
            ) {
                Column(Modifier.padding(Spacing.md)) {
                    Text("空白画布", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${vm.gridW}×${vm.gridH} · 从零开始",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = ClayColors.BrandLavender),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .weight(1f)
                    .clickable { textDialogOpen = true },
            ) {
                Column(Modifier.padding(Spacing.md)) {
                    Text("文字拼豆", style = MaterialTheme.typography.titleMedium)
                    Text("打字生成图纸", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我的作品", style = MaterialTheme.typography.titleLarge)
            Text(
                "${projects.size} 个",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        vm.lastError?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    err,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Spacing.sm),
                )
            }
        }

        if (projects.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(Spacing.lg)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        BeadDot(ClayColors.BrandPeach)
                        BeadDot(ClayColors.BrandMint)
                        BeadDot(ClayColors.BrandLavender)
                        BeadDot(ClayColors.BrandOchre)
                        BeadDot(ClayColors.BrandCoral)
                    }
                    Text(
                        "还没有作品，点上面的粉色卡片开始",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Spacing.md),
                    )
                }
            }
        } else {
            // 手写两列网格：LazyVerticalGrid 不能嵌在 verticalScroll 里（无限高度约束崩溃）
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(bottom = Spacing.lg),
            ) {
                projects.chunked(2).forEach { rowProjects ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowProjects.forEach { project ->
                            Box(Modifier.weight(1f)) {
                                ProjectCard(
                                    project = project,
                                    onClick = { vm.openProject(project) },
                                    onRename = { renameTarget = project },
                                    onDelete = { deleteTarget = project },
                                )
                            }
                        }
                        if (rowProjects.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "将按当前网格 ${vm.gridW}×${vm.gridH} 与色卡 ${vm.selectedPalette?.brandLabel ?: ""} 生成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.startNewProject()
                        vm.generateFromText(text)
                        textDialogOpen = false
                    },
                    enabled = text.isNotBlank(),
                ) { Text("生成") }
            },
            dismissButton = { TextButton(onClick = { textDialogOpen = false }) { Text("取消") } },
        )
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("项目名") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) vm.renameProject(target, name.trim())
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目") },
            text = { Text("确定删除「${target.name}」？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(target)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ProjectEntity,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = remember(project.thumbnailPath, project.updatedAt) {
                    project.thumbnailPath?.let { path ->
                        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
                    }
                }
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = project.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    BeadDot(ClayColors.BrandMint)
                }
            }
            Column(Modifier.padding(Spacing.sm)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${project.width}×${project.height} · ${project.paletteBrand}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    relativeTime(project.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (menuOpen) {
        AlertDialog(
            onDismissRequest = { menuOpen = false },
            title = { Text(project.name) },
            text = { Text("${project.width}×${project.height} · ${project.paletteBrand}") },
            confirmButton = {
                TextButton(onClick = {
                    menuOpen = false
                    onRename()
                }) { Text("重命名") }
            },
            dismissButton = {
                TextButton(onClick = {
                    menuOpen = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

private fun relativeTime(time: Long): String {
    val diff = System.currentTimeMillis() - time
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 7 * day -> "${diff / day} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(time))
    }
}

@Composable
private fun BeadDot(color: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(Color.White.copy(alpha = 0.85f), CircleShape),
        )
    }
}