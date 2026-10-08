package com.pindou.app.core.pattern

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions

/**
 * Floyd–Steinberg 抖动（Lab 空间误差传播）。
 * 生成图纸默认关闭（抖动图难以实际拼装），仅作为进阶选项。
 *
 * @param cells 输入网格的 Lab 缓冲（会被误差累积修改），输出为同尺寸的色卡索引矩阵
 */
object FloydSteinbergDitherer {

    fun dither(
        grid: IntArray,
        width: Int,
        height: Int,
        palette: PaletteForDither,
    ): IntArray {
        // 扁平 Lab 缓冲（3 float/格）：避免为每格分配 FloatArray（200×200 曾达 4 万个小对象）
        val lab = FloatArray(grid.size * 3)
        for (i in grid.indices) {
            val l = ColorConversions.argbToLab(grid[i])
            lab[i * 3] = l[0]
            lab[i * 3 + 1] = l[1]
            lab[i * 3 + 2] = l[2]
        }
        val out = IntArray(grid.size) { -1 }
        val cursor = FloatArray(3)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                cursor[0] = lab[idx * 3]
                cursor[1] = lab[idx * 3 + 1]
                cursor[2] = lab[idx * 3 + 2]
                val best = palette.nearestIndex(cursor) ?: continue
                out[idx] = best
                // 误差 = 当前 Lab − 所选色卡 Lab
                val chosen = palette.labOf(best)
                val eL = cursor[0] - chosen[0]
                val eA = cursor[1] - chosen[1]
                val eB = cursor[2] - chosen[2]

                fun spread(nx: Int, ny: Int, factor: Double) {
                    if (nx < 0 || nx >= width || ny >= height) return
                    val ni = (ny * width + nx) * 3
                    lab[ni] = (lab[ni] + eL * factor).toFloat()
                    lab[ni + 1] = (lab[ni + 1] + eA * factor).toFloat()
                    lab[ni + 2] = (lab[ni + 2] + eB * factor).toFloat()
                }
                spread(x + 1, y, 7.0 / 16.0)
                spread(x - 1, y + 1, 3.0 / 16.0)
                spread(x, y + 1, 5.0 / 16.0)
                spread(x + 1, y + 1, 1.0 / 16.0)
            }
        }
        return out
    }

    /** 抖动所需的调色板最小接口（避免依赖完整 BeadPalette） */
    interface PaletteForDither {
        fun nearestIndex(lab: FloatArray): Int?
        fun labOf(index: Int): FloatArray
    }

    /** 把 BeadPalette 适配为抖动接口 */
    class FromPalette(palette: com.pindou.app.core.palette.BeadPalette) : PaletteForDither {
        private val labs = palette.colors.map { it.lab }
        override fun nearestIndex(lab: FloatArray): Int? {
            var best = -1
            var bestD = Double.MAX_VALUE
            for (i in labs.indices) {
                val d = Ciede2000.deltaE(lab, labs[i])
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            return if (best < 0) null else best
        }

        override fun labOf(index: Int): FloatArray = labs[index]
    }
}
