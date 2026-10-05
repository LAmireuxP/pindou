package com.pindou.app.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 项目：图纸的可再编辑存档 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val width: Int,
    val height: Int,
    val beadSize: String,
    val paletteBrand: String,
    /** 每格色卡索引，int32 小端序列化（-1 = 空格） */
    val cells: ByteArray,
    /** 每格锁定标记，1 字节一格 */
    val locked: ByteArray,
    val thumbnailPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** 施工进度：每格 1 字节（1 = 已拼） */
    val doneCells: ByteArray? = null,
    /** 施工引导方向：0 = 按行，1 = 按列 */
    val buildOrientation: Int = 0,
    /** 当前施工行/列号 */
    val buildCursor: Int = 0,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Query("UPDATE projects SET cells = :cells, locked = :locked, thumbnailPath = :thumb, updatedAt = :now WHERE id = :id")
    suspend fun updateContent(id: Long, cells: ByteArray, locked: ByteArray, thumb: String?, now: Long)

    @Query("UPDATE projects SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE projects SET doneCells = :done, buildOrientation = :orientation, buildCursor = :cursor, updatedAt = :now WHERE id = :id")
    suspend fun updateBuildProgress(id: Long, done: ByteArray, orientation: Int, cursor: Int, now: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [ProjectEntity::class], version = 2, exportSchema = false)
abstract class PindouDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        /** v1 → v2：新增施工进度三列（老数据保持默认值） */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN doneCells BLOB")
                db.execSQL("ALTER TABLE projects ADD COLUMN buildOrientation INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE projects ADD COLUMN buildCursor INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

/** IntArray / BooleanArray 与字节数组互转（仅数据层使用） */
object GridCodec {

    fun encodeCells(cells: IntArray): ByteArray {
        val buf = ByteBuffer.allocate(cells.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (c in cells) buf.putInt(c)
        return buf.array()
    }

    fun decodeCells(bytes: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(bytes.size / 4) { buf.getInt() }
    }

    fun encodeLocks(locks: BooleanArray): ByteArray =
        ByteArray(locks.size) { if (locks[it]) 1 else 0 }

    fun decodeLocks(bytes: ByteArray): BooleanArray =
        BooleanArray(bytes.size) { bytes[it] != 0.toByte() }
}