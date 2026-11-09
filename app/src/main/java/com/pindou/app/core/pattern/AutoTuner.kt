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

        // 同分时优先少用色：分数一样但少 5 色，拼起来更省
        val best = candidates.maxWithOrNull(
            compareBy<Candidate> { it.score }
                .thenByDescending { it.result.quality.colorCount },
        ) ?: return null
        return Outcome(best, candidates)
    }

    /**
     * 默认候选：四个过滤档 + （未限色时）一个 20 色档。
     * 已限色时只比档位，避免把用户明确设定的色数上限改掉。
     */
    fun defaultColorOptions(base: GeneratorOptions): List<Int?> =
        if (base.maxColors != null) listOf(base.maxColors) else listOf(null, 20)
}
