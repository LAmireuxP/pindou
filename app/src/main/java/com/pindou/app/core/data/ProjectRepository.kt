package com.pindou.app.core.data

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import com.pindou.app.core.export.ExportRenderer
import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.pattern.PatternResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** 项目仓库：Room 持久化 + 缩略图生成 */
class ProjectRepository(private val context: Context) {

    private val db: PindouDatabase by lazy {
        Room.databaseBuilder(context, PindouDatabase::class.java, "pindou.db")
            .addMigrations(PindouDatabase.MIGRATION_1_2)
            .build()
    }

    private val dao get() = db.projectDao()

    fun observeAll(): Flow<List<ProjectEntity>> = dao.observeAll()

    suspend fun get(id: Long): ProjectEntity? = withContext(Dispatchers.IO) { dao.getById(id) }

    /** 新建项目（生成图纸后调用），返回项目 id */
    suspend fun create(
        name: String,
        result: PatternResult,
        locked: BooleanArray? = null,
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val thumb = renderThumbnail(result)
        dao.insert(
            ProjectEntity(
                name = name,
                width = result.width,
                height = result.height,
                beadSize = result.palette.beadSize.name,
                paletteBrand = result.palette.brand,
                cells = GridCodec.encodeCells(result.cells),
                locked = GridCodec.encodeLocks(locked ?: BooleanArray(result.cells.size)),
                thumbnailPath = thumb,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** 编辑器完成：只更新格数据与锁，保留施工进度（尺寸色卡不变） */
    suspend fun updateCells(
        id: Long,
        width: Int,
        height: Int,
        cells: IntArray,
        locked: BooleanArray,
        palette: BeadPalette,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val thumb = renderThumbnail(PatternResult(width, height, cells, palette, 0))
        val old = dao.getById(id)?.thumbnailPath
        dao.updateCells(id, GridCodec.encodeCells(cells), GridCodec.encodeLocks(locked), thumb, now)
        // 旧缩略图清理（新图已生成时）
        if (thumb != null && old != null && old != thumb) {
            runCatching { File(old).delete() }
        }
    }

    /** 重新生成：尺寸、色卡、格数据全量更新，施工进度作废（图纸已变） */
    suspend fun updateContent(
        id: Long,
        width: Int,
        height: Int,
        cells: IntArray,
        locked: BooleanArray,
        palette: BeadPalette,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val thumb = renderThumbnail(PatternResult(width, height, cells, palette, 0))
        val old = dao.getById(id)?.thumbnailPath
        dao.updateContent(
            id, width, height,
            palette.beadSize.name, palette.brand,
            GridCodec.encodeCells(cells), GridCodec.encodeLocks(locked), thumb, now,
        )
        // 旧缩略图清理（新图已生成时）
        if (thumb != null && old != null && old != thumb) {
            runCatching { File(old).delete() }
        }
    }

    /** 保存施工进度 */
    suspend fun saveBuildProgress(
        id: Long,
        done: BooleanArray,
        orientation: Int,
        cursor: Int,
    ) = withContext(Dispatchers.IO) {
        dao.updateBuildProgress(
            id,
            ByteArray(done.size) { if (done[it]) 1 else 0 },
            orientation,
            cursor,
            System.currentTimeMillis(),
        )
    }

    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) {
        dao.rename(id, name, System.currentTimeMillis())
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val project = dao.getById(id)
        project?.thumbnailPath?.let { runCatching { File(it).delete() } }
        dao.delete(id)
    }

    /** 生成缩略图 PNG，返回文件路径 */
    private fun renderThumbnail(result: PatternResult): String? = runCatching {
        val cellPx = when {
            result.width <= 32 -> 10
            result.width <= 64 -> 6
            else -> 4
        }
        val rendered = ExportRenderer.renderPattern(
            result,
            ExportRenderer.RenderOptions(cellPx = cellPx, showCodes = false, showGrid = false),
        )
        val bmp = Bitmap.createBitmap(rendered.width, rendered.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(rendered.pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)

        val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
        val file = File(dir, "thumb_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        bmp.recycle()
        file.absolutePath
    }.getOrNull()
}