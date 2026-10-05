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
        val usage = result.usage()
        val total = usage.sumOf { it.second }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)

        return buildString {
            appendLine("拼豆采购清单")
            appendLine("项目：$projectName")
            appendLine("图纸：${result.width}×${result.height}（共 $total 粒）")
            appendLine("色卡：${result.palette.brandLabel}")
            appendLine("日期：$dateStr")
            appendLine("—".repeat(24))
            appendLine("色号\t颜色\t数量\t建议购买")
            for ((idx, count) in usage) {
                val entry = result.palette.colors[idx]
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