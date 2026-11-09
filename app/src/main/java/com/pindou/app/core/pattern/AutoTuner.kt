package com.pindou.app.core.pattern

import com.pindou.app.core.palette.BeadPalette

/**
 * 自动优选：把同一张图跑若干组参数，按 [QualityReport.score] 排序取最高。
 *
 * 同类 app 只给一根"简化强度"滑杆让用户自己试；试出来好不好全凭手感，
 * 且同一张图换个人调，结果差一截。这里把"哪组参数更好"变成可比较的数字。
 *
 * 候选集刻意压到 ≤5 组：单次生成在 78×72 网格上约 200~600ms，
 * 5 组再加评分 ≈ 1~3s，用户可以等，但不能再多。
 */
object AutoTuner {

    data class Candidate(
        val filterLevel: FilterLevel,
        val maxColors: Int?,
        val result: PatternResult,
    ) {
        val score: Int get() = result.quality.score
    }

    data class Outcome(val best: Candidate, val all: List<Candidate>) {
        /** 与"用户原来的手动参数"相比高多少分 */
        fun gainOver(baseline: Candidate?): Int =
            if (baseline == null) 0 else best.score - baseline.score
    }

    /**
     * @param base 用户当前参数（网格、背景移除、抖动等都沿用）
     * @param levels 参与比较的过滤档位
     * @param maxColorsOptions 参与比较的限色档位；null = 不限色
     * @param onProgress (已完成组数, 总组数, 当前档位)
     */
    fun sweep(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        base: GeneratorOptions,
        palette: BeadPalette,
        levels: List<FilterLevel> = FilterLevel.entries.toList(),
        maxColorsOptions: List<Int?> = listOf(base.maxColors),
        onProgress: (Int, Int, FilterLevel) -> Unit = { _, _, _ -> },
    ): Outcome? {
        val combos = levels.flatMap { level -> maxColorsOptions.map { level to it } }
        if (combos.isEmpty()) return null

        val candidates = ArrayList<Candidate>(combos.size)
        combos.forEachIndexed { index, (level, colors) ->
            onProgress(index, combos.size, level)
            val result = PatternGenerator.generate(
                pixels, srcW, srcH,
                base.copy(filterLevel = level, maxColors = colors),
                palette,
            )
            candidates += Candidate(level, colors, result)
        }

        // 同分时优先色多的一版：同分说明观感指标打平，色多那版的结构细节
        // 更完整（花瓣层次、轮廓拐角），20 色那版是"糊平"换来的低碎点。
        val best = candidates.maxWithOrNull(
            compareBy<Candidate> { it.score }
                .thenBy { it.result.quality.colorCount },
        ) ?: return null
        return Outcome(best, candidates)
    }

    /**
     * 默认候选：四个过滤档 × 三档色数（20 / 30 / 不限）。
     *
     * 只给 20 色时，评分里的碎点项永远偏向"糊成一块"的组合，花瓣层次会被
     * 合并掉；加 30 色档给细腻版本一个进候选的机会。12 组在 78×72 上约
     * 6~9 秒，仍在可等范围内。
     * 已限色时只比档位，避免把用户明确设定的色数上限改掉。
     */
    fun defaultColorOptions(base: GeneratorOptions): List<Int?> =
        if (base.maxColors != null) listOf(base.maxColors) else listOf(null, 20, 30)
}
