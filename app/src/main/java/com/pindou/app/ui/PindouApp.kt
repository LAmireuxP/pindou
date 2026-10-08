package com.pindou.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay

private data class Tab(
    val label: String,
    val outlined: ImageVector,
    val filled: ImageVector,
)

private val Tabs = listOf(
    Tab("项目库", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("创建", Icons.Outlined.AddCircleOutline, Icons.Filled.AddCircle),
    Tab("设置", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun PindouApp(vm: AppViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        while (true) {
            // toast 显示后清空，避免重复弹出
            val msg = vm.toast
            if (msg != null) {
                snackbarHostState.showSnackbar(msg)
                vm.toast = null
            }
            delay(300)
        }
    }

    // 图片选择器：走标准 SAF（OpenDocument）。
    // 已知问题：HyperOS 上 PickVisualMedia（小米「安全访问」通道）返回后，
    // 会残留一个透明层吞掉应用内所有点击，故暂时全程使用 SAF 选择器。
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::onImagePicked) }

    fun pickImage() {
        imagePicker.launch(arrayOf("image/*"))
    }

    BackHandler(enabled = vm.screen != Screen.HOME) {
        vm.handleBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // 仅首页显示底部导航，流程页保持画布聚焦
            if (vm.screen == Screen.HOME) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == index) tab.filled else tab.outlined,
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (vm.screen) {
                Screen.HOME -> HomeScreen(vm, selectedTab, onPickImage = ::pickImage)
                Screen.CROP -> CropScreen(vm)
                Screen.PARAMS -> ParamsScreen(vm)
                Screen.PREVIEW -> PreviewScreen(vm)
                Screen.EDITOR -> com.pindou.app.ui.editor.EditorScreen(vm)
                Screen.CONSTRUCTION -> com.pindou.app.ui.build.ConstructionScreen(vm)
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: AppViewModel, selectedTab: Int, onPickImage: () -> Unit) {
    when (selectedTab) {
        0 -> ProjectLibraryTab(vm, onPickImage)
        1 -> ParamsScreen(vm)
        else -> SettingsScreen(vm)
    }
}

@Composable
private fun ProjectLibraryTab(vm: AppViewModel, onPickImage: () -> Unit) {
    ProjectLibraryScreen(vm, onPickImage)
}
