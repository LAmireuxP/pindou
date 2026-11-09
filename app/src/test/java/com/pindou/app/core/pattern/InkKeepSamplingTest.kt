package com.pindou.app.core.pattern

import com.pindou.app.core.color.ColorConversions
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采样层的细深色结构保留（眼睛、描边）。
 *
 * 这条规则曾经写反：判据恒成立时总是取"亮的那一侧"，于是眼睛、耳朵描边
 * 被整片刷成白色——78 格下粉兔的右眼只剩两格碎黑。这里锁死正确行为。
 */
class InkKeepSamplingTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** 白底 + 左侧一条纯黑竖线，线宽按像素给 */
    private fun stripeImage(size: Int, stripeW: Int): IntArray {
        val black = argb(0, 0, 0)
        val white = argb(255, 255, 255)
        return IntArray(size * size) { i ->
            if (i % size < stripeW) black else white
        }
    }

    @Test
    fun `深色线占到两成以上时整格取深色`() {
        val size = 160
        val grid = 4                      // 每格 40×40 px
        val pixels = stripeImage(size, 10) // 10/40 = 25%，落在 22%~50% 之间
        val sample = ImagePreprocessor.sample(pixels, size, size, grid, grid, FilterLevel.NONE)

        val l = ColorConversions.argbToLab(sample.argb[0])[0]
        assertTrue("左列应为深色，实际 L=$l", l < 30f)
        assertTrue("边界格应被标记", sample.edge?.get(0) == true)
        val white = ColorConversions.argbToLab(sample.argb[grid - 1])[0]
        assertTrue("其余格保持白，实际 L=$white", white > 90f)
    }

    @Test
    fun `零星深色像素不翻转整格`() {
        val size = 160
        val grid = 4
        val pixels = stripeImage(size, 5) // 5/40 = 12.5%，低于 22% 门槛
        val sample = ImagePreprocessor.sample(pixels, size, size, grid, grid, FilterLevel.NONE)

        val l = ColorConversions.argbToLab(sample.argb[0])[0]
        assertTrue("不足两成不应翻黑，实际 L=$l", l > 60f)
    }

    @Test
    fun `近白占比随覆盖面积输出`() {
        val size = 160
        val grid = 4
        val pixels = stripeImage(size, 12)
        val sample = ImagePreprocessor.sample(pixels, size, size, grid, grid, FilterLevel.NONE)
        val spot = sample.spot ?: error("采样应输出近白占比")
        // 左列 70% 白；右列 100% 白
        assertTrue("左列 spot=${spot[0]}", spot[0].toInt() in 60..80)
        assertTrue("右列 spot=${spot[grid - 1]}", spot[grid - 1].toInt() >= 99)
    }
}
