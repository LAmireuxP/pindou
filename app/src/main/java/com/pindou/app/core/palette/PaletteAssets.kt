package com.pindou.app.core.palette

import android.content.Context

/** 从 APK assets 加载内置色卡。文件名即资产目录 app/src/main/assets/palettes/ 下的 json。 */
object PaletteAssets {

    const val DIR = "palettes"

    /** 内置色卡文件清单（新增色卡时同步维护） */
    val FILES = listOf(
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
