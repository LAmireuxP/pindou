package com.pindou.app.core.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstructionStateTest {

    private fun state3x3() = ConstructionState(3, 3, BooleanArray(9))

    @Test
    fun `切换单格与进度统计`() {
        val s = state3x3()
        assertEquals(0f, s.progress)
        s.toggle(0)
        s.toggle(4)
        assertEquals(2, s.doneCount)
        assertEquals(2f / 9f, s.progress, 1e-6f)
        s.toggle(0) // 再切一次变回未完成
        assertEquals(1, s.doneCount)
    }

    @Test
    fun `setDone 无变化返回 false`() {
        val s = state3x3()
        assertFalse(s.setDone(0, false))
        assertTrue(s.setDone(0, true))
        assertFalse(s.setDone(0, true))
        assertTrue(s.done[0])
    }

    @Test
    fun `整行整列标记返回改动格数`() {
        val s = state3x3()
        assertEquals(3, s.setRowDone(1, true))
        assertEquals(2, s.setColDone(1, true)) // 中格已由整行标过
        assertEquals(5, s.doneCount)
        assertEquals(3, s.setRowDone(1, false))
        assertEquals(2, s.doneCount)
    }

    @Test
    fun `跳到下一个未完成行列`() {
        val s = state3x3()
        assertTrue(s.jumpToNextIncomplete())
        assertEquals(0, s.cursor)
        s.setRowDone(0, true)
        assertTrue(s.jumpToNextIncomplete())
        assertEquals(1, s.cursor)
        s.setColDone(1, true)
        s.updateOrientation(ConstructionState.Orientation.COLUMN)
        assertTrue(s.jumpToNextIncomplete())
        assertEquals(0, s.cursor) // 列 0 还没完成
        s.setRowDone(2, true)
        s.setColDone(0, true)
        s.setDone(1 * 3 + 2, true) // 补掉最后一个格 (1,2)
        assertFalse(s.jumpToNextIncomplete()) // 全部完成
    }

    @Test
    fun `revision 只在实际变更时增长`() {
        val s = state3x3()
        val r0 = s.revision
        s.setDone(0, false) // 无变化
        assertEquals(r0, s.revision)
        s.toggle(3)
        assertTrue(s.revision > r0)
    }

    @Test
    fun `尺寸不符拒绝构造`() {
        try {
            ConstructionState(3, 3, BooleanArray(4)) // 应抛出
            throw AssertionError("应当抛出 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 预期路径
        }
    }
}
