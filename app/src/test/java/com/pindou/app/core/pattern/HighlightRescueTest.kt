package com.pindou.app.core.pattern

import com.pindou.app.core.color.ColorConversions
import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.palette.PaletteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighlightRescueTest {

    private val palette: BeadPalette =
        PaletteJson.parse(File("src/main/assets/palettes/all_solid_272.json").readText())
    private val white = palette.nearestIndex(ColorConversions.argbToLab(0xFFFFFFFF.toInt()))
    private val black = palette.nearestIndex(ColorConversions.argbToLab(0xFF000000.toInt()))

    private val w = 40
    private val h = 40
    private fun idx(x: Int, y: Int) = y * w + x

    private fun grid(cells: IntArray, spot: ByteArray) =
        HighlightRescue.apply(cells, w, h, palette, spot)

    @Test
    fun `实心暗块里的高光格被点回白豆`() {
        val cells = IntArray(w * h) { white }
        // 4×4 实心块：眼球的真实尺寸，内部格 4 个，满足实心度判据
        for (y in 17..20) for (x in 17..20) cells[idx(x, y)] = black
        val spot = ByteArray(w * h)
        spot[idx(19, 19)] = 45

        val out = grid(cells, spot)

        assertEquals("正中高光格应变白", white, out[idx(19, 19)])
        assertEquals("周围仍是黑豆", black, out[idx(18, 18)])
        assertEquals("只改一格", 1, out.indices.count { out[it] != cells[it] })
    }

    @Test
    fun `大面积暗块不做高光救援`() {
        val cells = IntArray(w * h) { white }
        for (y in 10..21) for (x in 10..21) cells[idx(x, y)] = black
        val spot = ByteArray(w * h)
        spot[idx(16, 16)] = 90

        val out = grid(cells, spot)

        assertEquals("大面积暗块（头发/描边）不动", black, out[idx(16, 16)])
        assertTrue(out.contentEquals(cells))
    }

    @Test
    fun `一格宽笔画中间不点白`() {
        // 十字形 5 格（`<` 闭眼、嘴角这类 1 格宽笔画的极端）：中间格四邻是
        // 暗色，但 8 邻域只有 4 个暗格——不满足"被深色包围"，不能穿心点白
        val cells = IntArray(w * h) { white }
        listOf(idx(20, 19), idx(19, 20), idx(20, 20), idx(21, 20), idx(20, 21))
            .forEach { cells[it] = black }
        val spot = ByteArray(w * h)
        spot[idx(20, 20)] = 60

        val out = grid(cells, spot)

        assertEquals("V 形/十字闭眼不穿心", black, out[idx(20, 20)])
    }

    @Test
    fun `近白占比不足时不动`() {
        val cells = IntArray(w * h) { white }
        for (y in 18..20) for (x in 18..20) cells[idx(x, y)] = black
        val spot = ByteArray(w * h)
        spot[idx(19, 19)] = 20

        val out = grid(cells, spot)
        assertEquals("占比 20% 不算高光", black, out[idx(19, 19)])
    }
}
