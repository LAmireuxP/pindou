package com.pindou.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.palette.PaletteAssets
import com.pindou.app.core.build.ConstructionState
import com.pindou.app.core.data.GridCodec
import com.pindou.app.core.data.ProjectEntity
import com.pindou.app.core.data.ProjectRepository
import com.pindou.app.core.data.SettingsStore
import com.pindou.app.core.editor.EditorEngine
import com.pindou.app.core.editor.ColorReplaceOps
import com.pindou.app.core.editor.PaletteOps
import com.pindou.app.core.image.CropRect
import com.pindou.app.core.image.ImageTransforms
import com.pindou.app.core.chart.ChartRecognizer
import com.pindou.app.core.pattern.GenerationStage
import com.pindou.app.core.pattern.BpatCodec
import com.pindou.app.core.pattern.GeneratorOptions
import com.pindou.app.core.pattern.AutoTuner
import com.pindou.app.core.pattern.FilterLevel
import com.pindou.app.core.pattern.PatternGenerator
import com.pindou.app.core.pattern.PatternResult
import com.pindou.app.core.pattern.PixelArtDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.io.File
import java.io.FileOutputStream

/** 页面导航（线性流程，M5 引入项目库后再考虑导航库） */
enum class Screen { HOME, CROP, PARAMS, PREVIEW, EDITOR, CONSTRUCTION, CHART_CALIBRATE }

/** 编辑工具 */
enum class EditorTool { PAINT, ERASE, PICKER, FILL, PAN }

/** 生成过程中的 UI 状态 */
sealed interface GenState {
    data object Idle : GenState
    data class Running(val stage: GenerationStage, val progress: Float) : GenState
    data class Done(val result: PatternResult) : GenState
    data class Error(val message: String) : GenState
}

/**
 * 裁剪页实时网格预览：一格就是最终图纸里的一粒豆。
 *
 * [image] 是 gridW×gridH 的缩略位图，画布按选区尺寸一次 drawImage 铺开。
 * 不在 Canvas 里逐格 drawRect——56×50 是 2800 次绘制，每帧都画必然掉帧，
 * 而且位图可以复用现成的 FilterQuality.None 最近邻放大，正好是"看色块"。
 * [rect] 用来判断预览是否还对应当前选区：拖动过程中旧预览不画，
 * 松手防抖算完再出图，避免色块跟着手指歪着跑。
 */
