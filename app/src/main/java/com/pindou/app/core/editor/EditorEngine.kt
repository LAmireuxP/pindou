package com.pindou.app.core.editor

/**
 * 图纸编辑引擎：格子数据 + 锁定标记 + 撤销/重做命令栈。纯 Kotlin，可脱离 Android 单测。
 *
 * cells 每格为色卡索引（-1 = 空格）；锁定格不受绘制/擦除影响。
 * 撤销栈只覆盖颜色变更；锁定/解锁是辅助操作，直接生效不入栈。
 */
class EditorEngine(
    val width: Int,
    val height: Int,
    initialCells: IntArray,
) {
    init {
        require(initialCells.size == width * height) { "cells 尺寸与网格不符" }
    }

    private val _cells = initialCells.copyOf()
    val cells: IntArray get() = _cells

    val locked = BooleanArray(width * height)

    private val undoStack = ArrayDeque<List<Change>>()
    private val redoStack = ArrayDeque<List<Change>>()

    /** 每次数据变更 +1，UI 层用它触发重绘 */
    var revision: Int = 0
        private set

    data class Change(val index: Int, val old: Int, val new: Int)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 一次连续绘制手势：聚合格子变更，抬手时入撤销栈 */
    inner class StrokeSession {
        internal val changes = LinkedHashMap<Int, Change>()

        /** 设置格子颜色；格子被锁定或颜色相同返回 false */
        fun setCell(index: Int, newColor: Int): Boolean {
            if (index !in _cells.indices) return false
            if (locked[index]) return false
            val old = _cells[index]
            if (old == newColor) return false
            _cells[index] = newColor
            changes.putIfAbsent(index, Change(index, old, newColor))
            return true
        }
    }

    fun startStroke(): StrokeSession = StrokeSession()

    /** 结束手势：有实际变更则入撤销栈并清空重做栈 */
    fun endStroke(session: StrokeSession) {
        if (session.changes.isNotEmpty()) {
            undoStack.addLast(session.changes.values.toList())
            if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
            redoStack.clear()
            revision++
        }
    }

    /** 锁定/解锁单格（不入撤销栈） */
    fun setLocked(index: Int, value: Boolean): Boolean {
        if (index !in locked.indices || locked[index] == value) return false
        locked[index] = value
        revision++
        return true
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val changes = undoStack.removeLast()
        for (c in changes.asReversed()) _cells[c.index] = c.old
        redoStack.addLast(changes)
        revision++
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val changes = redoStack.removeLast()
        for (c in changes) _cells[c.index] = c.new
        undoStack.addLast(changes)
        revision++
        return true
    }

    companion object {
        private const val MAX_HISTORY = 100
    }
}
