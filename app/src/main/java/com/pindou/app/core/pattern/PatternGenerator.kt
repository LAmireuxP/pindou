package com.pindou.app.core.pattern

import com.pindou.app.core.color.ColorConversions
import com.pindou.app.core.palette.BeadPalette


/** 生成参数（功能规格书 §4） */
data class GeneratorOptions(
    val gridW: Int,
    val gridH: Int,
    val maxColors: Int? = null,          // null = 不限制（用自适应 K）
    val excludedCodes: Set<String> = emptySet(),
    val cleanupEnabled: Boolean = true,  // 孤立像素清理，默认开
    val ditheringEnabled: Boolean = false, // 抖动，默认关
    val removeBackground: Boolean = false, // 背景移除，默认关
    val backgroundTolerance: Double = 12.0, // 背景 ΔE 容差
    val similarCodeMaxDeltaE: Double = 3.0, // 相似色号合并阈值；0 = 关闭
    val saliencyDeltaE: Double = 15.0,  // 显著细节与主导色的最小 ΔE；0 = 关闭检测
    val saliencyFraction: Double = 0.15, // 显著细节色块的最小像素占比
    val contentMode: ContentSimplifier.Mode = ContentSimplifier.Mode.OFF, // 内容简化（前置）
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

enum class GenerationStage { DOWNSAMPLE, BACKGROUND, REDUCE, MAP, CLEAN, MERGE, DONE }

/**
 * 图纸生成管线：主导色降采样 → (K-means 压色) → 色卡映射 → (抖动) → (杂色清理) → (相似色号合并)。
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
        // ⓪ 内容简化（前置）：保边去细节，让降采样面对的是已扁平化的图
        val workPixels = if (options.contentMode != ContentSimplifier.Mode.OFF) {
            ContentSimplifier.simplify(pixels, srcW, srcH, options.contentMode)
        } else pixels
        // ① 主导色降采样 + 显著细节检测（眼睛、高光等小面积高对比色块优先保留）
        onStage(GenerationStage.DOWNSAMPLE, 0f)
        val down = DominantColorDownsampler.downsampleDetailed(
            workPixels, srcW, srcH, options.gridW, options.gridH,
            options.saliencyDeltaE, options.saliencyFraction,
        )
        var grid = down.colors
        val salientColors = HashSet<Int>()
        for (i in down.colors.indices) if (down.salient[i]) salientColors.add(down.colors[i])
        onStage(GenerationStage.DOWNSAMPLE, 1f)

        // ② 颜色量化（始终执行）：先把格子颜色聚成稳定的中间表示（类中心代替），
        //    再统一映射色号 —— 同一聚类的格子必然同号，小尺寸下采样块内颜色杂
        //    导致的跳号噪点从结构上消除。用户限色时用指定 K，否则自适应。
        onStage(GenerationStage.REDUCE, 0f)
        val targetK = options.maxColors
            ?: KmeansColorReducer.adaptiveClusterCount(options.gridW * options.gridH)
        grid = KmeansColorReducer.reduce(grid, targetK, salientColors) {
            onStage(GenerationStage.REDUCE, it)
        }
        onStage(GenerationStage.REDUCE, 1f)

        // ③+④ 色卡映射（抖动开启时由抖动器一并完成映射）
        val mapped: IntArray
        if (options.ditheringEnabled) {
            onStage(GenerationStage.MAP, 0f)
            mapped = FloydSteinbergDitherer.dither(
                grid, options.gridW, options.gridH,
                FloydSteinbergDitherer.FromPalette(palette),
            )
            onStage(GenerationStage.MAP, 1f)
        } else {
            mapped = mapToPalette(grid, options, palette) { onStage(GenerationStage.MAP, it) }
        }

        // ③b 背景移除（可选）：从四边洪水填充，与边缘连通的近似色清成空格
        val cells: IntArray = if (options.removeBackground) {
            BackgroundRemover.applyToCells(
                mapped, grid, options.gridW, options.gridH,
                options.backgroundTolerance,
            )
        } else {
            mapped
        }

        // ⑤ 杂色清理（带色差保护：真细节不被误清）
        var cleaned = cells
        if (options.cleanupEnabled) {
            onStage(GenerationStage.CLEAN, 0f)
            cleaned = IsolatedPixelCleaner.clean(
                cells, options.gridW, options.gridH,
                paletteArgbs = palette.colors.map { it.argb },
            )
            onStage(GenerationStage.CLEAN, 1f)
        }

        // ⑥ 相似色号合并：近同色号并入常用方，消除渐变区的跳号噪点
        if (options.similarCodeMaxDeltaE > 0.0) {
            onStage(GenerationStage.MERGE, 0f)
            cleaned = SimilarCodeMerger.merge(
                cleaned,
                palette.colors.map { it.argb },
                options.similarCodeMaxDeltaE,
            )
            onStage(GenerationStage.MERGE, 1f)
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
        // 按去重颜色缓存查询结果：K-means 量化后网格里只有 ≤64 种颜色，
        // 避免对每格重复 291 次 CIEDE2000（200×200 网格曾达千万次量级）。
        val cache = HashMap<Int, Int>()
        var lastReported = -1
        for (i in grid.indices) {
            val argb = grid[i]
            out[i] = cache.getOrPut(argb) {
                palette.nearestIndex(ColorConversions.argbToLab(argb), excluded)
            }
            val pct = (i * 100L / total).toInt()
            if (pct != lastReported) {
                lastReported = pct
                onProgress(pct / 100f)
            }
        }
        return out
    }
}
