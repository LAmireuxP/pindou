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
            .addMigrations(PindouDatabase.MIGRATION_1_2, PindouDatabase.MIGRATION_2_3)
            .build()
    }

    private val dao get() = db.projectDao()

    fun observeAll(): Flow<List<ProjectEntity>> = dao.observeAll()

    /** 项目库列表：不读格数据 BLOB，只取卡片字段 */
    fun observeSummaries(): Flow<List<ProjectSummary>> = dao.observeSummaries()

    suspend fun get(id: Long): ProjectEntity? = withContext(Dispatchers.IO) { dao.getById(id) }

    /** 新建项目（生成图纸后调用），返回项目 id */
    suspend fun create(
        name: String,
        result: PatternResult,
        locked: BooleanArray? = null,
        pages: Int = 1,
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
                pages = pages,
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
        pages: Int = 1,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // cells 为多页平铺数据时，缩略图只渲染第 1 页
        val page0 = if (pages > 1 && cells.size > width * height) {
            cells.copyOfRange(0, width * height)
        } else cells
        val thumb = renderThumbnail(PatternResult(width, height, page0, palette, 0))
        val old = dao.getById(id)?.thumbnailPath
        dao.updateCells(id, GridCodec.encodeCells(cells), GridCodec.encodeLocks(locked), pages, thumb, now)
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

    /** 收藏/取消收藏 */
    suspend fun setFav(id: Long, fav: Boolean) = withContext(Dispatchers.IO) {
        dao.setFav(id, fav, System.currentTimeMillis())
    }

    /** 移入/移出专辑（空串 = 未分组） */
    suspend fun setAlbum(id: Long, album: String) = withContext(Dispatchers.IO) {
        dao.setAlbum(id, album.trim(), System.currentTimeMillis())
    }

    /** 保存参考原图路径 */
    suspend fun setRefPath(id: Long, path: String?) = withContext(Dispatchers.IO) {
        dao.setRefPath(id, path, System.currentTimeMillis())
    }

    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) {
        dao.rename(id, name, System.currentTimeMillis())
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val project = dao.getById(id)
        project?.thumbnailPath?.let { runCatching { File(it).delete() } }
        dao.delete(id)
    }

    /**
     * 导入外部图纸（.bpat）或复刻项目：全量新建一个实体。
     * 复刻时缩略图复制一份独立文件，避免删除原项目时把复刻的图也删掉。
     */
    suspend fun importProject(
        name: String,
        width: Int,
        height: Int,
        palette: BeadPalette,
        pagesCells: List<IntArray>,
        pagesLocks: List<BooleanArray>,
        album: String = "",
        thumbnailCopyFrom: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        require(pagesCells.isNotEmpty()) { "页数据为空" }
        val now = System.currentTimeMillis()
        val flat = pagesCells.reduce { acc, cur -> acc + cur }
        val flatLocks = pagesLocks.reduce { acc, cur -> acc + cur }
        val thumb = thumbnailCopyFrom?.let { src ->
            runCatching {
                val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
                val dst = File(dir, "thumb_${System.currentTimeMillis()}.png")
                File(src).copyTo(dst, overwrite = true)
                dst.absolutePath
            }.getOrNull()
        } ?: renderThumbnail(PatternResult(width, height, pagesCells[0], palette, 0))
        dao.insert(
            ProjectEntity(
                name = name,
                width = width,
                height = height,
                beadSize = palette.beadSize.name,
                paletteBrand = palette.brand,
                cells = GridCodec.encodeCells(flat),
                locked = GridCodec.encodeLocks(flatLocks),
                thumbnailPath = thumb,
                createdAt = now,
                updatedAt = now,
                pages = pagesCells.size,
                album = album,
            ),
        )
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
