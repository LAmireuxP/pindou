package com.pindou.app.core.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 校验随包分发的色卡资产：数量下限、色号唯一、hex 全部合法。
 * 直接读 app/src/main/assets/palettes（单元测试工作目录 = app 模块）。
 */
class PaletteAssetsIntegrityTest {

    private val minColors = mapOf(
        "mard.json" to 280, "coco.json" to 280, "manman.json" to 280,
        "panpan.json" to 280, "mixiaowo.json" to 280,
        "perler.json" to 90, "hama.json" to 80,
        "artkal_s.json" to 160, "artkal_mini.json" to 210,
    )

    @Test
    fun `内置色卡全部合法`() {
        for ((file, min) in minColors) {
            val text = File("src/main/assets/palettes/$file").readText()
            val palette = PaletteJson.parse(text)
            assertTrue("$file 颜色数 ${palette.colors.size} < $min", palette.colors.size >= min)
            val codes = palette.colors.map { it.code }
            assertEquals("$file 色号有重复", codes.size, codes.toSet().size)
            assertTrue(
                "$file 存在 Lab 非有限值",
                palette.colors.all { c -> c.lab.all { it.isFinite() } },
            )
        }
    }

    @Test
    fun `文件清单与资产目录一致`() {
        val onDisk = File("src/main/assets/palettes").list { _, name -> name.endsWith(".json") }!!.toSet()
        assertEquals(onDisk, PaletteAssets.FILES.toSet())
    }
}
