package com.pindou.app.core.editor

/**
 * 图纸编辑引擎：多页格子数据 + 逐页锁定标记 + 撤销/重做命令栈。纯 Kotlin，可脱离 Android 单测。
 *
 * - cells 每格为色卡索引（-1 = 空格）；锁定格不受绘制/擦除影响；锁定按页独立。
 * - 页操作：[addPage]（可复制当前页）/ [removePage] / [setPage] / [resize]（居中保留内容）。
 * - 撤销栈两类条目：普通笔触只记格子变更；结构操作（增删页/改尺寸）整帧快照。
 *   快照条目较占内存（全页拷贝），历史栈上限 50 步兜底，常规 1~5 页图纸占用可控。
 */
class EditorEngine(
    initialWidth: Int,
    initialHeight: Int,
    initialPages: List<IntArray>,
    initialLocks: List<BooleanArray>? = null,
    initialPage: Int = 0,
) {
    /** 兼容旧调用：单页构造 */
    constructor(width: Int, height: Int, initialCells: IntArray) : this(
        width, height, listOf(initialCells),
    )

    var width: Int = initialWidth
        private set
    var height: Int = initialHeight
        private set

    init {
        require(initialWidth > 0 && initialHeight > 0) { "网格尺寸必须为正" }
        require(initialPages.isNotEmpty()) { "至少一页" }
        initialPages.forEach { require(it.size == initialWidth * initialHeight) { "页数据尺寸与网格不符" } }
        initialLocks?.forEach { require(it.size == initialWidth * initialHeight) { "锁定数据尺寸与网格不符" } }
    }

    private val _pages = initialPages.map { it.copyOf() }.toMutableList()
    private val _locks = initialLocks?.map { it.copyOf() }?.toMutableList()
        ?: MutableList(_pages.size) { BooleanArray(initialWidth * initialHeight) }

    /** 各页活动数组：调用方只读，改写必须走引擎方法 */
    val pages: List<IntArray> get() = _pages
    val locks: List<BooleanArray> get() = _locks
    val pageCount: Int get() = _pages.size

    /** 当前页索引 */
    var pageIndex: Int = initialPage.coerceIn(0, _pages.size - 1)
        private set

    /** 当前页格子数据 */
    val cells: IntArray get() = _pages[pageIndex]

    /** 当前页锁定标记 */
    val locked: BooleanArray get() = _locks[pageIndex]

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    /** 每次数据变更 +1，UI 层用它触发重绘 */
    var revision: Int = 0
        private set

    data class Change(val index: Int, val old: Int, val new: Int)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private sealed interface Entry

    /** 一次连续绘制手势（限当前页）：聚合格子变更 */
    private data class CellChangeEntry(val page: Int, val changes: List<Change>) : Entry

    /** 结构操作（增删页/改尺寸）：整帧快照，撤销时整体还原 */
    private class PageSnapshotEntry(
        val beforeWidth: Int,
        val beforeHeight: Int,
        val beforePages: List<IntArray>,
        val beforeLocks: List<BooleanArray>,
        val beforeIndex: Int,
        val afterWidth: Int,
        val afterHeight: Int,
        val afterPages: List<IntArray>,
        val afterLocks: List<BooleanArray>,
        val afterIndex: Int,
    ) : Entry

    /** 一次连续绘制手势：聚合格子变更，抬手时入撤销栈 */
    inner class StrokeSession {
        internal val changes = LinkedHashMap<Int, Change>()

        /** 设置当前页格子颜色；格子被锁定或颜色相同返回 false */
        fun setCell(index: Int, newColor: Int): Boolean {
            if (index !in cells.indices) return false
            if (locked[index]) return false
            val old = cells[index]
            if (old == newColor) return false
            cells[index] = newColor
            changes.putIfAbsent(index, Change(index, old, newColor))
            return true
        }
    }

    fun startStroke(): StrokeSession = StrokeSession()

    /** 结束手势：有实际变更则入撤销栈并清空重做栈 */
    fun endStroke(session: StrokeSession) {
        if (session.changes.isNotEmpty()) {
            undoStack.addLast(CellChangeEntry(pageIndex, session.changes.values.toList()))
            trimHistory()
            redoStack.clear()
            revision++
        }
    }

    /**
     * 油漆桶：从 [index] 出发按 4 连通填充同色区域为 [newColor]（含空格区域）。
     * 锁定格不改色但参与连通传播（保持区域的视觉连续性）。
     * 整个填充作为一条撤销记录。返回是否有变更。
     */
    fun floodFill(index: Int, newColor: Int): Boolean {
        if (index !in cells.indices) return false
        if (locked[index]) return false
        val origin = cells[index]
        if (origin == newColor) return false
        val visited = BooleanArray(width * height)
        val queue = ArrayDeque<Int>()
        queue.add(index)
        visited[index] = true
        val session = startStroke()
        val w = width
        val h = height
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            // 只写未锁定格；锁定格颜色不变但区域继续扩散
            if (!locked[cur]) session.setCell(cur, newColor)
            val x = cur % w
            val y = cur / w
            if (x > 0 && !visited[cur - 1] && cells[cur - 1] == origin) {
                visited[cur - 1] = true; queue.add(cur - 1)
            }
            if (x < w - 1 && !visited[cur + 1] && cells[cur + 1] == origin) {
                visited[cur + 1] = true; queue.add(cur + 1)
            }
            if (y > 0 && !visited[cur - w] && cells[cur - w] == origin) {
                visited[cur - w] = true; queue.add(cur - w)
            }
            if (y < h - 1 && !visited[cur + w] && cells[cur + w] == origin) {
                visited[cur + w] = true; queue.add(cur + w)
            }
        }
        endStroke(session)
        return session.changes.isNotEmpty()
    }

    /** 锁定/解锁当前页单格（不入撤销栈） */
    fun setLocked(index: Int, value: Boolean): Boolean {
        val locks = locked
        if (index !in locks.indices || locks[index] == value) return false
        locks[index] = value
        revision++
        return true
    }

    /** 切换页；索引越界钳制到有效范围 */
    fun setPage(index: Int) {
        pageIndex = index.coerceIn(0, _pages.size - 1)
    }

    /** 新增一页（全空或复制当前页），并切换到新页 */
    fun addPage(copyCurrent: Boolean = false) {
        val beforePages = snapshotPages()
        val beforeLocks = snapshotLocks()
        val beforeIndex = pageIndex
        val newCells = if (copyCurrent) cells.copyOf() else IntArray(width * height) { -1 }
        val newLocks = if (copyCurrent) locked.copyOf() else BooleanArray(width * height)
        val targetIndex = _pages.size
        _pages.add(newCells)
        _locks.add(newLocks)
        pageIndex = targetIndex
        pushSnapshot(beforePages, beforeLocks, beforeIndex, targetIndex)
    }

    /** 删除一页；至少保留一页 */
    fun removePage(index: Int): Boolean {
        if (_pages.size <= 1 || index !in _pages.indices) return false
        val beforePages = snapshotPages()
        val beforeLocks = snapshotLocks()
        val beforeIndex = pageIndex
        _pages.removeAt(index)
        _locks.removeAt(index)
        pageIndex = beforeIndex.coerceAtMost(_pages.size - 1)
        pushSnapshot(beforePages, beforeLocks, beforeIndex, pageIndex)
        return true
    }

    /**
     * 改尺寸：每页内容按中心对齐裁剪/留白，锁定标记同步映射。
     * 所有页一起变（多页图纸必须同尺寸，导出/施工按统一网格处理）。
     */
    fun resize(newWidth: Int, newHeight: Int) {
        require(newWidth in 1..200 && newHeight in 1..200) { "尺寸超出范围" }
        if (newWidth == width && newHeight == height) return
        val beforePages = snapshotPages()
        val beforeLocks = snapshotLocks()
        val beforeIndex = pageIndex
        val oldWidth = width
        val oldHeight = height
        val dx = (newWidth - oldWidth) / 2
        val dy = (newHeight - oldHeight) / 2
        val newPages = _pages.map { page ->
            IntArray(newWidth * newHeight) { -1 }.also { out ->
                for (y in 0 until oldHeight) {
                    val ny = y + dy
                    if (ny < 0 || ny >= newHeight) continue
                    for (x in 0 until oldWidth) {
                        val nx = x + dx
                        if (nx < 0 || nx >= newWidth) continue
                        out[ny * newWidth + nx] = page[y * oldWidth + x]
                    }
                }
            }
        }
        val newLocks = _locks.map { lock ->
            BooleanArray(newWidth * newHeight).also { out ->
                for (y in 0 until oldHeight) {
                    val ny = y + dy
                    if (ny < 0 || ny >= newHeight) continue
                    for (x in 0 until oldWidth) {
                        val nx = x + dx
                        if (nx < 0 || nx >= newWidth) continue
                        out[ny * newWidth + nx] = lock[y * oldWidth + x]
                    }
                }
            }
        }
        _pages.clear(); _pages.addAll(newPages)
        _locks.clear(); _locks.addAll(newLocks)
        width = newWidth
        height = newHeight
        pageIndex = beforeIndex.coerceAtMost(_pages.size - 1)
        pushSnapshot(beforePages, beforeLocks, beforeIndex, pageIndex)
    }

    fun undo(): Boolean {
        when (val entry = undoStack.removeLastOrNull() ?: return false) {
            is CellChangeEntry -> {
                val page = _pages[entry.page.coerceIn(0, _pages.size - 1)]
                for (c in entry.changes.asReversed()) page[c.index] = c.old
                redoStack.addLast(entry)
            }
            is PageSnapshotEntry -> {
                restore(entry.beforeWidth, entry.beforeHeight, entry.beforePages, entry.beforeLocks, entry.beforeIndex)
                redoStack.addLast(entry)
            }
        }
        revision++
        return true
    }

    fun redo(): Boolean {
        when (val entry = redoStack.removeLastOrNull() ?: return false) {
            is CellChangeEntry -> {
                val page = _pages[entry.page.coerceIn(0, _pages.size - 1)]
                for (c in entry.changes) page[c.index] = c.new
                undoStack.addLast(entry)
            }
            is PageSnapshotEntry -> {
                restore(entry.afterWidth, entry.afterHeight, entry.afterPages, entry.afterLocks, entry.afterIndex)
                undoStack.addLast(entry)
            }
        }
        revision++
        return true
    }

    // ---------- 内部工具 ----------

    private fun snapshotPages(): List<IntArray> = _pages.map { it.copyOf() }

    private fun snapshotLocks(): List<BooleanArray> = _locks.map { it.copyOf() }

    private fun pushSnapshot(
        beforePages: List<IntArray>,
        beforeLocks: List<BooleanArray>,
        beforeIndex: Int,
        afterIndex: Int,
    ) {
        undoStack.addLast(
            PageSnapshotEntry(
                width, height, beforePages, beforeLocks, beforeIndex,
                width, height, snapshotPages(), snapshotLocks(), afterIndex,
            ),
        )
        trimHistory()
        redoStack.clear()
        revision++
    }

    private fun restore(width0: Int, height0: Int, pages0: List<IntArray>, locks0: List<BooleanArray>, index0: Int) {
        _pages.clear(); _pages.addAll(pages0.map { it.copyOf() })
        _locks.clear(); _locks.addAll(locks0.map { it.copyOf() })
        width = width0
        height = height0
        pageIndex = index0.coerceIn(0, _pages.size - 1)
    }

    private fun trimHistory() {
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
    }

    companion object {
        private const val MAX_HISTORY = 50
    }
}
