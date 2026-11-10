package com.pindou.app.core.editor

/**
 * 把一块矩形区域内的格子压成「同色矩形」序列：行内同色游程 + 相邻行纵向合并。
 *
 * 直接用 drawRect 逐格画，60×60 就是 3600 次绘制，滑动上色时每一帧都要把
 * 整块显示列表重录一遍，帧预算全花在重复的方块上。压成矩形之后像素画通常
 * 只剩几百个（大色块会整片合并成一个），同样的画面绘制次数少一个数量级。
 *
 * 算法：逐行扫描出 (x0, x1, color) 游程；下一行游程与上一行完全一致时
 * 不落笔，等颜色变化或行结束时把累积的高度一次画出来。空格（color < 0）
 * 由调用方铺底色，这里直接跳过。
 *
 * 复杂度 O(可见格数)；每格最多读 3 次（扫游程 + 两行比较）。
 * 内存：两个 3×(宽+2) 的 IntArray 暂存游程，60 宽约 750 字节/次绘制调用。
 */
object RunRects {

    /**
     * 遍历 [firstX]..[lastX] × [firstY]..[lastY] 内的所有非空格，
     * 以「同色矩形」的形式回调。矩形边界都是闭区间。
     *
     * inline：绘制 lambda 里直接调 drawRect，不产生闭包对象。
     */
    inline fun forEach(
        cells: IntArray,
        width: Int,
        firstX: Int,
        lastX: Int,
        firstY: Int,
        lastY: Int,
        emit: (x0: Int, x1: Int, y0: Int, y1: Int, color: Int) -> Unit,
    ) {
        if (firstX > lastX || firstY > lastY) return
        val runCap = 3 * (lastX - firstX + 2)
        val prevRuns = IntArray(runCap)
        val curRuns = IntArray(runCap)
        var prevN = 0
        var prevY = Int.MIN_VALUE
        var groupTop = 0

        for (y in firstY..lastY) {
            val row = y * width
            var n = 0
            var x = firstX
            while (x <= lastX) {
                val c = cells[row + x]
                if (c < 0) {
                    x++
                    continue
                }
                var end = x
                while (end < lastX && cells[row + end + 1] == c) end++
                if (n + 3 <= runCap) {
                    curRuns[n] = x
                    curRuns[n + 1] = end
                    curRuns[n + 2] = c
                    n += 3
                }
                x = end + 1
            }

            var same = n == prevN && prevY == y - 1
            if (same) {
                var i = 0
                while (i < n) {
                    if (prevRuns[i] != curRuns[i] ||
                        prevRuns[i + 1] != curRuns[i + 1] ||
                        prevRuns[i + 2] != curRuns[i + 2]
                    ) {
                        same = false
                        break
                    }
                    i += 3
                }
            }

            if (same) {
                prevY = y
            } else {
                if (prevN > 0) {
                    var i = 0
                    while (i < prevN) {
                        emit(prevRuns[i], prevRuns[i + 1], groupTop, prevY, prevRuns[i + 2])
                        i += 3
                    }
                }
                System.arraycopy(curRuns, 0, prevRuns, 0, n)
                prevN = n
                prevY = y
                groupTop = y
            }
        }
        if (prevN > 0) {
            var i = 0
            while (i < prevN) {
                emit(prevRuns[i], prevRuns[i + 1], groupTop, prevY, prevRuns[i + 2])
                i += 3
            }
        }
    }
}
