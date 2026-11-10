package com.pindou.app.core.pattern

import com.pindou.app.core.palette.BeadPalette
import kotlin.math.roundToInt

/** 生成参数（功能规格书 §4） */
data class GeneratorOptions(
    val gridW: Int,
    val gridH: Int,
    val maxColors: Int? = null,          // null = 不限制（色卡全色）
    val excludedCodes: Set<String> = emptySet(),
    val cleanupEnabled: Boolean = true,  // 孤立像素清理，默认开
    val ditheringEnabled: Boolean = false, // 抖动，默认关
    val removeBackground: Boolean = false, // 背景移除，默认关
    val backgroundTolerance: Double = 12.0, // 背景 ΔE 容差
    val similarCodeMaxDeltaE: Double = 6.0, // 相似色号合并阈值；0 = 关闭
    val filterLevel: FilterLevel = FilterLevel.MEDIUM, // 过滤等级（对齐颂拼豆四档）
    val detailThreshold: Double? = null, // 细节保护阈值（Lab ΔE）；null = 跟随过滤等级
    val autoExposure: Boolean = true,    // 欠曝照片自动提亮（中位亮度 < 70 才触发）
    val regionCleanupEnabled: Boolean = true, // 区域清理：小面积连通碎块并入邻域
    val regionMinArea: Int? = null,           // null = 按过滤等级/格数自适应
    /** 显式「像素画生成」：放宽画像素检测并按最近画像素取色，不生成过渡色 */
    val exactArt: Boolean = false,
)

