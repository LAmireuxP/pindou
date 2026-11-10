package com.pindou.app.core.export

import com.pindou.app.core.palette.PaletteJson
import com.pindou.app.core.pattern.PatternResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingListTest {

    private val palette = PaletteJson.parse(
        """
        {
          "brand": "T", "brandLabel": "测试卡", "beadSize": "5mm", "version": 1,
          "colors": [
            {"code": "W", "hex": "#FFFFFF", "name": "白"},
            {"code": "R", "hex": "#FF0000", "name": "红"}
          ]
        }
    """.trimIndent(),
    )

    @Test
    fun `清单含表头与合计`() {
        val w = palette.indexOfCode("W")
        val r = palette.indexOfCode("R")
        val result = PatternResult(2, 2, intArrayOf(w, w, r, -1), palette, 0)
        val text = ShoppingList.render(result, "测试项目")
        assertTrue(text.contains("拼豆采购清单"))
        assertTrue(text.contains("测试项目"))
        assertTrue(text.contains("2×2"))
        assertTrue(text.contains("测试卡"))
        assertTrue(text.contains("合计：3 粒 / 2 色"))
        assertTrue(text.contains("W"))
        assertTrue(text.contains("白"))
    }

    @Test
    fun `购买建议规则`() {
        assertEquals("500粒装×1", ShoppingList.packSuggestion(1))
        assertEquals("500粒装×1", ShoppingList.packSuggestion(500))
        assertEquals("1000粒装×1", ShoppingList.packSuggestion(501))
        assertEquals("1000粒装×1", ShoppingList.packSuggestion(1000))
        assertEquals("1000粒装×2", ShoppingList.packSuggestion(1001))
        assertEquals("-", ShoppingList.packSuggestion(0))
    }

    @Test
    fun `用量降序排列`() {
        val w = palette.indexOfCode("W")
        val r = palette.indexOfCode("R")
        val result = PatternResult(3, 1, intArrayOf(r, w, w), palette, 0)
        val text = ShoppingList.render(result, "t")
        val lines = text.lines()
        val wLine = lines.indexOfFirst { it.startsWith("W\t") }
        val rLine = lines.indexOfFirst { it.startsWith("R\t") }
        assertTrue("白红两行都应存在", wLine >= 0 && rLine >= 0)
        assertTrue("白色 2 粒应排在红色 1 粒之前: w=$wLine r=$rLine", wLine < rLine)
    }
}