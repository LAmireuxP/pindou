package com.pindou.app.core.pattern

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions

/**
 * 背景移除：从网格四边的格子做洪水填充，把与种子色 ΔE 在容差内的连通区域标记为空格（-1）。
 *
 * 与"抠图"不同，这里只移除**从边缘连通**的区域，图案内部的同色块会被保留
 * （例如主体里的大面积白色不会被误删）。
 */
object BackgroundRemover {

    private const val BACKGROUND = -1

    /**
     * @param grid 降采样后的 ARGB 网格
     * @param tolerance CIEDE2000 容差（建议 8~20；越大删得越多）
     * @return 与 grid 同尺寸的标记结果：true 表示该格判为背景
     */
    fun detect(
        grid: IntArray,
        width: Int,
        height: Int,
        tolerance: Double = 12.0,
    ): BooleanArray {
        val isBg = BooleanArray(grid.size)
        if (width < 3 || height < 3) return isBg

        // 种子：四条边上的所有格子
        val queue = ArrayDeque<Int>()
        val visited = BooleanArray(grid.size)

        fun seed(index: Int) {
            if (!visited[index]) {
                visited[index] = true
                queue.addLast(index)
            }
        }
        for (x in 0 until width) {
            seed(x)                       // 上边
            seed((height - 1) * width + x) // 下边
        }
        for (y in 0 until height) {
            seed(y * width)               // 左边
            seed(y * width + width - 1)   // 右边
        }

        // 每个种子用"自身颜色"作为连通判据：BFS 扩散时只吞掉与**出发种子**接近的格子
        // 简化实现：以队列中当前格的颜色为参考（局部相邻扩散），可有效贴合渐变背景
        val labCache = arrayOfNulls<FloatArray>(grid.size)
        fun labOf(i: Int): FloatArray = labCache[i] ?: ColorConversions.argbToLab(grid[i]).also { labCache[i] = it }

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            isBg[cur] = true
            val curLab = labOf(cur)
            val x = cur % width
            val y = cur / width

            fun tryNeighbor(nx: Int, ny: Int) {
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) return
                val ni = ny * width + nx
                if (visited[ni]) return
                val d = Ciede2000.deltaE(curLab, labOf(ni))
                if (d <= tolerance) {
                    visited[ni] = true
                    queue.addLast(ni)
                }
            }
            tryNeighbor(x + 1, y)
            tryNeighbor(x - 1, y)
            tryNeighbor(x, y + 1)
            tryNeighbor(x, y - 1)
        }
        return isBg
    }

    /**
     * 执行背景移除：返回新网格，背景格被置为 [BACKGROUND]（-1 色卡索引不会被使用，
     * 调用方在映射后需要把对应位置也置 -1）。
     */
    fun applyToCells(
        cells: IntArray,
        grid: IntArray,
        width: Int,
        height: Int,
        tolerance: Double = 12.0,
    ): IntArray {
        val isBg = detect(grid, width, height, tolerance)
        val out = cells.copyOf()
        for (i in isBg.indices) {
            if (isBg[i]) out[i] = BACKGROUND
        }
        return out
    }
}