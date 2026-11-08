package com.pindou.app.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEngineTest {

    private fun engine3x3() = EditorEngine(3, 3, IntArray(9) { -1 })

    @Test
    fun `笔触内同格多次修改只记首个旧值`() {
        val e = engine3x3()
        val s = e.startStroke()
        s.setCell(0, 5)
        s.setCell(0, 7)
        s.setCell(0, 9)
        e.endStroke(s)
        assertEquals(9, e.cells[0])
        e.undo()
        assertEquals(-1, e.cells[0]) // 恢复到笔触开始前的值，不是中间值
    }

    @Test
    fun `撤销重做往返`() {
        val e = engine3x3()
        val s = e.startStroke()
        s.setCell(1, 3)
        s.setCell(2, 4)
        e.endStroke(s)
        assertEquals(3, e.cells[1])
        assertTrue(e.undo())
        assertEquals(-1, e.cells[1])
        assertEquals(-1, e.cells[2])
        assertTrue(e.redo())
        assertEquals(3, e.cells[1])
        assertEquals(4, e.cells[2])
        assertFalse(e.redo())
    }

    @Test
    fun `锁定格不可绘制`() {
        val e = engine3x3()
        e.setLocked(4, true)
        val s = e.startStroke()
        assertFalse(s.setCell(4, 2))
        e.endStroke(s)
        assertEquals(-1, e.cells[4])
    }

    @Test
    fun `空笔触不入撤销栈`() {
        val e = engine3x3()
        val s = e.startStroke()
        // 尝试写锁定格（无效果）
        e.setLocked(0, true)
        s.setCell(0, 1)
        e.endStroke(s)
        assertFalse(e.canUndo)
        assertFalse(e.undo())
    }

    @Test
    fun `新笔触清空重做栈`() {
        val e = engine3x3()
        val s1 = e.startStroke()
        s1.setCell(0, 1)
        e.endStroke(s1)
        e.undo()
        assertTrue(e.canRedo)
        val s2 = e.startStroke()
        s2.setCell(8, 2)
        e.endStroke(s2)
        assertFalse(e.canRedo)
    }

    @Test
    fun `撤销栈上限 50`() {
        val e = engine3x3()
        repeat(120) { i ->
            val s = e.startStroke()
            s.setCell(i % 9, i)
            e.endStroke(s)
        }
        var undos = 0
        while (e.undo()) undos++
        assertEquals(50, undos)
    }

    @Test
    fun `revision 只在有变更时增长`() {
        val e = engine3x3()
        val r0 = e.revision
        e.setLocked(0, true)
        val r1 = e.revision
        val s = e.startStroke()
        assertFalse(s.setCell(0, 5)) // 锁定格无变更
        e.endStroke(s)
        assertEquals(r1, e.revision)
        val s2 = e.startStroke()
        assertTrue(s2.setCell(1, 5))
        e.endStroke(s2)
        assertTrue(e.revision > r1)
    }

    @Test
    fun `多页独立绘制与切换`() {
        val e = EditorEngine(3, 3, listOf(IntArray(9) { -1 }, IntArray(9) { -1 }))
        assertEquals(2, e.pageCount)
        e.setPage(1)
        val s = e.startStroke()
        s.setCell(0, 5)
        e.endStroke(s)
        e.setPage(0)
        assertEquals(-1, e.cells[0]) // 第 1 页不受第 2 页笔触影响
        e.setPage(1)
        assertEquals(5, e.cells[0])
    }

    @Test
    fun `油漆桶填充同色连通区域`() {
        val e = engine3x3()
        assertTrue(e.floodFill(4, 5))
        assertEquals(9, e.cells.count { it == 5 }) // 全空棋盘 4 连通充满
        e.undo()
        assertEquals(-1, e.cells[4]) // 整个填充作为一条撤销记录
    }

    @Test
    fun `油漆桶锁定格不改色但保持连通`() {
        val e = engine3x3()
        e.setLocked(1, true)
        assertTrue(e.floodFill(0, 7))
        assertEquals(-1, e.cells[1])
        assertEquals(8, e.cells.count { it == 7 })
    }

    @Test
    fun `油漆桶起点同色无操作`() {
        val e = engine3x3()
        assertFalse(e.floodFill(0, -1)) // 目标色与当前色相同
        assertFalse(e.canUndo)
    }

    @Test
    fun `新增复制页与删除页可撤销`() {
        val e = engine3x3()
        val s = e.startStroke()
        s.setCell(0, 5)
        e.endStroke(s)
        e.addPage(copyCurrent = true)
        assertEquals(2, e.pageCount)
        assertEquals(1, e.pageIndex)
        assertEquals(5, e.cells[0])
        assertTrue(e.removePage(0))
        assertEquals(1, e.pageCount)
        assertTrue(e.undo())
        assertEquals(2, e.pageCount) // 撤销删除页
        assertEquals(1, e.pageIndex)
        assertTrue(e.undo())
        assertEquals(1, e.pageCount) // 撤销新增页
        assertEquals(0, e.pageIndex)
    }

    @Test
    fun `改尺寸居中保留内容`() {
        val e = engine3x3()
        val s = e.startStroke()
        s.setCell(4, 5) // 中心格 (1,1)
        e.endStroke(s)
        e.resize(5, 5)
        assertEquals(25, e.cells.size)
        assertEquals(5, e.cells[2 * 5 + 2]) // (1,1) 平移到 (2,2)
        e.undo()
        assertEquals(9, e.cells.size)
        assertEquals(5, e.cells[4])
    }

    @Test
    fun `多页改尺寸所有页同步`() {
        val e = EditorEngine(3, 3, listOf(IntArray(9) { -1 }, IntArray(9) { 2 }))
        e.resize(4, 4)
        assertEquals(16, e.pages[0].size)
        assertEquals(16, e.pages[1].size)
        assertEquals(9, e.pages[1].count { it == 2 }) // 9 个旧格全部保留
    }
}