/** 生成结果：cells 为色卡索引（-1 = 空格），与 palette 配套使用 */
data class PatternResult(
    val width: Int,
    val height: Int,
    val cells: IntArray,
    val palette: BeadPalette,
    val elapsedMs: Long,
    /** 质量评分；历史数据/测试构造的结果为 EMPTY（score=0） */
    val quality: QualityReport = QualityReport.EMPTY,
    /** 生成时用的过滤档位，回显给 UI（自动优选后告诉用户选了哪档） */
    val filterLevel: FilterLevel = FilterLevel.MEDIUM,
    /** 源图被识别成像素画：图纸是逐画像素还原的，质量口径与照片不同 */
    val pixelArt: Boolean = false,
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

enum class GenerationStage { EXPOSURE, DOWNSAMPLE, BACKGROUND, REDUCE, MAP, CLEAN, MERGE, LIMIT, DONE }

/**
 * 图纸生成管线（对齐颂拼豆「导入前处理」的思路）：
 *
 * 曝光修正 → 面积加权重采样到 2× 网格 → 按过滤等级盒式平滑 → 落到目标网格
 * → Lab 聚类量化 → 细节回填 → 邻域众数滤波 → 小区域合并 → 相似色号合并 → 限色。
 *
 * 关键差别：不再逐格映射色卡。逐格最近色会把同一片区域拆成十几个相邻
 * 色号，纹理被放大成棋盘格；先 k-means 把整图归成 K 个色簇、逐簇映射，
 * 同一片区域的所有格子带同一个色号，块面自然连成一片。细节掩码再把
 * 眼睛、高光、首饰按原始采样色回填，避免聚类把五官平均掉。
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
        // ⓪a 曝光修正：欠曝照片先提亮，主体颜色才能展开（正常曝光原图直通）
        onStage(GenerationStage.EXPOSURE, 0f)
        val exposurePixels = if (options.autoExposure) {
            ExposureCorrector.correct(pixels, srcW, srcH)
        } else pixels
        onStage(GenerationStage.EXPOSURE, 1f)

        // ① 采样：面积平均 + 分级平滑，直接得到目标网格的颜色。
        //    过滤等级越高，2× 中间图上的盒式平滑越强，纹理高频先被吃掉。
        onStage(GenerationStage.DOWNSAMPLE, 0f)
        val sample = ImagePreprocessor.sample(
            exposurePixels, srcW, srcH, options.gridW, options.gridH, options.filterLevel,
            options.exactArt,
        )
        onStage(GenerationStage.DOWNSAMPLE, 1f)

        // 像素画原图走独立支路：源图本来就是离散色块，任何"平滑/众数/区域合并"
        // 都会把 1~2 格宽的花瓣、眼白直接抹掉。这里只做两件事——按面积加权
        // 压色号数、按色号面积多数落格。target 网格上的 argb 只留给背景移除用。
        val art = sample.art
        // 实际是否落到"逐画像素原色"档（网格被放大时不成立，见 sampleArt）
        val exactArt = sample.exact

        // ② 细节掩码：与邻域均值色差大的格子（眼睛、嘴、高光）锁定。
        //    必须在量化前算好，量化阶段用它回填原始采样色。
        //    像素画支路不需要：它本身就没有"照片纹理"，回填只会让同一片花瓣里
        //    出现两三种粉（簇色号 + 原始最近色）。
        val detailThreshold = options.detailThreshold ?: options.filterLevel.detailThreshold
        val detail: BooleanArray? = if (art != null) {
            null
        } else {
            ImagePreprocessor.detailMask(
                sample, options.gridW, options.gridH, threshold = detailThreshold,
            )
        }

        // ②b 边界格并进保护掩码：采样阶段已经把跨边界的格子取成"多数侧原色"，
        //     量化回填、众数滤波、小区域合并都必须放过它们，否则刚切出来的边
        //     又会被邻域投票糊回去——这是高过滤档"糊边率"的主要来源。
        val protect: BooleanArray? = if (detail == null) {
            null
        } else {
            val edge = sample.edge
            if (edge == null) detail else BooleanArray(detail.size) { detail[it] || edge[it] }
        }

        // ③ 色卡映射：抖动走逐格误差扩散；默认走 Lab 聚类量化。
        //    量化先把整图归成 K 个色簇，同一片区域共享一个色号，这是
        //    "成块"的来源；抖动与聚类互斥，开了抖动就保留逐格语义。
        val mapped: IntArray
        if (options.ditheringEnabled) {
            onStage(GenerationStage.MAP, 0f)
            mapped = FloydSteinbergDitherer.dither(
                sample.argb, options.gridW, options.gridH,
                FloydSteinbergDitherer.FromPalette(palette),
            )
            onStage(GenerationStage.MAP, 1f)
        } else if (art != null) {
            onStage(GenerationStage.MAP, 0f)
            mapped = ArtQuantizer.quantize(
                art, options.gridW, options.gridH, palette,
                options.filterLevel.artMaxColors, options.excludedCodes,
                exactArt,
            )
            onStage(GenerationStage.MAP, 1f)
        } else {
            onStage(GenerationStage.MAP, 0f)
            mapped = LabQuantizer.quantize(
                sample, options.gridW, options.gridH, palette,
                options.filterLevel.clusterK, options.excludedCodes, protect,
            )
            onStage(GenerationStage.MAP, 1f)
        }

        // ②b 背景移除（可选）：固定边界主色 + 主体剪影保护，只清主体外的背景，
        //     主体内部的同色区域（猫脸白、花瓣间淡色）保留
        val cells: IntArray = if (options.removeBackground) {
            onStage(GenerationStage.BACKGROUND, 0f)
            // 在 3× 网格上判背景再落回目标网格：目标网格上不足 1 格宽的
            // 黑色描边会被泛洪从断口穿透，主体内部的白随之被误删。
            val removed = BackgroundRemover.applyToCellsFromPixels(
                cells = mapped,
                pixels = exposurePixels,
                srcW = srcW,
                srcH = srcH,
                w = options.gridW,
                h = options.gridH,
                tolerance = options.backgroundTolerance,
                grid = sample.argb,
            )
            onStage(GenerationStage.BACKGROUND, 1f)
            removed
        } else {
            mapped
        }

        // ④ 邻域众数滤波：过滤等级的档位核心。无过滤 0 轮，低/中 1 轮，高 2 轮。
        onStage(GenerationStage.CLEAN, 0f)
        var cleaned = ImagePreprocessor.modeFilter(
            cells, options.gridW, options.gridH, palette.colors.size,
            if (art != null) 0 else options.filterLevel.modePasses, protect,
        )

        // ⑤ 小区域合并：面积占比低于档位阈值的同色连通块并入最大邻块，
        //    细节格所在区域整块保留。复杂度 O(格数)。
        if (art == null && options.regionCleanupEnabled && options.filterLevel.regionFraction > 0.0) {
            val autoMin = (options.gridW * options.gridH * options.filterLevel.regionFraction)
                .roundToInt()
                .coerceIn(3, 64)
            val minArea = options.regionMinArea ?: autoMin
            cleaned = RegionCleaner.cleanMasked(
                cleaned, options.gridW, options.gridH, minArea,
                palette.colors.map { it.argb }, protect,
                options.filterLevel.regionKeepDeltaE,
            )
        }
        onStage(GenerationStage.CLEAN, 0.6f)

        // ⑥ 相似色号合并：近同色号并入常用方，消除渐变区的跳号噪点
        if (options.similarCodeMaxDeltaE > 0.0 && !exactArt) {
            onStage(GenerationStage.MERGE, 0f)
            cleaned = SimilarCodeMerger.merge(
                cleaned,
                palette.colors.map { it.argb },
                options.similarCodeMaxDeltaE,
            )
            onStage(GenerationStage.MERGE, 1f)
        }

        // ⑥b 孤立像素兜底：众数滤波后仍残留的、全图出现 ≤3 次的单格
        if (art == null && options.cleanupEnabled) {
            cleaned = IsolatedPixelCleaner.clean(
                cleaned, options.gridW, options.gridH,
                paletteArgbs = palette.colors.map { it.argb },
            )
        }

        // ⑥d 小亮斑救援：眼睛高光、牙齿这类不足一格的白点，面积平均后只剩
        //     一格灰，色卡映射一压就没了。用小深色块内的近白占比把它点回来。
        //     放在所有清理之后：前面任何一轮众数/区域合并都会把单格白点吃掉。
        val spot = sample.spot
        if (art == null && spot != null) {
            cleaned = HighlightRescue.apply(
                cleaned, options.gridW, options.gridH, palette, spot, options.excludedCodes,
            )
        }

        // 参照采样：未平滑的面积平均，代表"原图在目标网格上真实的样子"。
        // 边缘还原与质量评分共用这一份；用当前档位的 sample 会把平滑后的
        // 图当参照，边界早就没了，还原与评分都失去意义。
        val refSample = if (options.filterLevel == FilterLevel.NONE) {
            sample
        } else {
            ImagePreprocessor.sample(
                exposurePixels, srcW, srcH, options.gridW, options.gridH, FilterLevel.NONE,
                options.exactArt,
            )
        }

        // ⑥c 最终限色：用户显式限色时把色号数压到 maxColors 以内
        if (options.maxColors != null) {
            onStage(GenerationStage.LIMIT, 0f)
            cleaned = ColorLimiter.limit(cleaned, palette.colors.map { it.argb }, options.maxColors)
            onStage(GenerationStage.LIMIT, 1f)
        }
        onStage(GenerationStage.CLEAN, 1f)

        // ⑦ 质量评分：拿"原始采样色"和"最终色号色"逐格比色差。
        //    必须在这里算——sample 在曝光/平滑之后、量化之前，是这张图在
        //    目标网格上"理论上应该长什么样"的唯一参照；出去之后就没了。
        //    参照必须"未平滑"：用了当前档位的 sample，平滑越强参照越糊，
        //    评分会把糊掉的图判成"和参照一样"——档位之间分数就拉不开了。
        val quality = QualityScorer.score(
            refSample, options.gridW, options.gridH, cleaned,
            palette.colors.map { it.argb }.toIntArray(),
            pixelArt = art != null,
        )

        val result = PatternResult(
            options.gridW, options.gridH, cleaned, palette,
            (System.nanoTime() - startNanos) / 1_000_000,
            quality = quality,
            filterLevel = options.filterLevel,
            pixelArt = art != null,
        )
        onStage(GenerationStage.DONE, 1f)
        return result
    }

}
