package com.pindou.app.core.pattern

import com.pindou.app.core.color.ColorConversions
import com.pindou.app.core.palette.BeadPalette


/** 生成参数（功能规格书 §4） */
data class GeneratorOptions(
    val gridW: Int,
    val gridH: Int,
    val maxColors: Int? = null,          // null = 不限制
    val excludedCodes: Set<String> = emptySet(),
    val cleanupEnabled: Boolean = true,  // 孤立像素清理，默认开
    val ditheringEnabled: Boolean = false, // 抖动，默认关
    val removeBackground: Boolean = false, // 背景移除，默认关
    val backgroundTolerance: Double = 12.0, // 背景 ΔE 容差
)

/** 生成结果：cells 为色卡索引（-1 = 空格），与 palette 配套使用 */
data class PatternResult(
    val width: Int,
    val height: Int,
    val cells: IntArray,
    val palette: BeadPalette,
    val elapsedMs: Long,
) {
    /** 各色号用量（索引 → 数量），按数量降序 */
    fun usage(): List<Pair<Int, Int>> {
        val counts = HashMap<Int, Int>()
        for (c in cells) if (c >= 0) counts.merge(c, 1, Int::plus)
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    override fun equals(other: Any?): Boolean = other is PatternResult &&
        other.width == width && other.height == height && other.cells.contentEquals(cells)
    override fun hashCode(): Int = cells.contentHashCode()
}

enum class GenerationStage { DOWNSAMPLE, BACKGROUND, REDUCE, MAP, CLEAN, DONE }

/**
 * 图纸生成管线：主导色降采样 → (K-means 压色) → 色卡映射 → (抖动) → (杂色清理)。
 * 纯 Kotlin 实现，可在任意线程执行。
 */
object PatternGenerator {

    fun generate(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        options: GeneratorOptions,
        palette: BeadPalette,
        onStage: (GenerationStage, Float) -> Unit = { _, _ -> },
    ): PatternResult {
        val startNanos = System.nanoTime()
        // ① 主导色降采样
        onStage(GenerationStage.DOWNSAMPLE, 0f)
        var grid = DominantColorDownsampler.downsample(pixels, srcW, srcH, options.gridW, options.gridH)
        onStage(GenerationStage.DOWNSAMPLE, 1f)

        // ② 颜色数压缩（可选）
        if (options.maxColors != null) {
            onStage(GenerationStage.REDUCE, 0f)
            grid = KmeansColorReducer.reduce(grid, options.maxColors) {
                onStage(GenerationStage.REDUCE, it)
            }
            onStage(GenerationStage.REDUCE, 1f)
        }

        // ③+④ 色卡映射（抖动开启时由抖动器一并完成映射）
        val cells: IntArray
        if (options.ditheringEnabled) {
            onStage(GenerationStage.MAP, 0f)
            cells = FloydSteinbergDitherer.dither(
                grid, options.gridW, options.gridH,
                FloydSteinbergDitherer.FromPalette(palette),
            )
            onStage(GenerationStage.MAP, 1f)
        } else {
            cells = mapToPalette(grid, options, palette) { onStage(GenerationStage.MAP, it) }
        }

        // ⑤ 杂色清理
        var cleaned = cells
        if (options.cleanupEnabled) {
            onStage(GenerationStage.CLEAN, 0f)
            cleaned = IsolatedPixelCleaner.clean(cells, options.gridW, options.gridH)
            onStage(GenerationStage.CLEAN, 1f)
        }
        val result = PatternResult(
            options.gridW, options.gridH, cleaned, palette,
            (System.nanoTime() - startNanos) / 1_000_000,
        )
        onStage(GenerationStage.DONE, 1f)
        return result
    }

    /** 网格 ARGB → 最近色卡索引 */
    private fun mapToPalette(
        grid: IntArray,
        options: GeneratorOptions,
        palette: BeadPalette,
        onProgress: (Float) -> Unit,
    ): IntArray {
        val out = IntArray(grid.size)
        val excluded = options.excludedCodes
        val total = grid.size
        var lastReported = -1
        for (i in grid.indices) {
            val lab = ColorConversions.argbToLab(grid[i])
            out[i] = palette.nearestIndex(lab, excluded)
            val pct = (i * 100L / total).toInt()
            if (pct != lastReported) {
                lastReported = pct
                onProgress(pct / 100f)
            }
        }
        return out
    }
}
