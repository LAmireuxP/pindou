package com.pindou.app.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 矩形合并的正确性 + 绘制次数。
 * 合并错了会少画格子；次数不降下来，滑动上色就会掉帧。
 */
class RunRectsTest {

    private data class Rect(val x0: Int, val x1: Int, val y0: Int, val y1: Int, val c: Int)

    private fun collect(
        cells: IntArray, w: Int, h: Int,
        firstX: Int = 0, lastX: Int = w - 1,
        firstY: Int = 0, lastY: Int = h - 1,
    ): List<Rect> {
        val out = ArrayList<Rect>()
        RunRects.forEach(cells, w, firstX, lastX, firstY, lastY) { x0, x1, y0, y1, c ->
            out.add(Rect(x0, x1, y0, y1, c))
        }
        return out
    }

    /** 把矩形展开回格子颜色，用来和原始数组逐格对比 */
    private fun expand(rects: List<Rect>, w: Int, h: Int): IntArray {
        val out = IntArray(w * h) { -1 }
        for (r in rects) {
            for (y in r.y0..r.y1) for (x in r.x0..r.x1) {
                val i = y * w + x
                assertEquals("矩形重叠在 ($x,$y)", -1, out[i])
                out[i] = r.c
            }
        }
        return out
    }

    @Test
    fun `随机块状图案展开后与原始格子一致`() {
        val rnd = Random(20261110)
        val w = 37
        val h = 29
        val cells = IntArray(w * h) { -1 }
        // 随机铺 1~5 格见方的色块，模拟像素画的大色块而不是逐格噪点
        repeat(90) {
            val bw = rnd.nextInt(1, 6)
            val bh = rnd.nextInt(1, 6)
            val x0 = rnd.nextInt(0, w - bw + 1)
            val y0 = rnd.nextInt(0, h - bh + 1)
            val c = rnd.nextInt(0, 7)
            for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) cells[y * w + x] = c
        }
        val rects = collect(cells, w, h)
        assertTrue("合并结果不应为空", rects.isNotEmpty())
        assertEquals(cells.toList(), expand(rects, w, h).toList())
    }

    @Test
    fun `只统计可视区，区域外不产出矩形`() {
        val w = 20
        val h = 20
        val cells = IntArray(w * h) { 3 }
        val rects = collect(cells, w, h, firstX = 5, lastX = 9, firstY = 4, lastY = 4)
        assertEquals(1, rects.size)
        assertEquals(Rect(5, 9, 4, 4, 3), rects[0])
    }

    @Test
    fun `空格不产出矩形`() {
        val w = 10
        val h = 10
        val cells = IntArray(w * h) { -1 }
        assertTrue(collect(cells, w, h).isEmpty())
    }

    @Test
    fun `60x60 像素画的绘制次数显著低于逐格`() {
        // 一张典型拼豆图纸：底色白 + 轮廓黑 + 几块粉色，块状连通
        val w = 60
        val h = 60
        val cells = IntArray(w * h) { 0 }
        for (y in 8 until 52) for (x in 6 until 54) cells[y * w + x] = 1
        for (y in 14 until 46) for (x in 12 until 48) cells[y * w + x] = 0
        for (y in 20 until 40) for (x in 18 until 42) cells[y * w + x] = 2
        for (y in 26 until 34) for (x in 24 until 36) cells[y * w + x] = 3
        // 再撒几条 1 格宽的描边，制造不连续行
        for (y in 10 until 50) cells[y * w + (y % 2 + 7)] = 4

        val rects = collect(cells, w, h)
        val cellsTotal = w * h
        println("RUNRECTS rects=${rects.size} cells=$cellsTotal 降幅=${"%.1f".format(100.0 - rects.size * 100.0 / cellsTotal)}%")
        assertEquals(cells.toList(), expand(rects, w, h).toList())
        assertTrue("合并后矩形数应远小于格子数，实际 ${rects.size}", rects.size < cellsTotal / 4)
    }

    @Test
    fun `200x200 合并耗时不拖帧`() {
        val w = 200
        val h = 200
        val cells = IntArray(w * h) { if (it % 97 == 0) 2 else 1 }
        // 预热，避开 JIT 首次编译
        repeat(20) { collect(cells, w, h) }
        val t0 = System.nanoTime()
        val iterations = 100
        var n = 0
        repeat(iterations) { n = collect(cells, w, h).size }
        val ms = (System.nanoTime() - t0) / 1_000_000.0 / iterations
        println("RUNRECTS 200x200 合并 ${"%.3f".format(ms)}ms/次 rects=$n")
        assertTrue("200×200 单次合并应远低于一帧 8ms，实际 ${ms}ms", ms < 8.0)
    }
}
