package com.pindou.app.core.editor

/**
 * 方形笔刷足迹。尺寸 1/3/5 对应 1×1、3×3、5×5，边缘自动裁到网格内。
 * 纯函数式回调，避免每次移动指针都分配 IntArray。
 */
object BrushStamp {
    val sizes = intArrayOf(1, 3, 5)

    // inline：每个指针事件要按 1/2 格间距插值十几次，非 inline 的 lambda
    // 每次都会新建一个闭包对象，滑动时就是持续的分配 + GC 抖动。
    inline fun forEachCell(
        width: Int,
        height: Int,
        centerIndex: Int,
        size: Int,
        action: (Int) -> Unit,
    ) {
        if (centerIndex !in 0 until width * height) return
        val radius = (size.coerceIn(1, 9) - 1) / 2
        val cx = centerIndex % width
        val cy = centerIndex / width
        val x0 = (cx - radius).coerceAtLeast(0)
        val x1 = (cx + radius).coerceAtMost(width - 1)
        val y0 = (cy - radius).coerceAtLeast(0)
        val y1 = (cy + radius).coerceAtMost(height - 1)
        for (y in y0..y1) {
            val row = y * width
            for (x in x0..x1) action(row + x)
        }
    }
}
