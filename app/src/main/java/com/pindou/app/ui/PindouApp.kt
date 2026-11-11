package com.pindou.app.ui

import com.pindou.app.ui.icon.MorphIcons
import com.pindou.app.ui.theme.ClayColors
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Tab(
    val target: HomeTab,
    val label: String,
    val outlined: ImageVector,
    val filled: ImageVector,
)

private val Tabs = listOf(
    Tab(HomeTab.LIBRARY, "项目库", MorphIcons.House, MorphIcons.House),
    Tab(HomeTab.CREATE, "创建", MorphIcons.CirclePlus, MorphIcons.CirclePlus),
    Tab(HomeTab.SETTINGS, "设置", MorphIcons.Settings, MorphIcons.Settings),
)

@Composable
fun PindouApp(vm: AppViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        while (true) {
            // toast 显示后清空，避免重复弹出
            val msg = vm.toast
            if (msg != null) {
                // 1.8 秒自动收：Material 的 Short 是 4 秒，压在画布/工具条上
                // 挡点击的时间太长。自己起一个计时协程提前 dismiss。
                val autoHide = launch {
                    delay(1800)
                    snackbarHostState.currentSnackbarData?.dismiss()
                }
                snackbarHostState.showSnackbar(
                    message = msg,
                    duration = SnackbarDuration.Short,
                    withDismissAction = false,
                )
                autoHide.cancel()
                vm.toast = null
            }
            delay(300)
        }
    }

    // 图片选择器：走标准 SAF（OpenDocument）。
    // 已知问题：HyperOS 上 PickVisualMedia（小米「安全访问」通道）返回后，
    // 会残留一个透明层吞掉应用内所有点击，故暂时全程使用 SAF 选择器。
    // 当前这次选择器回调走哪条支路：true = 显式「像素画生成」
    var pendingPixelMode by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            // 只在真的选到文件后重置。取消选择器不能把用户当前打开的项目清空。
            vm.startNewProject()
            vm.onImagePicked(it, pendingPixelMode)
        }
    }

    // .bpat 工程文件导入：同样走 SAF，任意 mime（导出方自定义扩展名，不做类型白名单）
    val bpatPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importBpat) }

    // 色号图（现成图纸）走同一套 SAF 选择器，回调进校准页
    val chartPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::onChartPicked) }

    fun pickImage() {
        pendingPixelMode = false
        imagePicker.launch(arrayOf("image/*"))
    }

    fun pickPixelImage() {
        pendingPixelMode = true
        imagePicker.launch(arrayOf("image/*"))
    }

    fun pickBpat() {
        bpatPicker.launch(arrayOf("*/*"))
    }

    fun pickChart() {
        chartPicker.launch(arrayOf("image/*"))
    }

    BackHandler(enabled = vm.screen != Screen.HOME) {
        vm.handleBack()
    }

    // 首页的创建/设置页按返回先回项目库，避免一按返回直接退出应用。
    BackHandler(enabled = vm.screen == Screen.HOME && vm.homeTab != HomeTab.LIBRARY) {
        vm.selectHomeTab(HomeTab.LIBRARY)
    }

    // 底栏毛玻璃的取样源：页面内容画进这一层，底栏再从里面取背景做模糊
    val hazeState = remember { HazeState() }

    Scaffold(
        // 结果提示做成小药丸：默认的整条 Snackbar 又宽又高，会盖住底部工具条
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.padding(bottom = if (vm.screen == Screen.HOME) 96.dp else 0.dp),
            ) { data ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ClayColors.Ink,
                    contentColor = ClayColors.OnDark,
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    Text(
                        data.visuals.message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .haze(hazeState),
            ) {
                Box(Modifier.padding(innerPadding)) {
                    when (vm.screen) {
                        Screen.HOME -> HomeScreen(
                            vm,
                            onPickImage = ::pickImage,
                            onPickPixel = ::pickPixelImage,
                            onPickBpat = ::pickBpat,
                            onPickChart = ::pickChart,
                        )
                        Screen.CROP -> CropScreen(vm)
                        Screen.PARAMS -> ParamsScreen(vm)
                        Screen.PREVIEW -> PreviewScreen(vm)
                        Screen.EDITOR -> com.pindou.app.ui.editor.EditorScreen(vm)
                        Screen.CONSTRUCTION -> com.pindou.app.ui.build.ConstructionScreen(vm)
                        Screen.CHART_CALIBRATE -> ChartCalibrateScreen(vm)
                        Screen.PIXEL_CALIBRATE -> PixelCalibrateScreen(vm)
                    }
                }
            }

            // 玻璃底栏叠在内容之上，列表从半透明背景后面滚过
            if (vm.screen == Screen.HOME) {
                GlassBottomBar(
                    hazeState = hazeState,
                    current = vm.homeTab,
                    onSelect = vm::selectHomeTab,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    vm: AppViewModel,
    onPickImage: () -> Unit,
    onPickPixel: () -> Unit,
    onPickBpat: () -> Unit,
    onPickChart: () -> Unit,
) {
    when (vm.homeTab) {
        HomeTab.LIBRARY -> ProjectLibraryScreen(
            vm,
            onGoCreate = { vm.selectHomeTab(HomeTab.CREATE) },
            onPickBpat = onPickBpat,
        )
        HomeTab.CREATE -> CreateScreen(
            vm,
            onPickPhoto = onPickImage,
            onPickPixel = onPickPixel,
            onPickChart = onPickChart,
        )
        HomeTab.SETTINGS -> SettingsScreen(vm)
    }
}

/**
 * 底部玻璃导航。
 *
 * 关键不是“白色半透明条”，而是让底下的卡片和缩略图真的透上来：
 * 低透明度 tint + 34dp 模糊只负责压住噪点，识别度交给顶部的镜面高光、
 * 1dp 亮边和选中项自己的小玻璃片。底栏不再铺满整行，而是离边 20dp 的
 * 悬浮胶囊，滚到底时内容会从胶囊两侧露出来。
 *
 * 注意：底栏不占 Scaffold 的 bottomBar 槽位，三个首页列表末尾都留了
 * 104dp 的滚动余量，最后一行不会被胶囊压住。
 */
@Composable
private fun GlassBottomBar(
    hazeState: HazeState,
    current: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(30.dp)
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.35f
    val selectedInk = if (dark) MaterialTheme.colorScheme.primary else ClayColors.PrimaryInk
    val idleInk = if (dark) Color.White.copy(alpha = 0.62f) else ClayColors.Body.copy(alpha = 0.68f)
    val activeGlassTop =
        if (dark) Color.White.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val activeGlassBottom =
        if (dark) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
    val activeEdge =
        if (dark) Color.White.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
    val hairline = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.72f)

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .widthIn(max = 372.dp)
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.05f),
                spotColor = Color.Black.copy(alpha = if (dark) 0.38f else 0.12f),
            )
            .clip(shape)
            .hazeChild(
                state = hazeState,
                shape = shape,
                style = HazeStyle(
                    // 20% 白 + 34dp 模糊：卡片颜色能透上来，文字仍有足够底
                    tint = if (dark) Color(0xFF0D141B).copy(alpha = 0.34f)
                    else Color.White.copy(alpha = 0.20f),
                    blurRadius = 34.dp,
                    noiseFactor = 0.015f,
                ),
            )
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.10f else 0.12f),
                    0.48f to Color.White.copy(alpha = if (dark) 0.03f else 0.03f),
                    1f to Color.White.copy(alpha = if (dark) 0.02f else 0.06f),
                ),
            )
            // 斜向镜面：左下到右上的对角线反光，避免整块发白，只在左上角亮
            .drawWithContent {
                drawRect(
                    brush = Brush.linearGradient(
                        0f to Color.White.copy(alpha = if (dark) 0.09f else 0.26f),
                        0.26f to Color.White.copy(alpha = if (dark) 0.03f else 0.08f),
                        0.52f to Color.Transparent,
                        1f to Color.Transparent,
                        start = Offset(0f, 0f),
                        end = Offset(size.width * 0.9f, size.height * 1.35f),
                    ),
                )
                drawContent()
            }
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.30f else 0.95f),
                    0.18f to Color.White.copy(alpha = if (dark) 0.18f else 0.58f),
                    0.72f to hairline.copy(alpha = if (dark) 0.18f else 0.28f),
                    1f to Color.White.copy(alpha = if (dark) 0.10f else 0.46f),
                ),
                shape = shape,
            )
            // 顶部一道横向高光，边缘淡出，像玻璃上沿的反光
            .drawWithContent {
                drawContent()
                val inset = 10.dp.toPx()
                drawLine(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.18f to Color.White.copy(alpha = if (dark) 0.16f else 0.82f),
                        0.55f to Color.White.copy(alpha = if (dark) 0.20f else 0.92f),
                        0.82f to Color.White.copy(alpha = if (dark) 0.12f else 0.70f),
                        1f to Color.Transparent,
                    ),
                    start = Offset(inset, 0.8.dp.toPx()),
                    end = Offset(size.width - inset, 0.8.dp.toPx()),
                    strokeWidth = 0.8.dp.toPx(),
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tabs.forEach { tab ->
                val selected = current == tab.target
                val tint = if (selected) selectedInk else idleInk

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { onSelect(tab.target) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(
                                if (selected) {
                                    Brush.verticalGradient(
                                        0f to activeGlassTop,
                                        1f to activeGlassBottom,
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        1f to Color.Transparent,
                                    )
                                },
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) activeEdge else Color.Transparent,
                                shape = RoundedCornerShape(15.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (selected) tab.filled else tab.outlined,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = tab.label,
                        color = tint,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
