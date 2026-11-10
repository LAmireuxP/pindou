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

    /** 全色系套装：色数必须和套装规格严格相等，且同一套内色号 / hex 都不重复 */
    @Test
    fun `全色系套装色数与规格一致`() {
        val expected = linkedMapOf(
            "all_solid_24.json" to 24,
            "all_solid_48.json" to 48,
            "all_solid_72.json" to 72,
            "all_solid_96.json" to 96,
            "all_solid_120.json" to 120,
            "all_solid_144.json" to 144,
            "all_solid_216.json" to 216,
            "all_solid_221.json" to 221,
            "all_solid_272.json" to 272,
        )
        for ((file, count) in expected) {
            val palette = PaletteJson.parse(File("src/main/assets/palettes/$file").readText())
            assertEquals("$file 色数不符", count, palette.colors.size)
            assertTrue("$file 品牌代号非 ALL 前缀", palette.brand.startsWith("ALL"))
            assertTrue("$file 豆子规格应为 2.6mm", palette.beadSize == BeadSize.MM2_6)
            assertEquals("$file 色号重复", count, palette.colors.map { it.code }.toSet().size)
            assertEquals("$file hex 重复", count, palette.colors.map { it.argb }.toSet().size)
        }
    }

    /** 套装是逐级扩色：每一套都必须是 272 色套装的真子集，换套装不会出现新色号 */
    @Test
    fun `全色系套装逐级包含`() {
        val master = PaletteJson.parse(File("src/main/assets/palettes/all_solid_272.json").readText())
        val masterCodes = master.colors.map { it.code }.toSet()
        listOf(24, 48, 72, 96, 120, 144, 216, 221).forEach { n ->
            val p = PaletteJson.parse(File("src/main/assets/palettes/all_solid_$n.json").readText())
            val extra = p.colors.map { it.code }.filterNot { it in masterCodes }
            assertTrue("all_solid_$n.json 含 272 色之外色号：$extra", extra.isEmpty())
        }
    }
}
