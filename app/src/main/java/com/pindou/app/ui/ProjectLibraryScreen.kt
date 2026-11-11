package com.pindou.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pindou.app.core.data.ProjectEntity
import com.pindou.app.ui.shadcn.Button
import com.pindou.app.ui.shadcn.ShCard
import com.pindou.app.ui.shadcn.ShChip
import com.pindou.app.ui.shadcn.ShSearchField
import com.pindou.app.ui.shadcn.TextButton
import com.pindou.app.ui.shadcn.OutlinedButton
import com.pindou.app.ui.theme.ClayColors
import com.pindou.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 项目库首页：只做作品管理。
 *
 * 新建图纸统一收在「创建」页。这里保留的「导入 .bpat」是工程文件导入，
 * 与照片/像素图/色号图的生成流程不是一件事，因此单独放在标题右侧。
 */
@Composable
fun ProjectLibraryScreen(
    vm: AppViewModel,
    onGoCreate: () -> Unit,
    onPickBpat: () -> Unit = {},
) {
    // 必须订阅（collectAsState）而非读 .value：StateFlow 配 WhileSubscribed 时读取不会启动上游查询
    val projects by vm.projects.collectAsState()
    var renameTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var albumTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    val visibleProjects = vm.filteredProjects(projects)

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
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "项目库",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "${projects.size} 份图纸 · 保存在本机",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            OutlinedButton(
                onClick = onPickBpat,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(
                    Icons.Rounded.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("导入 .bpat", style = MaterialTheme.typography.labelMedium)
            }
        }

        ShSearchField(
            value = vm.libraryQuery,
            onValueChange = { vm.libraryQuery = it },
            placeholder = "搜索图纸名称或专辑",
        )

        // 收藏 / 专辑筛选：横向滚动的 pill chip
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            ShChip(
                text = "全部",
                selected = !vm.libraryFavOnly && vm.libraryAlbum == null,
                onClick = {
                    vm.libraryFavOnly = false
                    vm.libraryAlbum = null
                },
            )
            ShChip(
                text = "★ 收藏",
                selected = vm.libraryFavOnly,
                onClick = {
                    vm.libraryFavOnly = !vm.libraryFavOnly
                    vm.libraryAlbum = null
                },
            )
            vm.albumNames(projects).forEach { album ->
                ShChip(
                    text = album,
                    selected = vm.libraryAlbum == album,
                    onClick = { vm.libraryAlbum = if (vm.libraryAlbum == album) null else album },
                )
            }
        }

        vm.lastError?.let { err ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(Spacing.sm),
            ) {
                Text(
                    err,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (projects.isEmpty()) {
            ShCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ClayColors.TintSky),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.GridView,
                            contentDescription = null,
                            tint = ClayColors.PrimaryInk,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Text(
                        "还没有图纸",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spacing.md),
                    )
                    Text(
                        "去创建页导入照片、像素画或色号图，生成的图纸会自动保存在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Spacing.xxs),
                    )
                    Button(
                        onClick = onGoCreate,
                        modifier = Modifier.padding(top = Spacing.md),
                    ) {
                        Text("去创建")
                    }
                }
            }
        } else if (visibleProjects.isEmpty()) {
            ShCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("没有匹配的图纸", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "换个关键词，或清除当前筛选",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xxs),
                    )
                    TextButton(
                        onClick = {
                            vm.libraryQuery = ""
                            vm.libraryFavOnly = false
                            vm.libraryAlbum = null
                        },
                        modifier = Modifier.padding(top = Spacing.xs),
                    ) {
                        Text("清除筛选")
                    }
                }
            }
        } else {
            // 手写两列网格：LazyVerticalGrid 不能嵌在 verticalScroll 里（无限高度约束崩溃）
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(bottom = Spacing.lg),
            ) {
                visibleProjects.chunked(2).forEach { rowProjects ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowProjects.forEach { project ->
                            Box(Modifier.weight(1f)) {
                                ProjectCard(
                                    project = project,
                                    // 数据库里存的是品牌代号，卡片上要给人看的名字
                                    paletteLabel = vm.palettes.firstOrNull { it.brand == project.paletteBrand }
                                        ?.brandLabel ?: project.paletteBrand,
                                    onClick = { vm.openProject(project) },
                                    onRename = { renameTarget = project },
                                    onDelete = { deleteTarget = project },
                                    onFav = { vm.setFavProject(project, !project.fav) },
                                    onDuplicate = { vm.duplicateProject(project) },
                                    onAlbum = { albumTarget = project },
                                )
                            }
                        }
                        if (rowProjects.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
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
                    shape = RoundedCornerShape(8.dp),
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

    albumTarget?.let { target ->
        var album by remember(target.id) { mutableStateOf(target.album) }
        AlertDialog(
            onDismissRequest = { albumTarget = null },
            title = { Text("移到专辑") },
            text = {
                Column {
                    OutlinedTextField(
                        value = album,
                        onValueChange = { album = it },
                        singleLine = true,
                        label = { Text("专辑名（留空 = 未分组）") },
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setAlbumProject(target, album)
                    albumTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { albumTarget = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ProjectEntity,
    paletteLabel: String,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onFav: () -> Unit,
    onDuplicate: () -> Unit,
    onAlbum: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
            if (project.fav) {
                Text(
                    "★",
                    style = MaterialTheme.typography.labelMedium,
                    color = ClayColors.BrandOchre,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = Spacing.sm, vertical = 10.dp)) {
            Text(
                project.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("${project.width}×${project.height} · $paletteLabel")
                    if (project.album.isNotBlank()) append(" · ${project.album}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                relativeTime(project.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (menuOpen) {
        AlertDialog(
            onDismissRequest = { menuOpen = false },
            title = { Text(project.name) },
            text = {
                Column {
                    Text(
                        "${project.width}×${project.height} · $paletteLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        menuOpen = false
                        onFav()
                    }) { Text(if (project.fav) "取消收藏" else "收藏") }
                    TextButton(onClick = {
                        menuOpen = false
                        onDuplicate()
                    }) { Text("复刻一份") }
                    TextButton(onClick = {
                        menuOpen = false
                        onAlbum()
                    }) { Text("移到专辑") }
                    TextButton(onClick = {
                        menuOpen = false
                        onRename()
                    }) { Text("重命名") }
                    TextButton(onClick = {
                        menuOpen = false
                        onDelete()
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = {},
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
            .size(28.dp)
            .background(color, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(3.dp)),
        )
    }
}
