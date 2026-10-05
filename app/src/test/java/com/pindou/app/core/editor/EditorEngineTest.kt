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
    fun `撤销栈上限 100`() {
        val e = engine3x3()
        repeat(120) { i ->
            val s = e.startStroke()
            s.setCell(i % 9, i)
            e.endStroke(s)
        }
        var undos = 0
        while (e.undo()) undos++
        assertEquals(100, undos)
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
}