class CropGridPreview(
    val rect: CropRect,
    val gridW: Int,
    val gridH: Int,
    val image: Bitmap,
    val colorCount: Int,
    val elapsedMs: Long,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    var screen by mutableStateOf(Screen.HOME)
        private set

    // 导入的图（像素供管线使用，缩略图供参数页显示）
    var imagePixels: IntArray? = null
        private set
    var imageW: Int = 0
        private set
    var imageH: Int = 0
        private set
    var imagePreview by mutableStateOf<Bitmap?>(null)
        private set
    var importError by mutableStateOf<String?>(null)
        private set

    // 原始导入图（裁剪页「重置」恢复用；变换不原地修改数组，别名共享是安全的）
    var originalPixels: IntArray? = null
        private set
    var originalW: Int = 0
        private set
    var originalH: Int = 0
        private set

    // ---------- 色号图识别（导入别人做好的网格图纸） ----------

    /** 原图（校准页画布 + 网格线叠加用） */
    var chartBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var chartPixels: IntArray? = null
        private set
    var chartW: Int = 0
        private set
    var chartH: Int = 0
        private set
    var chartGrid by mutableStateOf<ChartRecognizer.ChartGrid?>(null)
        private set
    var chartSample by mutableStateOf<ChartRecognizer.ChartSample?>(null)
        private set
    /** 空白阈值：格内与纸白接近的像素占比达到该值即判空 */
    var chartThreshold by mutableFloatStateOf(0.55f)
        private set
    var chartAutoAligned by mutableStateOf(false)
        private set
    var chartBusy by mutableStateOf(false)
        private set
    var chartRefineRunning by mutableStateOf(false)
        private set
    /** 本次识别是否由自动检测得到的网格（false = 用了默认网格兜底） */
    var chartDetected by mutableStateOf(false)
        private set
    private var chartToken = 0

    /** 裁剪框（工作图像素坐标系），null = 不在裁剪流程中 */
    var cropRect by mutableStateOf<CropRect?>(null)
        private set

    /** 几何变换执行中标志（防止连点导致的状态竞争） */
    private var transformRunning = false

    // ---------- 裁剪页实时网格预览 ----------

    /** 裁剪页是否把每格画成最终色号（关掉只剩框线与辅助线） */
    var cropPreviewEnabled by mutableStateOf(true)
        private set

    var cropPreview by mutableStateOf<CropGridPreview?>(null)
        private set

    /** 正在算预览（防抖结束 + 管线一次） */
    var cropPreviewRunning by mutableStateOf(false)
        private set

    private var cropPreviewJob: Job? = null
    private var cropPreviewToken = 0

    /**
     * 用户在裁剪页显式定过网格：裁剪完成后不再被「像素画原画网格」覆盖。
     * 否则 56×50 的图改成 78×70 再裁，confirmCrop 里检测一次又跳回原画，
     * 用户会觉得定的尺寸没生效。
     */
    private var cropGridUserSet = false

    /** 参数页返回目标：true = 从裁剪页确认过来，返回时回裁剪页 */
    private var paramsFromCrop = false


    // 项目库与设置
    private val repository = ProjectRepository(application)
    val settingsStore = SettingsStore(application)

    /** 当前正在编辑的项目 id（null = 尚未保存的新图纸） */
    var currentProjectId by mutableStateOf<Long?>(null)
        private set

    var projectName by mutableStateOf("未命名图纸")

    val projects = repository.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    var settings by mutableStateOf(SettingsStore.Snapshot())
        private set

    var toast by mutableStateOf<String?>(null)

    /** 最近一次操作失败原因（常驻显示，便于排查与反馈） */
    var lastError by mutableStateOf<String?>(null)

    // 色卡
    var palettes by mutableStateOf<List<BeadPalette>>(emptyList())
        private set
    var selectedPalette by mutableStateOf<BeadPalette?>(null)
        private set

    // 参数
    var gridW by mutableIntStateOf(29)
    var gridH by mutableIntStateOf(29)

    /**
     * 像素画原图的画像素网格（null = 当前图不是像素画 / 或尚未检测）。
     *
     * 有这个值的时候，网格尺寸不应该由用户随手输入——56×50 的原画配
     * 56×50 之外的网格，ArtQuantizer 会把每个画像素拉伸成不等宽块，
     * 花瓣的圆弧就会变成台阶。UI 优先展示「原画网格」一键套用。
     */
    var pixelArtGrid by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /**
     * 用户从「像素画生成」入口导入：强制像素支路。
     *
     * 与自动检测分开的原因：自动检测宁漏勿错（照片被认成像素画，整张图
     * 会碎成色块），所以门槛高；用户显式声明"这是像素画"时，宁可放宽到
     * 兜底算法也要走像素支路，否则入口点了跟「图片生成」没有任何区别。
     */
    var pixelArtMode by mutableStateOf(false)
        private set

    /** 自定义宽高时是否联动另一轴（默认开，防止宽高独立输入把图拉扁） */
    var lockGridAspect by mutableStateOf(true)
        private set
    var limitColors by mutableStateOf(false)
    var maxColors by mutableIntStateOf(24)
    var dithering by mutableStateOf(false)
    var removeBackground by mutableStateOf(false)
    var backgroundTolerance by mutableFloatStateOf(12f)
    // 过滤等级：对齐颂拼豆「导入前处理」四档，默认中过滤
    var filterLevel by mutableStateOf(com.pindou.app.core.pattern.FilterLevel.MEDIUM)

    var genState by mutableStateOf<GenState>(GenState.Idle)
        private set

    /** 自动优选结果说明（"自动优选：高过滤 · 还原度 79 …"），null = 未跑过 */
    var autoTuneInfo by mutableStateOf<String?>(null)
        private set

    /**
     * 自动优选的逐档得分（过滤档名 → 该档最高分，按档位顺序）。
     * 让用户看见"为什么选这一档"——只看结论是黑箱，看到四档分数才知道
     * 差距有多大、选错一档会损失多少。
     */
    var autoTuneScores by mutableStateOf<List<Pair<String, Int>>>(emptyList())
        private set

    /** 自动优选进行中（要多跑几组参数，UI 单独禁用按钮） */
    var autoTuneRunning by mutableStateOf(false)
        private set

    // 施工模式
    var construction by mutableStateOf<ConstructionState?>(null)

    // 多页图纸：编辑器内产生页；空列表 = 只有 genState 里的单页结果
    var projectPages: List<IntArray> = emptyList()
        private set

    // 参考图对比：原图叠加显示在格子上，透明度可调
    var refBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var showRefOverlay by mutableStateOf(false)
    var refOpacity by mutableFloatStateOf(0.5f)

    // 项目库筛选
    var libraryQuery by mutableStateOf("")
    var libraryFavOnly by mutableStateOf(false)
    /** null = 全部；否则按专辑名过滤 */
    var libraryAlbum by mutableStateOf<String?>(null)

    // 编辑器
    var editor by mutableStateOf<EditorEngine?>(null)
        private set
    var editorTool by mutableStateOf(EditorTool.PAINT)
    var editorColor by mutableIntStateOf(0) // palette 索引
    /** 方形笔刷边长：1 / 3 / 5 格 */
    var editorBrushSize by mutableIntStateOf(1)
    /** 最近使用色（调色板索引），只保留 8 个，最新的在最前 */
    val editorRecentColors = mutableStateListOf<Int>()
    var editorShowCodes by mutableStateOf(true)
    var editorShowGrid by mutableStateOf(true)
    var editorRevision by mutableIntStateOf(0)
        private set

    fun notifyEditorChanged() {
        editorRevision++
    }

    /** 统一入口：选色后自动切回画笔，并记录最近使用 */
    fun selectEditorColor(index: Int) {
        val size = selectedPalette?.colors?.size ?: return
        if (size == 0) return
        val safe = index.coerceIn(0, size - 1)
        editorColor = safe
        editorRecentColors.remove(safe)
        editorRecentColors.add(0, safe)
        while (editorRecentColors.size > 8) editorRecentColors.removeAt(editorRecentColors.lastIndex)
        editorTool = EditorTool.PAINT
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = PaletteAssets.loadAll(application)
            palettes = loaded
            selectedPalette = loaded.firstOrNull { it.brand == "MARD" } ?: loaded.firstOrNull()
        }
        viewModelScope.launch {
            settingsStore.flow.collect { snapshot ->
                settings = snapshot
                // 首次加载时应用默认参数（未生成过图纸）
                if (genState is GenState.Idle && imagePixels == null) {
                    gridW = snapshot.defaultGridW
                    gridH = snapshot.defaultGridH
                    palettes.firstOrNull { it.brand == snapshot.defaultPaletteBrand }?.let {
                        selectedPalette = it
                    }
                }
            }
        }
    }

    fun palettesFor(beadSizeTag: String): List<BeadPalette> =
        palettes.filter { it.beadSize.name == beadSizeTag }

    /** Photo Picker / GetContent 回调：解码并限制最大边 2048 */
    fun onImagePicked(uri: Uri, pixelMode: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                // 注意：inJustDecodeBounds=true 时 decodeStream 返回 null（只填 bounds），
                // 因此这里只关心流能否打开与 bounds 是否有效，不能拿 decode 返回值判失败
                val boundsInput = resolver.openInputStream(uri) ?: error("无法读取图片")
                boundsInput.use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片解码失败" }

                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmpInput = resolver.openInputStream(uri) ?: error("无法读取图片")
                val bmp = bmpInput.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: error("图片解码失败")

                val pixels = IntArray(bmp.width * bmp.height)
                bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                Triple(bmp, pixels, intArrayOf(bmp.width, bmp.height))
            }.onSuccess { (bmp, pixels, size) ->
                // 像素画检测放在导入线程里做（O(源像素)，200 条抽样线），
                // 检测结果决定默认裁剪和默认网格，必须在进入裁剪页之前拿到。
                val art = if (pixelMode) {
                    PixelArtDetector.detectForced(pixels, size[0], size[1])
                } else {
                    PixelArtDetector.detect(pixels, size[0], size[1])
                }
                pixelArtMode = pixelMode
                pixelArtGrid = art?.let { it.artW to it.artH }
                // 像素画原图默认「无过滤」：不聚类、不限色，逐画像素直接映射
                // 色卡，源图每一层淡粉/淡黄都能落成独立色号。照片维持中过滤。
                filterLevel = if (pixelMode || art != null) FilterLevel.NONE else FilterLevel.MEDIUM
                originalPixels = pixels
                originalW = size[0]
                originalH = size[1]
                imagePreview = bmp
                imagePixels = pixels
                imageW = size[0]
                imageH = size[1]
                // 导入时就按图的主体定位：像素画保留完整画布（裁掉白边反而丢画像素），
                // 照片默认取中心最大正方形，避免后续网格宽高比不一致时被拉伸。
                cropRect = if (art != null) {
                    // 裁到画像素网格的外框：白边/描边留白不进图纸，网格边界
                    // 正好压在画像素边界上，取色不会跨块。
                    val l = art.ox.toFloat().coerceIn(0f, size[0] - 1f)
                    val t = art.oy.toFloat().coerceIn(0f, size[1] - 1f)
                    val r = (art.ox + art.artW * art.blockW).toFloat().coerceIn(l + 1f, size[0].toFloat())
                    val b = (art.oy + art.artH * art.blockH).toFloat().coerceIn(t + 1f, size[1].toFloat())
                    CropRect(l, t, r, b)
                } else {
                    ImageTransforms.centerSquare(size[0], size[1])
                }
                if (art != null) {
                    gridW = art.artW.coerceIn(5, 200)
                    gridH = art.artH.coerceIn(5, 200)
                }
                cropGridUserSet = false
                cropPreview = null
                paramsFromCrop = false
                adaptiveAnchor = 0
                adaptiveScale = 1f
                importError = null
                genState = GenState.Idle
                screen = Screen.CROP
                scheduleCropPreview()
            }.onFailure { e ->
                importError = e.message ?: "导入失败"
            }
        }
    }

    // ---------- 裁剪与几何变换 ----------

    private fun launchTransform(block: suspend () -> Unit) {
        if (transformRunning) return
        transformRunning = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                block()
            } finally {
                transformRunning = false
            }
        }
    }

    private fun rebuildPreview() {
        val p = imagePixels ?: return
        if (imageW <= 0 || imageH <= 0) return
        imagePreview = Bitmap.createBitmap(p, imageW, imageH, Bitmap.Config.ARGB_8888)
    }

    /**
     * 几何变换后重新识别像素画网格。
     *
     * 裁剪/旋转会换掉像素数组（检测器的缓存按数组实例失效），画像素网格
     * 也可能变（裁掉一圈后 56×50 会变成 54×48）。识别到就把网格同步成
     * 原画尺寸，用户不必自己数格子。
     */
    private fun refreshPixelArt(pixels: IntArray, w: Int, h: Int, forceGrid: Boolean = true) {
        val art = if (pixelArtMode) {
            PixelArtDetector.detectForced(pixels, w, h)
        } else {
            PixelArtDetector.detect(pixels, w, h)
        }
        pixelArtGrid = art?.let { it.artW to it.artH }
        if (art != null) {
            filterLevel = FilterLevel.NONE
            // forceGrid=false：用户在裁剪页已经定过网格，尊重他们的选择
            if (forceGrid) {
                gridW = art.artW.coerceIn(5, 200)
                gridH = art.artH.coerceIn(5, 200)
            }
            adaptiveAnchor = 0
            adaptiveScale = 1f
        } else if (pixelArtMode) {
            filterLevel = FilterLevel.NONE
        }
    }

    /** 进入裁剪页（参数页「重新裁剪」入口；导入后自动进入，不经此函数） */
    fun openCrop() {
        if (imagePixels == null || imageW <= 0 || imageH <= 0) return
        // 重新裁剪时按当前图的主体重置（像素画给全图，照片给中心正方形）
        cropRect = if (pixelArtGrid != null) {
            CropRect.full(imageW, imageH)
        } else {
            ImageTransforms.centerSquare(imageW, imageH)
        }
        cropGridUserSet = false
        screen = Screen.CROP
        scheduleCropPreview()
    }

    fun updateCropRect(rect: CropRect) {
        cropRect = rect
        scheduleCropPreview()
    }

    // ---------- 裁剪页：先定网格，再看每格颜色 ----------

    fun toggleCropPreview() {
        cropPreviewEnabled = !cropPreviewEnabled
        if (cropPreviewEnabled) {
            scheduleCropPreview()
        } else {
            cropPreviewJob?.cancel()
            cropPreview = null
            cropPreviewRunning = false
        }
    }

    fun setCropGridWidth(w: Int) {
        cropGridUserSet = true
        setGridWidthInput(w)
        scheduleCropPreview()
    }

    fun setCropGridHeight(h: Int) {
        cropGridUserSet = true
        setGridHeightInput(h)
        scheduleCropPreview()
    }

    fun setCropGridPreset(w: Int, h: Int) {
        cropGridUserSet = true
        setGridSize(w, h)
        scheduleCropPreview()
    }

    fun applyArtGridAtCrop() {
        cropGridUserSet = true
        applyArtGrid()
        scheduleCropPreview()
    }

    /**
     * 请求一次裁剪网格预览。
     *
     * 拖动时 updateCropRect 每帧都会进来，这里用 160ms 防抖 + 递增 token：
     * 只有最后一次请求的结果会被写回状态，中间那些算完就丢。管线本身是纯
     * Kotlin（无 Bitmap 锁），可以放心在 Default 线程跑。
     */
    private fun scheduleCropPreview() {
        if (screen != Screen.CROP || !cropPreviewEnabled) return
        val pixels = imagePixels ?: return
        val rect = cropRect ?: return
        val token = ++cropPreviewToken
        cropPreviewJob?.cancel()
        cropPreviewJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                delay(160)
                if (token != cropPreviewToken) return@launch
                // 色卡是异步加载的：导入后立刻排预览时 selectedPalette 可能还是 null，
                // 这里等一下加载完成再算，否则预览永远不出现（裁剪页只剩空网格）。
                val loaded = ensurePalettes()
                val palette = selectedPalette
                    ?: loaded.firstOrNull { it.brand == "MARD" }
                    ?: loaded.firstOrNull()
                    ?: return@launch
                cropPreviewRunning = true
                val sub = ImageTransforms.crop(pixels, imageW, imageH, rect)
                // 与最终生成完全同一套参数：预览色号 = 成品色号，
                // 不会出现"裁的时候好看、生成完变样"。
                val res = PatternGenerator.generate(
                    sub.pixels, sub.width, sub.height, currentOptions(), palette,
                )
                if (token != cropPreviewToken) return@launch
                val argb = IntArray(res.cells.size)
                val distinct = HashSet<Int>()
                for (i in argb.indices) {
                    val idx = res.cells[i]
                    if (idx >= 0 && idx < res.palette.colors.size) {
                        argb[i] = res.palette.colors[idx].argb
                        distinct.add(idx)
                    } else {
                        // 背景移除掉的格子保持透明，底图透出来才知道哪块被判成背景
                        argb[i] = 0
                    }
                }
                val bmp = Bitmap.createBitmap(res.width, res.height, Bitmap.Config.ARGB_8888)
                bmp.setPixels(argb, 0, res.width, 0, 0, res.width, res.height)
                cropPreview = CropGridPreview(
                    rect = rect,
                    gridW = res.width,
                    gridH = res.height,
                    image = bmp,
                    colorCount = distinct.size,
                    elapsedMs = res.elapsedMs,
                )
            } catch (e: Exception) {
                // 预览失败不打断裁剪；正式生成会重新报错。留日志便于定位静默失败。
                android.util.Log.w("PindouCrop", "裁剪预览生成失败", e)
            } finally {
                if (token == cropPreviewToken) cropPreviewRunning = false
            }
        }
    }

    /** 旋转 90°：即时作用于工作图，裁剪框随图像同步变换 */
    fun rotateImage(clockwise: Boolean) {
        val pixels = imagePixels ?: return
        val w = imageW
        val h = imageH
        val rect = cropRect
        launchTransform {
            val t = ImageTransforms.rotate90(pixels, w, h, clockwise)
            imagePixels = t.pixels
            imageW = t.width
            imageH = t.height
            if (rect != null) cropRect = ImageTransforms.rotateRect(rect, w, h, clockwise)
            rebuildPreview()
            refreshPixelArt(t.pixels, t.width, t.height)
            scheduleCropPreview()
        }
    }

    /** 镜像：即时作用于工作图，裁剪框随图像同步变换 */
    fun mirrorImage(horizontal: Boolean) {
        val pixels = imagePixels ?: return
        val w = imageW
        val h = imageH
        val rect = cropRect
        launchTransform {
            val t = ImageTransforms.mirror(pixels, w, h, horizontal)
            imagePixels = t.pixels
            imageW = t.width
            imageH = t.height
            if (rect != null) cropRect = ImageTransforms.mirrorRect(rect, w, h, horizontal)
            rebuildPreview()
            refreshPixelArt(t.pixels, t.width, t.height)
            scheduleCropPreview()
        }
    }

    /** 重置全部几何变换，恢复刚导入时的原图 */
    fun resetImageTransforms() {
        val pixels = originalPixels ?: return
        val w = originalW
        val h = originalH
        launchTransform {
            imagePixels = pixels
            imageW = w
            imageH = h
            cropRect = CropRect.full(w, h)
            rebuildPreview()
            refreshPixelArt(pixels, w, h)
            scheduleCropPreview()
        }
    }

    /** 确认裁剪并进入参数页；选区为全图时跳过像素复制 */
    fun confirmCrop() {
        val pixels = imagePixels ?: return
        val rect = cropRect ?: return
        val w = imageW
        val h = imageH
        val isFull = rect.left <= 0f && rect.top <= 0f &&
            rect.right >= w.toFloat() && rect.bottom >= h.toFloat()
        if (isFull) {
            paramsFromCrop = true
            screen = Screen.PARAMS
            return
        }
        launchTransform {
            val t = ImageTransforms.crop(pixels, w, h, rect)
            imagePixels = t.pixels
            imageW = t.width
            imageH = t.height
            cropRect = CropRect.full(t.width, t.height)
            rebuildPreview()
            // 裁剪页定过网格就保留，没定过才跟随新的画像素尺寸
            refreshPixelArt(t.pixels, t.width, t.height, forceGrid = !cropGridUserSet)
            cropGridUserSet = false
            cropPreviewJob?.cancel()
            cropPreview = null
            cropPreviewRunning = false
            withContext(Dispatchers.Main) {
                paramsFromCrop = true
                screen = Screen.PARAMS
            }
        }
    }

    fun selectPalette(palette: BeadPalette) {
        selectedPalette = palette
        scheduleCropPreview()
    }

    /** 自适应网格：长边沿用当前设定的长边，短边按图片比例计算，避免生成拉伸 */
    var adaptiveScale by mutableFloatStateOf(1f)
        private set

    /** 自适应的锚定格数（点自适应时的网格长边）；0 = 未处于自适应状态 */
    private var adaptiveAnchor = 0

    fun applyAdaptiveSize() {
        if (imageW <= 0 || imageH <= 0) return
        if (adaptiveAnchor <= 0) adaptiveAnchor = maxOf(gridW, gridH)
        recomputeAdaptive()
    }

    /** 调节自适应结果的放大倍数（0.5~4），仅在自适应状态下有效 */
    fun updateAdaptiveScale(scale: Float) {
        if (adaptiveAnchor <= 0) return
        adaptiveScale = scale
        recomputeAdaptive()
    }

    /** 显式设定网格（固定档位/手动输入），退出自适应状态 */
    fun setGridSize(w: Int, h: Int) {
        gridW = w.coerceIn(5, 200)
        gridH = h.coerceIn(5, 200)
        adaptiveAnchor = 0
        adaptiveScale = 1f
    }

    /** 当前应使用的宽高比：像素画用原画网格，照片用图片本身 */
    private fun effectiveAspect(): Double {
        val art = pixelArtGrid
        if (art != null && art.second > 0) return art.first.toDouble() / art.second
        return if (imageW > 0 && imageH > 0) imageW.toDouble() / imageH else 1.0
    }

    fun toggleGridAspectLock() {
        lockGridAspect = !lockGridAspect
    }

    /**
     * 自定义宽输入：锁定比例时按当前有效宽高比推导高。
     *
     * 旧版两个输入框各写各的，29×46 这种组合会让降采样按宽、高独立映射，
     * 圆脸被拉成椭圆——用户说的"自定义尺寸会变形"就是这条路径。
     */
    fun setGridWidthInput(w: Int) {
        val cw = w.coerceIn(5, 200)
        gridW = cw
        if (lockGridAspect) {
            gridH = (cw / effectiveAspect()).roundToInt().coerceIn(5, 200)
        }
        adaptiveAnchor = 0
        adaptiveScale = 1f
    }

    /** 自定义高输入：锁定比例时按当前有效宽高比推导宽 */
    fun setGridHeightInput(h: Int) {
        val ch = h.coerceIn(5, 200)
        gridH = ch
        if (lockGridAspect) {
            gridW = (ch * effectiveAspect()).roundToInt().coerceIn(5, 200)
        }
        adaptiveAnchor = 0
        adaptiveScale = 1f
    }

    /** 一键套用像素画原画网格（56×50 这种，一比一还原画像素） */
    fun applyArtGrid() {
        val art = pixelArtGrid ?: return
        setGridSize(art.first, art.second)
    }

    /**
     * 「原图尺寸」档位：宽度固定，高度按图片比例推导。
     * 对齐颂拼豆的档位语义——只选一个数，长边不拉伸，避免方形网格把照片压扁。
     */
    fun setGridWidthPreset(width: Int) {
        val w = width.coerceIn(5, 200)
        val h = (w / effectiveAspect()).roundToInt().coerceIn(5, 200)
        setGridSize(w, h)
    }

    private fun recomputeAdaptive() {
        // 像素画的自适应就是原画网格（×放大倍数）：56×50 的图给 78×70
        // 会把画像素切成两半，颜色被平均成源图不存在的过渡色。
        val art = pixelArtGrid
        if (art != null) {
            val (w, h) = ImageTransforms.scaledGridSize(
                art.first, art.second, adaptiveScale.toDouble(),
            )
            gridW = w
            gridH = h
            return
        }
        val (baseW, baseH) = ImageTransforms.adaptiveGridSize(imageW, imageH, adaptiveAnchor)
        val (w, h) = ImageTransforms.scaledGridSize(baseW, baseH, adaptiveScale.toDouble())
        gridW = w
        gridH = h
    }

    fun generate() {
        val pixels = imagePixels ?: return
        val palette = selectedPalette ?: return
        projectPages = emptyList()
        // 手动生成 = 用户自己改了参数，之前的自动优选结论失效
        autoTuneInfo = null
        autoTuneScores = emptyList()
        genState = GenState.Running(GenerationStage.DOWNSAMPLE, 0f)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = PatternGenerator.generate(
                    pixels, imageW, imageH,
                    currentOptions(),
                    palette,
                ) { stage, progress ->
                    genState = GenState.Running(stage, progress)
                }
                genState = GenState.Done(result)
                saveAfterGenerate(result)
                withContext(Dispatchers.Main) { screen = Screen.PREVIEW }
            } catch (e: Exception) {
                genState = GenState.Error(e.message ?: "生成失败")
            }
        }
    }

    /** 当前 UI 参数 → 管线参数；generate 与 autoTune 共用，避免两处漂移 */
    private fun currentOptions(): GeneratorOptions = GeneratorOptions(
        gridW = gridW.coerceIn(5, 200),
        gridH = gridH.coerceIn(5, 200),
        maxColors = if (limitColors) maxColors.coerceIn(1, 64) else null,
        cleanupEnabled = true,
        ditheringEnabled = dithering,
        removeBackground = removeBackground,
        backgroundTolerance = backgroundTolerance.toDouble(),
        filterLevel = filterLevel,
        exactArt = pixelArtMode,
    )

    /**
     * 自动优选：跑 4 个过滤档（未限色时再加一个 20 色档），按还原度评分取最高。
     *
     * 选中后把结果写回 UI 参数（过滤档/限色），用户能看见"它替我做了什么选择"，
     * 下次手动生成会沿用同一组参数——不是一次性黑箱。
     */
    fun autoTune() {
        val pixels = imagePixels ?: return
        val palette = selectedPalette ?: return
        if (autoTuneRunning) return
        val base = currentOptions()
        projectPages = emptyList()
        autoTuneRunning = true
        autoTuneInfo = null
        autoTuneScores = emptyList()
        genState = GenState.Running(GenerationStage.DOWNSAMPLE, 0f)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val outcome = AutoTuner.sweep(
                    pixels, imageW, imageH, base, palette,
                    // 像素画只比「无过滤」一档：聚类/限色都会把原画的离散色块
                    // 重新揉成过渡色，和"一比一还原画像素"目标相反。
                    levels = if (pixelArtGrid != null) {
                        listOf(FilterLevel.NONE)
                    } else {
                        FilterLevel.entries.toList()
                    },
                    maxColorsOptions = if (pixelArtGrid != null) {
                        listOf(base.maxColors)
                    } else {
                        AutoTuner.defaultColorOptions(base)
                    },
                ) { done, total, _ ->
                    // 进度按组数推进，视觉上要动；最后一组留给评分/落库
                    genState = GenState.Running(GenerationStage.MAP, done.toFloat() / total)
                } ?: throw IllegalStateException("没有可用参数组合")

                val best = outcome.best
                filterLevel = best.filterLevel
                if (best.maxColors != null) {
                    limitColors = true
                    maxColors = best.maxColors
                } else {
                    limitColors = false
                }
                // 与"用户原来那组参数"对比，告诉他人话上的收益
                val baseline = outcome.all.firstOrNull {
                    it.filterLevel == base.filterLevel && it.maxColors == base.maxColors
                }
                val gain = if (baseline != null) best.score - baseline.score else 0
                autoTuneScores = FilterLevel.entries.mapNotNull { lvl ->
                    outcome.all.filter { it.filterLevel == lvl }
                        .maxByOrNull { it.score }
                        ?.let { lvl.label to it.score }
                }
                val qualityText = if (best.result.pixelArt) {
                    "逐像素还原 · 平均色差 %.1f · %d 色".format(
                        best.result.quality.meanDeltaE, best.result.quality.colorCount,
                    )
                } else {
                    best.result.quality.summary()
                }
                autoTuneInfo = "自动优选：${best.filterLevel.label} · " +
                    (best.maxColors?.let { "$it 色" } ?: "不限色") + " · " + qualityText +
                    if (gain > 0) " · 比原参数高 $gain 分" else ""
                genState = GenState.Done(best.result)
                saveAfterGenerate(best.result)
                // 停在参数页：逐档得分和结论要给人看见，自动跳预览等于把
                // 刚算出来的对比信息扔掉。看完由用户点"查看图纸"进预览。
            } catch (e: Exception) {
                genState = GenState.Error(e.message ?: "自动优选失败")
            } finally {
                autoTuneRunning = false
            }
        }
    }

    /** 自动优选跑完但还没进预览时，用这个入口看结果（不重算、不清得分） */
    fun openPreview() {
        if (genState is GenState.Done) screen = Screen.PREVIEW
    }

    fun backToHome() {
        screen = Screen.HOME
        editor = null
    }

    /** 系统返回键：按流程层级上退 */
    fun handleBack(): Boolean = when (screen) {
        Screen.CONSTRUCTION -> {
            exitConstruction()
            true
        }
        Screen.EDITOR -> {
            finishEditor()
            true
        }
        Screen.PREVIEW -> {
            screen = Screen.PARAMS
            true
        }
        Screen.PARAMS -> {
            screen = if (paramsFromCrop) Screen.CROP else Screen.HOME
            paramsFromCrop = false
            true
        }
        Screen.CROP -> {
            screen = Screen.HOME
            true
        }
        Screen.CHART_CALIBRATE -> {
            screen = Screen.HOME
            true
        }
        Screen.HOME -> false
    }

    fun backToParams() {
        screen = Screen.PARAMS
    }

    /** 存档用名称：参数页可自定义，留空回退默认名 */
    private fun displayName(): String = projectName.trim().ifBlank { "未命名图纸" }

    /** 各页包装成 PatternResult（预览/导出用；调色板与尺寸全页一致） */
    fun currentPages(): List<PatternResult> {
        val result = (genState as? GenState.Done)?.result ?: return emptyList()
        val pages = projectPages.ifEmpty { listOf(result.cells) }
        return pages.map { PatternResult(result.width, result.height, it, result.palette, result.elapsedMs) }
    }

    /** 生成完成后自动建档/更新保存 */
    private suspend fun saveAfterGenerate(result: PatternResult) {
        val id = currentProjectId
        if (id != null) {
            repository.updateContent(
                id, result.width, result.height,
                result.cells, BooleanArray(result.cells.size), result.palette,
            )
            // 重新生成 = 图纸整体变化：照片来源则更新参考图，无照片（空白/文字）则清除
            if (imagePreview != null && textSource == null) {
                saveRefImage(id)
            } else {
                repository.setRefPath(id, null)
                refBitmap = null
            }
            toast = "修改已保存"
        } else {
            currentProjectId = repository.create(displayName(), result)
            projectName = displayName()
            if (imagePreview != null && textSource == null) {
                saveRefImage(currentProjectId!!)
            }
            toast = "已保存到项目库"
        }
    }

    /** 参考原图落盘：长边压到 ≤1024px 的 JPEG，供编辑器对比叠加 */
    private suspend fun saveRefImage(id: Long) {
        val bmp = imagePreview ?: return
        runCatching {
            val dir = File(getApplication<Application>().filesDir, "refs").apply { mkdirs() }
            val file = File(dir, "ref_$id.jpg")
            val scale = min(1f, 1024f / max(bmp.width, bmp.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else bmp
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (scaled !== bmp) scaled.recycle()
            repository.setRefPath(id, file.absolutePath)
            refBitmap = bmp
        }
    }

    /** 空白画布：直接创建空网格进编辑器 */
    fun startBlankCanvas() {
        val palette = selectedPalette ?: return
        pixelArtMode = false
        val w = gridW.coerceIn(5, 200)
        val h = gridH.coerceIn(5, 200)
        val cells = IntArray(w * h) { -1 }
        genState = GenState.Done(PatternResult(w, h, cells, palette, 0))
        projectPages = listOf(cells)
        editor = EditorEngine(w, h, cells)
        currentProjectId = null
        projectName = "空白画布 ${w}×${h}"
        refBitmap = null
        showRefOverlay = false
        editorRevision++
        screen = Screen.EDITOR
    }

    /** 文字拼豆：文字栅格化后走常规管线 */
    fun generateFromText(text: String) {
        val palette = selectedPalette ?: return
        if (text.isBlank()) return
        textSource = text
        projectPages = emptyList()
        genState = GenState.Running(GenerationStage.DOWNSAMPLE, 0f)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val bitmap = TextRasterizer.rasterize(text, gridW.coerceIn(5, 200), gridH.coerceIn(5, 200))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                // 二值化：文字是"白底黑字"，抗锯齿在笔画边缘留了一圈过渡灰，
                // 降采样后灰格会被映射成杂色号导致字形破碎、识别率低。
                // 按亮度阈值把过渡灰切开归为纯黑/纯白，字迹锐利后映射色号干净利落。
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val lum = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    pixels[i] = if (lum >= 128) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                }
                bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                // 让文字栅格化的像素成为"当前源"，这样参数页有预览、调整参数后 generate() 重新生成仍是文字图
                imagePixels = pixels
                imageW = bitmap.width
                imageH = bitmap.height
                // 文字位图不是照片也不是像素画，清掉画像素网格，避免沿用上一张图的 56×50
                pixelArtGrid = null
                pixelArtMode = false
                originalPixels = pixels
                originalW = bitmap.width
                originalH = bitmap.height
                cropRect = com.pindou.app.core.image.CropRect.full(bitmap.width, bitmap.height)
                imagePreview = bitmap // 保留位图供预览，不 recycle
                val result = PatternGenerator.generate(
                    pixels, bitmap.width, bitmap.height,
                    GeneratorOptions(
                        gridW = gridW.coerceIn(5, 200),
                        gridH = gridH.coerceIn(5, 200),
                        maxColors = if (limitColors) maxColors.coerceIn(1, 64) else null,
                        cleanupEnabled = true,
                        ditheringEnabled = false,
                        filterLevel = filterLevel,
                    ),
                    palette,
                ) { stage, progress ->
                    genState = GenState.Running(stage, progress)
                }
                genState = GenState.Done(result)
                saveAfterGenerate(result)
                withContext(Dispatchers.Main) { screen = Screen.PREVIEW }
            } catch (e: Exception) {
                genState = GenState.Error(e.message ?: "文字生成失败")
            }
        }
    }

    /** 打开已有项目（进入编辑器继续修改） */
    fun openProject(entity: ProjectEntity) {
        viewModelScope.launch {
            try {
                // 色卡可能尚未加载完成：这里按需等待一次，避免点了没反应
                var palette = palettes.firstOrNull { it.brand == entity.paletteBrand }
                if (palette == null) {
                    val loaded = withContext(Dispatchers.IO) { PaletteAssets.loadAll(getApplication()) }
                    if (loaded.isNotEmpty()) {
                        palettes = loaded
                        if (selectedPalette == null) {
                            selectedPalette = loaded.firstOrNull { it.brand == "MARD" } ?: loaded.first()
                        }
                    }
                    palette = loaded.firstOrNull { it.brand == entity.paletteBrand }
                }
                if (palette == null) {
                    lastError = "打开失败：未找到色卡「${entity.paletteBrand}」，当前已载入 ${palettes.size} 套"
                    return@launch
                }
                val cells = GridCodec.decodeCells(entity.cells)
                val pageLen = entity.width * entity.height
                val pageCount = entity.pages.coerceAtLeast(1)
                if (cells.size != pageLen * pageCount) {
                    lastError = "打开失败：数据尺寸不符（${cells.size} vs ${pageLen * pageCount}）"
                    return@launch
                }
                val pages = chunkInts(cells, pageLen)
                val lockPages = run {
                    val flat = GridCodec.decodeLocks(entity.locked)
                    if (flat.size == pageLen * pageCount) {
                        chunkBooleans(flat, pageLen)
                    } else {
                        // 旧数据锁定长度不符：按无锁处理
                        pages.map { BooleanArray(pageLen) }
                    }
                }
                val result = PatternResult(entity.width, entity.height, pages[0], palette, 0)
                val engine = EditorEngine(entity.width, entity.height, pages, lockPages)
                genState = GenState.Done(result)
                projectPages = pages
                refBitmap = entity.refPath?.let { path ->
                    withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
                }
                editor = engine
                currentProjectId = entity.id
                projectName = entity.name
                selectedPalette = palette
                editorRevision++
                lastError = null
                screen = Screen.EDITOR
            } catch (e: Exception) {
                lastError = "打开失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun deleteProject(entity: ProjectEntity) {
        viewModelScope.launch {
            repository.delete(entity.id)
            if (currentProjectId == entity.id) currentProjectId = null
        }
    }

    fun renameProject(entity: ProjectEntity, newName: String) {
        viewModelScope.launch { repository.rename(entity.id, newName) }
    }

    fun startNewProject() {
        currentProjectId = null
        projectName = "未命名图纸"
        genState = GenState.Idle
        editor = null
        projectPages = emptyList()
        refBitmap = null
        showRefOverlay = false
        imagePixels = null
        imagePreview = null
        imageW = 0
        imageH = 0
        originalPixels = null
        originalW = 0
        originalH = 0
        cropRect = null
        paramsFromCrop = false
        adaptiveAnchor = 0
        adaptiveScale = 1f
        textSource = null
        pixelArtMode = false
        pixelArtGrid = null
    }

    /** 当前文字拼豆的源文字（非 null = 文字模式）；改文字时更新 */
    var textSource by mutableStateOf<String?>(null)
        private set

    /** 文字拼豆改文字内容后重新生成 */
    fun regenerateFromText(text: String) {
        textSource = text
        generateFromText(text)
    }

    /** 从预览进入编辑器：以当前结果初始化引擎（重新生成会重建） */
    fun enterEditor() {
        val result = (genState as? GenState.Done)?.result ?: return
        editor = EditorEngine(result.width, result.height, projectPages.ifEmpty { listOf(result.cells) })
        editorRevision++
        screen = Screen.EDITOR
    }

    /** 完成编辑：写回当前结果并持久化到项目库 */
    fun finishEditor() {
        val engine = editor ?: return
        val current = (genState as? GenState.Done)?.result ?: return
        val pages = engine.pages.map { it.copyOf() }
        // 编辑器里可能改过尺寸：以引擎当前尺寸为准重建结果
        val updated = if (engine.width != current.width || engine.height != current.height) {
            PatternResult(engine.width, engine.height, pages[0], current.palette, current.elapsedMs)
        } else {
            current.copy(cells = pages[0])
        }
        projectPages = pages
        genState = GenState.Done(updated)
        editor = null
        val id = currentProjectId
        if (id != null) {
            viewModelScope.launch {
                // 编辑是局部修改：保留施工进度，只写回格数据与锁（多页平铺）
                repository.updateCells(
                    id = id,
                    width = updated.width,
                    height = updated.height,
                    cells = pages.reduce { acc, cur -> acc + cur },
                    locked = engine.locks.reduce { acc, cur -> acc + cur },
                    palette = updated.palette,
                    pages = pages.size,
                )
                toast = "修改已保存"
            }
        } else {
            viewModelScope.launch {
                currentProjectId = repository.importProject(
                    displayName(),
                    updated.width,
                    updated.height,
                    updated.palette,
                    pages,
                    engine.locks,
                )
            }
        }
        screen = Screen.PREVIEW
    }

    /** 进入施工模式（载入已保存的进度） */
    fun enterConstruction() {
        val result = (genState as? GenState.Done)?.result ?: return
        viewModelScope.launch {
            val entity = currentProjectId?.let { repository.get(it) }
            // 编辑器改过尺寸后进度数组长度会失配：失配即作废旧进度、游标归零
            val sizeOk = entity?.doneCells?.size == result.cells.size
            val done = if (sizeOk) {
                BooleanArray(entity!!.doneCells.size) { entity.doneCells[it] != 0.toByte() }
            } else {
                BooleanArray(result.cells.size)
            }
            construction = ConstructionState(
                width = result.width,
                height = result.height,
                done = done,
                orientation = if ((entity?.buildOrientation ?: 0) == 1) {
                    ConstructionState.Orientation.COLUMN
                } else {
                    ConstructionState.Orientation.ROW
                },
                cursor = if (sizeOk) entity?.buildCursor ?: 0 else 0,
            )
            screen = Screen.CONSTRUCTION
        }
    }

    fun exitConstruction() {
        saveConstructionProgress()
        construction = null
        screen = Screen.PREVIEW
    }

    /** 进度落库（节流由调用方保证：每格操作后调用一次即可） */
    fun saveConstructionProgress() {
        val id = currentProjectId ?: return
        val state = construction ?: return
        viewModelScope.launch {
            repository.saveBuildProgress(id, state.done, state.orientation.ordinal, state.cursor)
        }
    }

    fun undoEditor() {
        editor?.undo()
        notifyEditorChanged()
    }

    fun redoEditor() {
        editor?.redo()
        notifyEditorChanged()
    }

    // ---------- 编辑器：页操作 ----------

    /** 切换页 */
    fun switchEditorPage(index: Int) {
        editor?.setPage(index)
        notifyEditorChanged()
    }

    /** 新增一页（可复制当前页） */
    fun addEditorPage(copyCurrent: Boolean) {
        editor?.addPage(copyCurrent)
        notifyEditorChanged()
    }

    /** 删除一页（至少保留一页） */
    fun removeEditorPage(index: Int) {
        editor?.removePage(index)
        notifyEditorChanged()
    }

    /** 改尺寸：所有页内容居中保留 */
    fun resizeEditor(newWidth: Int, newHeight: Int) {
        val engine = editor ?: return
        engine.resize(newWidth.coerceIn(5, 200), newHeight.coerceIn(5, 200))
        notifyEditorChanged()
    }

    /** 参考图叠加开关 */
    fun toggleRefOverlay() {
        if (refBitmap != null) showRefOverlay = !showRefOverlay
    }

    fun updateRefOpacity(value: Float) {
        refOpacity = value.coerceIn(0.05f, 1f)
    }

    // ---------- 编辑器：色板操作 ----------

    /** 换品牌：按 ΔE2000 最近色整图重映射并立即持久化 */
    fun applyPaletteRemap(newPalette: BeadPalette) {
        val engine = editor ?: return
        val current = (genState as? GenState.Done)?.result ?: return
        val remapped = PaletteOps.remapToPalette(engine.pages, current.palette, newPalette)
        editor = EditorEngine(engine.width, engine.height, remapped, engine.locks, engine.pageIndex)
        genState = GenState.Done(
            PatternResult(engine.width, engine.height, remapped[0], newPalette, current.elapsedMs),
        )
        projectPages = remapped
        selectedPalette = newPalette
        // 新品牌色号数量可能不同，旧索引越界会让编辑页顶部色块直接崩
        editorColor = editorColor.coerceIn(0, newPalette.colors.lastIndex)
        editorRecentColors.clear()
        val id = currentProjectId
        if (id != null) {
            viewModelScope.launch {
                repository.updateCells(
                    id = id,
                    width = engine.width,
                    height = engine.height,
                    cells = remapped.reduce { acc, cur -> acc + cur },
                    locked = engine.locks.reduce { acc, cur -> acc + cur },
                    palette = newPalette,
                    pages = remapped.size,
                )
            }
        }
        notifyEditorChanged()
        toast = "已换用「${newPalette.brandLabel}」色卡"
    }

    /** 合并相近色号（ΔE 阈值），整图替换并立即持久化 */
    fun mergeSimilarEditorColors(maxDeltaE: Double) {
        mergeSimilarEditorColorsWithReport(maxDeltaE)
    }

    /**
     * 全图合并相近色（ΔE 阈值），沿用编辑引擎的撤销栈：
     * 合并后按一次撤销即可回到合并前，不丢编辑历史。
     * 返回逐条「旧色 → 新色 · 格数」明细，UI 用于展示合并了什么。
     */
    fun mergeSimilarEditorColorsWithReport(maxDeltaE: Double): List<ColorReplaceOps.Move> {
        val engine = editor ?: return emptyList()
        val palette = selectedPalette ?: return emptyList()
        val outcome = ColorReplaceOps.mergeSimilar(engine.pages, engine.locks, palette, maxDeltaE)
        if (!outcome.changed) {
            toast = "ΔE ≤ %.1f 没有可合并的相近色".format(maxDeltaE)
            return emptyList()
        }
        if (!engine.applyPages(outcome.pages)) return emptyList()
        syncEditorAfterColorOp()
        persistEditorPages()
        val usedBefore = engine.pages.flatMap { p -> p.filter { it >= 0 } }.distinct().size
        toast = "已合并 ${outcome.moves.size} 组 · ${outcome.movedCells} 格（剩 $usedBefore 色）"
        return outcome.moves
    }

    /**
     * 替换相似色：[from] 及与它 ΔE ≤ [maxDeltaE] 的已用色统一替换为 [to]。
     * maxDeltaE = 0 即只替换这一个色号。返回明细供 UI 展示。
     */
    fun replaceSimilarEditorColors(
        from: Int,
        to: Int,
        maxDeltaE: Double,
    ): List<ColorReplaceOps.Move> {
        val engine = editor ?: return emptyList()
        val palette = selectedPalette ?: return emptyList()
        if (from == to) {
            toast = "源色与目标色相同"
            return emptyList()
        }
        val outcome = ColorReplaceOps.replaceSimilar(
            engine.pages, engine.locks, palette, from, to, maxDeltaE,
        )
        if (!outcome.changed) {
            toast = if (outcome.lockedSkipped > 0) {
                "命中 ${outcome.lockedSkipped} 格，但都是锁定格"
            } else {
                "没有匹配的格子"
            }
            return emptyList()
        }
        if (!engine.applyPages(outcome.pages)) return emptyList()
        syncEditorAfterColorOp()
        persistEditorPages()
        val target = palette.colors.getOrNull(to)?.code ?: "?"
        toast = "已把 ${outcome.moves.size} 色 · ${outcome.movedCells} 格换成 $target"
        return outcome.moves
    }

    /** 全局换色后同步 genState / projectPages，并刷新画布 */
    private fun syncEditorAfterColorOp() {
        val engine = editor ?: return
        val current = (genState as? GenState.Done)?.result ?: return
        val pages = engine.pages.map { it.copyOf() }
        projectPages = pages
        genState = GenState.Done(
            current.copy(
                width = engine.width,
                height = engine.height,
                cells = pages.first(),
                palette = selectedPalette ?: current.palette,
            ),
        )
        editorRevision++
    }

    /** 全局换色后立即落库（拷贝数组，避免协程执行时被继续编辑） */
    private fun persistEditorPages() {
        val engine = editor ?: return
        val id = currentProjectId ?: return
        val palette = selectedPalette ?: return
        val pages = engine.pages.map { it.copyOf() }
        val locks = engine.locks.map { it.copyOf() }
        viewModelScope.launch {
            repository.updateCells(
                id = id,
                width = engine.width,
                height = engine.height,
                cells = pages.reduce { acc, cur -> acc + cur },
                locked = locks.reduce { acc, cur -> acc + cur },
                palette = palette,
                pages = pages.size,
            )
        }
    }

    // ---------- .bpat 导入导出 ----------

    /** 导出当前图纸为 .bpat（含全部页） */
    fun exportBpatFile() {
        val result = (genState as? GenState.Done)?.result ?: return
        val pages = editor?.pages ?: projectPages.ifEmpty { listOf(result.cells) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = currentProjectId?.let { repository.get(it) }
                val json = BpatCodec.encode(
                    title = projectName,
                    album = entity?.album ?: "",
                    brand = result.palette.brand,
                    beadSizeTag = result.palette.beadSize.name,
                    width = result.width,
                    height = result.height,
                    pages = pages,
                    paletteColors = result.palette.colors.map {
                        BpatCodec.BpatColorSource(
                            it.code,
                            String.format("#%06X", it.argb and 0xFFFFFF),
                            it.name,
                        )
                    },
                )
                val out = com.pindou.app.core.export.ExportService.exportBpat(
                    getApplication(), json, result,
                )
                withContext(Dispatchers.Main) {
                    when (out) {
                        is com.pindou.app.core.export.ExportService.Exported.FileReady ->
                            toast = "已导出 ${out.file.name}"
                        is com.pindou.app.core.export.ExportService.Exported.Failed ->
                            lastError = "导出失败：${out.message}"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { lastError = "导出失败：${e.message}" }
            }
        }
    }

    /** 导入 .bpat：落到项目库（不自动打开） */
    fun importBpat(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("无法读取文件")
                val data = BpatCodec.decode(text)
                val loaded = ensurePalettes()
                if (loaded.isEmpty()) error("没有可用色卡")
                val size = com.pindou.app.core.palette.BeadSize.fromTag(data.beadSizeTag)
                val target = loaded.firstOrNull { it.brand == data.brand && it.beadSize == size }
                    ?: loaded.firstOrNull { it.brand == data.brand }
                    ?: loaded.firstOrNull { it.beadSize == size }
                    ?: loaded.first()
                // 缺失色号用全色卡 Lab 最近匹配兜底
                val colorIndex = IntArray(data.colors.size) { i ->
                    val c = data.colors[i]
                    val local = target.indexOfCode(c.code)
                    if (local >= 0) {
                        local
                    } else {
                        com.pindou.app.core.color.ColorConversions.argbToLab(c.hex)
                            .let { target.nearestIndex(it) }
                            .takeIf { it >= 0 } ?: 0
                    }
                }
                val pages = data.pages.map { page ->
                    IntArray(page.size) { i -> if (page[i] >= 0) colorIndex[page[i]] else -1 }
                }
                repository.importProject(
                    data.title.ifBlank { "导入图纸" },
                    data.width,
                    data.height,
                    target,
                    pages,
                    pages.map { BooleanArray(it.size) },
                    data.album,
                )
                withContext(Dispatchers.Main) { toast = "已导入「${data.title.ifBlank { "图纸" }}」" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { lastError = "导入失败：${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    // ---------- 项目库：筛选 / 收藏 / 专辑 / 复刻 ----------

    /** 按搜索词/收藏/专辑过滤（库页组合使用） */
    fun filteredProjects(all: List<ProjectEntity>): List<ProjectEntity> {
        val q = libraryQuery.trim()
        return all.filter { p ->
            (q.isEmpty() || p.name.contains(q, ignoreCase = true)) &&
                (!libraryFavOnly || p.fav) &&
                (libraryAlbum == null || p.album == libraryAlbum)
        }
    }

    /** 已用到的专辑名（供筛选 chips） */
    fun albumNames(all: List<ProjectEntity>): List<String> =
        all.map { it.album }.filter { it.isNotBlank() }.distinct()

    fun setFavProject(entity: ProjectEntity, fav: Boolean) {
        viewModelScope.launch { repository.setFav(entity.id, fav) }
    }

    fun setAlbumProject(entity: ProjectEntity, album: String) {
        viewModelScope.launch { repository.setAlbum(entity.id, album) }
    }

    /** 复刻项目：全量拷贝为新项目，进度清零，重新开始拼 */
    fun duplicateProject(entity: ProjectEntity) {
        viewModelScope.launch {
            try {
                val loaded = ensurePalettes()
                val palette = loaded.firstOrNull { it.brand == entity.paletteBrand } ?: loaded.first()
                val pageLen = entity.width * entity.height
                val cells = GridCodec.decodeCells(entity.cells)
                val pageCount = entity.pages.coerceAtLeast(1)
                require(cells.size == pageLen * pageCount) { "数据尺寸不符" }
                val pages = chunkInts(cells, pageLen)
                val locksFlat = GridCodec.decodeLocks(entity.locked)
                val locks = if (locksFlat.size == pageLen * pageCount) {
                    chunkBooleans(locksFlat, pageLen)
                } else {
                    pages.map { BooleanArray(pageLen) }
                }
                repository.importProject(
                    "${entity.name} 复刻",
                    entity.width,
                    entity.height,
                    palette,
                    pages,
                    locks,
                    entity.album,
                    entity.thumbnailPath,
                )
                toast = "已复刻「${entity.name}」"
            } catch (e: Exception) {
                lastError = "复刻失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    // ---------- 色号图识别 ----------

    /**
     * 导入别人做好的拼豆图纸（网格 + 每格颜色 + 色号文字）。
     *
     * 解码上限 2600px：色号图的价值全在格子里那几个字，压到 1600 以下 OCR 和
     * 取色都会糊。检测失败不拦用户——直接给一张居中的默认网格进校准页，
     * 手动调列数/格子大小也能落图。
     */
    fun onChartPicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片解码失败" }

                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2600) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: error("图片解码失败")
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                Triple(bmp, px, intArrayOf(bmp.width, bmp.height))
            }.onSuccess { (bmp, px, size) ->
                val w = size[0]
                val h = size[1]
                val detected = ChartRecognizer.detect(px, w, h)
                val grid = detected ?: ChartRecognizer.ChartGrid(
                    cols = 29,
                    rows = 29,
                    cellW = minOf(w, h) / 29.0,
                    cellH = minOf(w, h) / 29.0,
                    offsetX = (w - minOf(w, h)) / 2.0,
                    offsetY = (h - minOf(w, h)) / 2.0,
                )
                startNewProject()
                chartBitmap = bmp
                chartPixels = px
                chartW = w
                chartH = h
                chartGrid = grid
                chartDetected = detected != null
                chartAutoAligned = false
                chartThreshold = 0.55f
                chartSample = null
                importError = null
                resampleChart(debounce = false) {
                    screen = Screen.CHART_CALIBRATE
                    if (detected == null) {
                        toast = "没自动识别到网格，请手动调列数和格子大小"
                    }
                }
            }.onFailure { e ->
                importError = e.message ?: "图纸导入失败"
                toast = "图纸导入失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun chartSetCols(n: Int) {
        val g = chartGrid ?: return
        chartGrid = g.withCols(n)
        chartAutoAligned = false
        resampleChart()
    }

    fun chartSetRows(n: Int) {
        val g = chartGrid ?: return
        chartGrid = g.withRows(n)
        chartAutoAligned = false
        resampleChart()
    }

    fun chartSetCellSize(px: Float) {
        val g = chartGrid ?: return
        chartGrid = g.withCellSize(px.toDouble())
        chartAutoAligned = false
        resampleChart()
    }

    /** 网格整体挪一格（不是一像素）：手指拖不准时用按钮对齐 */
    fun chartNudge(dx: Int, dy: Int) {
        val g = chartGrid ?: return
        chartGrid = g.copy(
            offsetX = g.offsetX + dx * g.cellW,
            offsetY = g.offsetY + dy * g.cellH,
        )
        chartAutoAligned = false
        resampleChart()
    }

    /** 手指拖拽：按屏幕像素直接挪网格（换算在 UI 层做） */
    fun chartMoveBy(dx: Float, dy: Float) {
        val g = chartGrid ?: return
        chartGrid = g.copy(offsetX = g.offsetX + dx, offsetY = g.offsetY + dy)
        chartAutoAligned = false
        resampleChart()
    }

    fun chartSetThreshold(v: Float) {
        chartThreshold = v.coerceIn(0.2f, 0.95f)
        resampleChart()
    }

    /** 智能对齐：在梯度曲线上把网格线贴到真实网格线上 */
    fun chartRefine() {
        val px = chartPixels ?: return
        val w = chartW
        val h = chartH
        val g = chartGrid ?: return
        if (chartRefineRunning) return
        chartRefineRunning = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val refined = ChartRecognizer.refine(px, w, h, g)
                withContext(Dispatchers.Main) {
                    chartGrid = refined
                    chartAutoAligned = true
                    toast = "已按网格线对齐"
                }
                resampleChart()
            } finally {
                withContext(Dispatchers.Main) { chartRefineRunning = false }
            }
        }
    }

    /**
     * 重新采样 + 生成预览缩略图。网格每动一次都跑，属于热路径：
     * 采样 O(格数 × 格内像素/步长)，400×400 格在 2000px 图上是 16 万次取色，
     * 必须丢到 Default 线程。token 保证只有最后一次结果能落到 state。
     */
    private fun resampleChart(debounce: Boolean = true, onDone: (() -> Unit)? = null) {
        val px = chartPixels ?: return
        val g = chartGrid ?: return
        val w = chartW
        val h = chartH
        val th = chartThreshold
        chartToken++
        val token = chartToken
        chartBusy = true
        viewModelScope.launch(Dispatchers.Default) {
            if (debounce) delay(90)
            if (token != chartToken) return@launch
            val sample = ChartRecognizer.sample(px, w, h, g, th)
            withContext(Dispatchers.Main) {
                if (token != chartToken) return@withContext
                chartSample = sample
                chartBusy = false
                onDone?.invoke()
            }
        }
    }

    /** 校准完落图纸：采样色 → 色卡最近色 → 预览页 */
    fun chartToPattern() {
        val sample = chartSample ?: return
        val palette = selectedPalette ?: return
        if (sample.filledCells == 0) {
            toast = "这组网格没采到颜色，调一下列数或格子大小"
            return
        }
        genState = GenState.Running(GenerationStage.MAP, 0.4f)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = ChartRecognizer.toPattern(sample, palette)
                genState = GenState.Done(result)
                saveAfterGenerate(result)
                withContext(Dispatchers.Main) { screen = Screen.PREVIEW }
            } catch (e: Exception) {
                genState = GenState.Error(e.message ?: "图纸生成失败")
                withContext(Dispatchers.Main) { toast = "图纸生成失败：${e.message}" }
            }
        }
    }

    // ---------- 内部工具 ----------

    /** 色卡按需加载（首次打开项目/复刻/导入时可能尚未就绪） */
    private suspend fun ensurePalettes(): List<BeadPalette> {
        if (palettes.isNotEmpty()) return palettes
        val loaded = withContext(Dispatchers.IO) { PaletteAssets.loadAll(getApplication()) }
        if (loaded.isNotEmpty()) {
            palettes = loaded
            if (selectedPalette == null) {
                selectedPalette = loaded.firstOrNull { it.brand == "MARD" } ?: loaded.first()
            }
        }
        return loaded
    }

    private fun chunkInts(arr: IntArray, size: Int): List<IntArray> =
        List((arr.size + size - 1) / size) { i ->
            arr.copyOfRange(i * size, minOf((i + 1) * size, arr.size))
        }

    private fun chunkBooleans(arr: BooleanArray, size: Int): List<BooleanArray> =
        List((arr.size + size - 1) / size) { i ->
            arr.copyOfRange(i * size, minOf((i + 1) * size, arr.size))
        }
}
