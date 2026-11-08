package com.pindou.app.core.export

import com.pindou.app.core.pattern.PatternResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 采购清单文本生成 */
object ShoppingList {

    /**
     * 生成可分享的清单文本，含按粒装建议。
     * 拼豆实体 1 格 = 1 粒，常见包装 500 / 1000 粒。
     */
    fun render(
        result: PatternResult,
        projectName: String,
        date: Date = Date(),
    ): String {
        return render(listOf(result), projectName, date)
    }

    /**
     * 多页聚合清单：各页同色号用量合并，标题标注页数。
     * 单页结果走同一实现，输出一致。
     */
    fun render(
        results: List<PatternResult>,
        projectName: String,
        date: Date = Date(),
    ): String {
        require(results.isNotEmpty()) { "无页数据" }
        val first = results.first()
        val counts = HashMap<Int, Int>()
        for (r in results) {
            for (c in r.cells) if (c >= 0) counts.merge(c, 1, Int::plus)
        }
        val usage = counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
        val total = usage.sumOf { it.second }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
        val pageCount = if (results.size > 1) "（${results.size} 页）" else ""

        return buildString {
            appendLine("拼豆采购清单")
            appendLine("项目：$projectName")
            appendLine("图纸：${first.width}×${first.height}$pageCount（共 $total 粒）")
            appendLine("色卡：${first.palette.brandLabel}")
            appendLine("日期：$dateStr")
            appendLine("—".repeat(24))
            appendLine("色号\t颜色\t数量\t建议购买")
            for ((idx, count) in usage) {
                val entry = first.palette.colors[idx]
                val name = entry.name ?: ""
                val packs = packSuggestion(count)
                appendLine("${entry.code}\t$name\t$count\t$packs")
            }
            appendLine("—".repeat(24))
            appendLine("合计：$total 粒 / ${usage.size} 色")
            appendLine("由「拼豆图纸」App 生成")
        }
    }

    /** 购买建议：<500 建议 500 粒装，否则按 1000 粒整包向上取 */
    fun packSuggestion(count: Int): String = when {
        count <= 0 -> "-"
        count <= 500 -> "500粒装×1"
        else -> "1000粒装×${(count + 999) / 1000}"
    }
}
