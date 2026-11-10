package com.pindou.app.core.editor

import com.pindou.app.core.color.ColorConversions
import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.palette.BeadSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteOpsTest {

    // 两个彼此极近的深藏青（ΔE < 3）+ 一个明显不同的红
    private val NAVY_A = 0xFF1A2438.toInt()
    private val NAVY_B = 0xFF1B2539.toInt()
    private val RED = 0xFFFF2A2A.toInt()
    private val BLUE = 0xFF2244FF.toInt()

    private fun paletteOf(brand: String, argbs: List<Int>): BeadPalette = BeadPalette(
        brand = brand,
        brandLabel = brand,
        beadSize = BeadSize.MM5,
        source = null,
        colors = argbs.mapIndexed { i, argb ->
            BeadPalette.Entry("C$i", null, argb, ColorConversions.argbToLab(argb))
        },
    )

    @Test
    fun `同品牌同色数短路返回原页`() {
        val p = paletteOf("MARD", listOf(NAVY_A, RED))
        val pages = listOf(intArrayOf(0, 1, -1))
        val out = PaletteOps.remapToPalette(pages, p, p)
        assertTrue(out[0].contentEquals(pages[0]))
    }

    @Test
    fun `跨品牌重映射到最近色`() {
        // 源：0=红 1=蓝；目标色表顺序对调：0=蓝 1=红 2=绿
        val source = paletteOf("MARD", listOf(RED, BLUE))
        val target = paletteOf("JOL", listOf(BLUE, RED, NAVY_A))
        val pages = listOf(intArrayOf(0, 1, -1))
        val out = PaletteOps.remapToPalette(pages, source, target)
        assertEquals(1, out[0][0]) // 红 → 目标里的红（下标 1）
        assertEquals(0, out[0][1]) // 蓝 → 目标里的蓝（下标 0）
        assertEquals(-1, out[0][2]) // 空格保持
    }

    @Test
    fun `相近色号并入用量大者`() {
        val palette = paletteOf("MARD", listOf(NAVY_A, NAVY_B, RED))
        // 97 格 A + 2 格 B + 1 格红：ΔE < 3 的 B 应并入 A（用量大者），红不动
        val pages = listOf(IntArray(100) { 0 }.also { it[0] = 1; it[1] = 1; it[2] = 2 })
        val out = PaletteOps.mergeSimilarCodes(pages, palette, 3.0)
        assertTrue(out[0].none { it == 1 })
        assertEquals(99, out[0].count { it == 0 })
        assertEquals(1, out[0].count { it == 2 }) // 红不动
    }

    @Test
    fun `跨页用量合计选代表色`() {
        val palette = paletteOf("MARD", listOf(NAVY_A, NAVY_B, RED))
        // 页 1 用 B 多，页 2 也用 B：合计后 B 用量超过 A → 代表色取 B
        val p1 = IntArray(10) { 1 }.also { it[0] = 0; it[1] = 0; it[2] = 0 }
        val p2 = IntArray(10) { 1 }
        val out = PaletteOps.mergeSimilarCodes(listOf(p1, p2), palette, 3.0)
        assertTrue(out.all { page -> page.none { it == 0 } })
        assertTrue(out.all { page -> page.all { it == 1 || it == 2 } })
    }

    @Test
    fun `阈值为零关闭合并`() {
        val palette = paletteOf("MARD", listOf(NAVY_A, NAVY_B, RED))
        val pages = listOf(intArrayOf(0, 1, 1))
        val out = PaletteOps.mergeSimilarCodes(pages, palette, 0.0)
        assertEquals(2, out[0].count { it == 1 }) // 合并关闭，B 原样保留
    }

    @Test
    fun `合并保留空格与越界索引`() {
        val palette = paletteOf("MARD", listOf(NAVY_A, NAVY_B, RED))
        val pages = listOf(intArrayOf(-1, 99, 1, 0, 1, 0))
        val out = PaletteOps.mergeSimilarCodes(pages, palette, 3.0)
        assertEquals(-1, out[0][0])
        assertEquals(99, out[0][1]) // 不在色卡范围内的旧值原样透传
        assertEquals(0, out[0][2]) // B 并入用量更大的 A
    }
}
