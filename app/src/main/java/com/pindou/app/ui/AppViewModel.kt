package com.pindou.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
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
import com.pindou.app.core.image.CropRect
import com.pindou.app.core.image.ImageTransforms
import com.pindou.app.core.pattern.GenerationStage
import com.pindou.app.core.pattern.GeneratorOptions
import com.pindou.app.core.pattern.PatternGenerator
import com.pindou.app.core.pattern.PatternResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面导航（线性流程，M5 引入项目库后再考虑导航库） */
enum class Screen { HOME, CROP, PARAMS, PREVIEW, EDITOR, CONSTRUCTION }

/** 编辑工具 */
enum class EditorTool { PAINT, ERASE, PICKER }

/** 生成过程中的 UI 状态 */
sealed interface GenState {
    data object Idle : GenState
    data class Running(val stage: GenerationStage, val progress: Float) : GenState
    data class Done(val result: PatternResult) : GenState
    data class Error(val message: String) : GenState
}

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

    /** 裁剪框（工作图像素坐标系），null = 不在裁剪流程中 */
    var cropRect by mutableStateOf<CropRect?>(null)
        private set

    /** 几何变换执行中标志（防止连点导致的状态竞争） */
    private var transformRunning = false

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
    var limitColors by mutableStateOf(false)
    var maxColors by mutableIntStateOf(24)
    var dithering by mutableStateOf(false)
    var removeBackground by mutableStateOf(false)
    var backgroundTolerance by mutableFloatStateOf(12f)
    var contentMode by mutableStateOf(com.pindou.app.core.pattern.ContentSimplifier.Mode.OFF)

    var genState by mutableStateOf<GenState>(GenState.Idle)
        private set

    // 施工模式
    var construction by mutableStateOf<ConstructionState?>(null)

    // 编辑器
    var editor by mutableStateOf<EditorEngine?>(null)
        private set
    var editorTool by mutableStateOf(EditorTool.PAINT)
    var editorColor by mutableIntStateOf(0) // palette 索引
    var editorShowCodes by mutableStateOf(true)
    var editorShowGrid by mutableStateOf(true)
    var editorRevision by mutableIntStateOf(0)
        private set

    fun notifyEditorChanged() {
        editorRevision++
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
    fun onImagePicked(uri: Uri) {
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
                originalPixels = pixels
                originalW = size[0]
                originalH = size[1]
                imagePreview = bmp
                imagePixels = pixels
                imageW = size[0]
                imageH = size[1]
                cropRect = CropRect.full(size[0], size[1])
                paramsFromCrop = false
                adaptiveAnchor = 0
                adaptiveScale = 1f
                importError = null
                genState = GenState.Idle
                screen = Screen.CROP
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

    /** 进入裁剪页（参数页「重新裁剪」入口；导入后自动进入，不经此函数） */
    fun openCrop() {
        if (imagePixels == null || imageW <= 0 || imageH <= 0) return
        cropRect = CropRect.full(imageW, imageH)
        screen = Screen.CROP
    }

    fun updateCropRect(rect: CropRect) {
        cropRect = rect
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
            withContext(Dispatchers.Main) {
                paramsFromCrop = true
                screen = Screen.PARAMS
            }
        }
    }

    fun selectPalette(palette: BeadPalette) {
        selectedPalette = palette
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

    private fun recomputeAdaptive() {
        val (baseW, baseH) = ImageTransforms.adaptiveGridSize(imageW, imageH, adaptiveAnchor)
        val (w, h) = ImageTransforms.scaledGridSize(baseW, baseH, adaptiveScale.toDouble())
        gridW = w
        gridH = h
    }

    fun generate() {
        val pixels = imagePixels ?: return
        val palette = selectedPalette ?: return
        genState = GenState.Running(GenerationStage.DOWNSAMPLE, 0f)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = PatternGenerator.generate(
                    pixels, imageW, imageH,
                    GeneratorOptions(
                        gridW = gridW.coerceIn(5, 200),
                        gridH = gridH.coerceIn(5, 200),
                        maxColors = if (limitColors) maxColors.coerceIn(1, 64) else null,
                        cleanupEnabled = true,
                        ditheringEnabled = dithering,
                        removeBackground = removeBackground,
                        backgroundTolerance = backgroundTolerance.toDouble(),
                        contentMode = contentMode,
                    ),
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
        Screen.HOME -> false
    }

    fun backToParams() {
        screen = Screen.PARAMS
    }

    /** 存档用名称：参数页可自定义，留空回退默认名 */
    private fun displayName(): String = projectName.trim().ifBlank { "未命名图纸" }

    /** 生成完成后自动建档/更新保存 */
    private suspend fun saveAfterGenerate(result: PatternResult) {
        val id = currentProjectId
        if (id != null) {
            repository.updateContent(
                id, result.width, result.height,
                result.cells, BooleanArray(result.cells.size), result.palette,
            )
            toast = "修改已保存"
        } else {
            currentProjectId = repository.create(displayName(), result)
            projectName = displayName()
            toast = "已保存到项目库"
        }
    }

    /** 空白画布：直接创建空网格进编辑器 */
    fun startBlankCanvas() {
        val palette = selectedPalette ?: return
        val w = gridW.coerceIn(5, 200)
        val h = gridH.coerceIn(5, 200)
        val cells = IntArray(w * h) { -1 }
        genState = GenState.Done(PatternResult(w, h, cells, palette, 0))
        editor = EditorEngine(w, h, cells)
        currentProjectId = null
        projectName = "空白画布 ${w}×${h}"
        editorRevision++
        screen = Screen.EDITOR
    }

    /** 文字拼豆：文字栅格化后走常规管线 */
    fun generateFromText(text: String) {
        val palette = selectedPalette ?: return
        if (text.isBlank()) return
        textSource = text
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
                        contentMode = contentMode,
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
                if (cells.size != entity.width * entity.height) {
                    lastError = "打开失败：数据尺寸不符（${cells.size} vs ${entity.width * entity.height}）"
                    return@launch
                }
                val locks = GridCodec.decodeLocks(entity.locked)
                val result = PatternResult(entity.width, entity.height, cells, palette, 0)
                val engine = EditorEngine(entity.width, entity.height, cells)
                locks.forEachIndexed { i, b -> if (i < engine.locked.size && b) engine.setLocked(i, true) }
                genState = GenState.Done(result)
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
        editor = EditorEngine(result.width, result.height, result.cells)
        editorRevision++
        screen = Screen.EDITOR
    }

    /** 完成编辑：写回当前结果并持久化到项目库 */
    fun finishEditor() {
        val engine = editor ?: return
        val current = (genState as? GenState.Done)?.result ?: return
        val updated = current.copy(cells = engine.cells.copyOf())
        genState = GenState.Done(updated)
        editor = null
        val id = currentProjectId
        if (id != null) {
            viewModelScope.launch {
                // 编辑是局部修改：保留施工进度，只写回格数据与锁
                repository.updateCells(
                    id = id,
                    width = updated.width,
                    height = updated.height,
                    cells = updated.cells,
                    locked = engine.locked,
                    palette = updated.palette,
                )
                toast = "修改已保存"
            }
        } else {
            viewModelScope.launch {
                currentProjectId = repository.create(displayName(), updated, engine.locked)
            }
        }
        screen = Screen.PREVIEW
    }

    /** 进入施工模式（载入已保存的进度） */
    fun enterConstruction() {
        val result = (genState as? GenState.Done)?.result ?: return
        viewModelScope.launch {
            val entity = currentProjectId?.let { repository.get(it) }
            val done = entity?.doneCells?.let { bytes ->
                BooleanArray(bytes.size) { bytes[it] != 0.toByte() }
            } ?: BooleanArray(result.cells.size)
            construction = ConstructionState(
                width = result.width,
                height = result.height,
                done = done,
                orientation = if ((entity?.buildOrientation ?: 0) == 1) {
                    ConstructionState.Orientation.COLUMN
                } else {
                    ConstructionState.Orientation.ROW
                },
                cursor = entity?.buildCursor ?: 0,
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
}
