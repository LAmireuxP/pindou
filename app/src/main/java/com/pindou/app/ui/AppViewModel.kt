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
enum class Screen { HOME, PARAMS, PREVIEW, EDITOR, CONSTRUCTION }

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
                imagePreview = bmp
                imagePixels = pixels
                imageW = size[0]
                imageH = size[1]
                importError = null
                genState = GenState.Idle
                screen = Screen.PARAMS
            }.onFailure { e ->
                importError = e.message ?: "导入失败"
            }
        }
    }

    fun selectPalette(palette: BeadPalette) {
        selectedPalette = palette
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
                    ),
                    palette,
                ) { stage, progress ->
                    genState = GenState.Running(stage, progress)
                }
                genState = GenState.Done(result)
                autoSaveAfterGenerate(result)
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
            screen = Screen.HOME
            true
        }
        Screen.HOME -> false
    }

    fun backToParams() {
        screen = Screen.PARAMS
    }

    /** 生成完成后自动建档保存 */
    private suspend fun autoSaveAfterGenerate(result: PatternResult) {
        if (currentProjectId != null) return
        val id = repository.create(projectName, result)
        currentProjectId = id
        toast = "已保存到项目库"
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
        genState = GenState.Running(GenerationStage.DOWNSAMPLE, 0f)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val bitmap = TextRasterizer.rasterize(text, gridW.coerceIn(5, 200), gridH.coerceIn(5, 200))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val result = PatternGenerator.generate(
                    pixels, bitmap.width, bitmap.height,
                    GeneratorOptions(
                        gridW = gridW.coerceIn(5, 200),
                        gridH = gridH.coerceIn(5, 200),
                        maxColors = if (limitColors) maxColors.coerceIn(1, 64) else null,
                        cleanupEnabled = true,
                        ditheringEnabled = false,
                    ),
                    palette,
                ) { stage, progress ->
                    genState = GenState.Running(stage, progress)
                }
                bitmap.recycle()
                genState = GenState.Done(result)
                autoSaveAfterGenerate(result)
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
                repository.updateContent(
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
                currentProjectId = repository.create(projectName, updated, engine.locked)
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
