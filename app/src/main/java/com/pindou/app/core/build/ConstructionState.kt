package com.pindou.app.core.build

/**
 * 施工模式状态：纯 Kotlin，可单测。
 *
 * done 是活动数组：UI 直接原地改写（不重建对象），配合 [revision] 计数触发 Compose 重绘；
 * 持久化方（AppViewModel.saveConstructionProgress）每次操作后读取同一数组落库。
 * 约定：进度仅覆盖第 1 页（多页图纸按页拆分施工由调用方扩展）。
 */
class ConstructionState(
    val width: Int,
    val height: Int,
    val done: BooleanArray,
    /** 引导方向；UI 可切换（按行/按列） */
    var orientation: Orientation = Orientation.ROW,
    /** 当前行/列号（按 [orientation] 解释），引导视线 */
    var cursor: Int = 0,
) {
    enum class Orientation { ROW, COLUMN }

    /** 数据变更计数：UI 观察它触发重绘（BooleanArray 原地改写无法被 Compose 察觉） */
    var revision: Int = 0
        private set

    init {
        require(done.size == width * height) { "进度数组尺寸与网格不符" }
    }

    val total: Int get() = width * height

    val doneCount: Int get() = done.count { it }

    /** 完成比例 0~1 */
    val progress: Float get() = if (total == 0) 0f else doneCount.toFloat() / total

    private fun bump() {
        revision++
    }

    /** 切换单格完成状态 */
    fun toggle(index: Int) {
        if (index !in done.indices) return
        done[index] = !done[index]
        bump()
    }

    /** 设置单格完成状态；无变化返回 false */
    fun setDone(index: Int, value: Boolean): Boolean {
        if (index !in done.indices || done[index] == value) return false
        done[index] = value
        bump()
        return true
    }

    /** 整行标为完成/未完成，返回实际改动的格数 */
    fun setRowDone(row: Int, value: Boolean): Int {
        if (row !in 0 until height) return 0
        var changed = 0
        for (x in 0 until width) {
            if (setDone(row * width + x, value)) changed++
        }
        return changed
    }

    /** 整列标为完成/未完成，返回实际改动的格数 */
    fun setColDone(col: Int, value: Boolean): Int {
        if (col !in 0 until width) return 0
        var changed = 0
        for (y in 0 until height) {
            if (setDone(y * width + col, value)) changed++
        }
        return changed
    }

    /** 第一个还有未完成格的行号；全部完成返回 -1 */
    fun nextIncompleteRow(): Int {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!done[y * width + x]) return y
            }
        }
        return -1
    }

    /** 第一个还有未完成格的列号；全部完成返回 -1 */
    fun nextIncompleteCol(): Int {
        for (x in 0 until width) {
            for (y in 0 until height) {
                if (!done[y * width + x]) return x
            }
        }
        return -1
    }

    /** 按当前方向跳到下一个未完成行/列，并更新游标；返回是否找到 */
    fun jumpToNextIncomplete(): Boolean {
        val next = when (orientation) {
            Orientation.ROW -> nextIncompleteRow()
            Orientation.COLUMN -> nextIncompleteCol()
        }
        if (next < 0) return false
        cursor = next
        bump()
        return true
    }

    /** 切换按行/按列引导（变更计数 +1 触发重绘）；与属性 setter 的 JVM 签名错开 */
    fun updateOrientation(value: Orientation) {
        if (orientation == value) return
        orientation = value
        bump()
    }
}
