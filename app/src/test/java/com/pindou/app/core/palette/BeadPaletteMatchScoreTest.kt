package com.pindou.app.core.palette

import com.pindou.app.core.color.ColorConversions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色卡匹配的中性色相保护：浅灰不能被 CIEDE2000 拉到更"近"的冷灰/蓝灰上。
 * 对应实测问题：贴纸图内耳的浅灰被映射成冷灰，拼出来是脏蓝点。
 */
class BeadPaletteMatchScoreTest {

    private fun palette(vararg hex: Pair<String, Int>): BeadPalette =
        BeadPalette(
            brand = "TEST",
            brandLabel = "测试",
            beadSize = BeadSize.MM5,
            source = "unit-test",
            colors = hex.map { (code, argb) ->
                BeadPalette.Entry(code, null, argb, ColorConversions.argbToLab(argb))
            },
        )

    @Test
    fun `中性灰优先选真正的中性灰而不是更近的冷灰`() {
        val p = palette(
            "GRAY" to 0xFFD2D2D2.toInt(),
            "COOL" to 0xFFE0E4EE.toInt(),
        )
        val src = ColorConversions.argbToLab(0xFFE0E0E0.toInt())

        // CIEDE2000 只看色差会选 COOL（ΔL 更小），加了色相罚分之后必须翻过来
        val gray = p.matchScore(src, p.colors[0])
        val cool = p.matchScore(src, p.colors[1])
        assertTrue("中性灰应优于冷灰：gray=$gray cool=$cool", gray < cool)
        assertEquals(0, p.nearestIndex(src))
    }

    @Test
    fun `饱和色不受中性罚分影响`() {
        val p = palette(
            "PINK" to 0xFFF5A8C0.toInt(),
            "BLUE" to 0xFFA8C8F5.toInt(),
        )
        val src = ColorConversions.argbToLab(0xFFF2A2BC.toInt())
        assertEquals(0, p.nearestIndex(src))
    }

    @Test
    fun `纯白仍然落到白色色号`() {
        val p = palette(
            "WHITE" to 0xFFFFFFFF.toInt(),
            "COOL" to 0xFFE0E4EE.toInt(),
        )
        val src = ColorConversions.argbToLab(0xFFFDFDFD.toInt())
        assertEquals(0, p.nearestIndex(src))
    }
}
