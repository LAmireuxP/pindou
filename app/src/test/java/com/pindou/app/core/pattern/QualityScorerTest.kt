package com.pindou.app.core.pattern

import com.pindou.app.core.color.ColorConversions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityScorerTest {

    private val red = 0xFFE05A5A.toInt()
    private val blue = 0xFF2A4BD7.toInt()

    private fun sample(vararg argb: Int): GridSample {
        val lab = FloatArray(argb.size * 3)
        argb.forEachIndexed { i, c ->
            val l = ColorConversions.argbToLab(c)
            lab[i * 3] = l[0]
            lab[i * 3 + 1] = l[1]
            lab[i * 3 + 2] = l[2]
        }
        return GridSample(argb.copyOf(), lab)
    }

    @Test
    fun `颜色完全一致时满分`() {
        val ref = sample(red, red, red, red)
        val report = QualityScorer.score(ref, 2, 2, intArrayOf(0, 0, 0, 0), intArrayOf(red))
        assertEquals(100, report.score)
        assertEquals(0.0, report.meanDeltaE, 1e-6)
        assertEquals(0.0, report.breakRate, 1e-6)
        assertEquals(1, report.colorCount)
    }

    @Test
    fun `同色区域被拆成两色时统计为破碎`() {
        // 原图一整条同色，输出中间插了一格蓝：两对相邻格里两对都被判"拆碎"
        val ref = sample(red, red, red)
        val report = QualityScorer.score(ref, 3, 1, intArrayOf(0, 1, 0), intArrayOf(red, blue))
        assertEquals(1.0, report.breakRate, 1e-6)
        assertEquals(0.0, report.flattenRate, 1e-6)
        assertTrue("碎点必须显著扣分，实际 ${report.score}", report.score < 60)
    }

    @Test
    fun `明确边界被压成同色时统计为糊边`() {
        // 原图左红右蓝是硬边界，输出两格都是红：边界被抹平
        val ref = sample(red, blue)
        val report = QualityScorer.score(ref, 2, 1, intArrayOf(0, 0), intArrayOf(red, blue))
        assertEquals(1.0, report.flattenRate, 1e-6)
        assertEquals(0.0, report.breakRate, 1e-6)
    }

    @Test
    fun `空格与透明格不参与评分`() {
        val ref = sample(red, 0x00FFFFFF, red)
        val report = QualityScorer.score(ref, 3, 1, intArrayOf(-1, 0, -1), intArrayOf(red))
        // 只有中间一格有源色但它是空的，两侧是透明格 → 有效格 0，返回 EMPTY
        assertEquals(QualityReport.EMPTY, report)
    }

    @Test
    fun `用色过多会扣分`() {
        val ref = sample(red, red)
        val palette = IntArray(30) { if (it == 0) red else 0xFF000000.toInt() or (it * 0x010101) }
        // 全用 0 号色，但调色板里有 30 色：只按"实际用到"的 1 色计，不扣
        val used = QualityScorer.score(ref, 2, 1, intArrayOf(0, 0), palette)
        assertEquals(1, used.colorCount)
        assertEquals(100, used.score)
    }
}
