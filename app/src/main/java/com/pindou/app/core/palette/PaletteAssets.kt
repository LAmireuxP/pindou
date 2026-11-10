package com.pindou.app.core.palette

import android.content.Context

/** 从 APK assets 加载内置色卡。文件名即资产目录 app/src/main/assets/palettes/ 下的 json。 */
object PaletteAssets {

    const val DIR = "palettes"

    /** 内置色卡文件清单（新增色卡时同步维护） */
    val FILES = listOf(
        // 全色系套装（2.6mm 融合豆实物色卡，24→272 逐级扩色）
        "all_solid_24.json", "all_solid_48.json", "all_solid_72.json",
        "all_solid_96.json", "all_solid_120.json", "all_solid_144.json",
        "all_solid_216.json", "all_solid_221.json", "all_solid_272.json",
        "mard.json", "coco.json", "manman.json", "panpan.json", "mixiaowo.json",
        "perler.json", "hama.json", "artkal_s.json", "artkal_mini.json",
    )

    fun loadAll(context: Context): List<BeadPalette> =
        FILES.mapNotNull { file ->
            runCatching {
                context.assets.open("$DIR/$file").bufferedReader().use { PaletteJson.parse(it.readText()) }
            }.getOrNull()
        }
}
