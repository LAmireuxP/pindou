package com.pindou.app.core.palette

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 豆子规格：5mm 普通豆 / 2.6mm 小豆 */
enum class BeadSize(val pitchMm: Double) {
    MM5(5.0),
    MM2_6(2.6);

    companion object {
        fun fromTag(tag: String): BeadSize =
            entries.firstOrNull { it.name.equals(tag, ignoreCase = true) || it.pitchMm.toString() == tag }
                ?: when (tag) {
                    "5mm" -> MM5
                    "2.6mm" -> MM2_6
                    else -> MM5
                }
    }
}

@Serializable
data class BeadColorDto(
    val code: String,
    val hex: String,
    val name: String? = null,
)

@Serializable
data class PaletteDto(
    val brand: String,
    val brandLabel: String,
    val beadSize: String,
    val version: Int = 1,
    val source: String? = null,
    val colors: List<BeadColorDto>,
)

/** 运行时色卡：预计算 Lab，供管线高频最近色查询 */
class BeadPalette(
    val brand: String,
    val brandLabel: String,
    val beadSize: BeadSize,
    val source: String?,
    val colors: List<Entry>,
) {
    data class Entry(val code: String, val name: String?, val argb: Int, val lab: FloatArray) {
        override fun equals(other: Any?): Boolean = other is Entry && other.code == code
        override fun hashCode(): Int = code.hashCode()
    }

    /** 最近色查找；[excludedCodes] 命中的色号跳过（用户买不到的颜色）；找不到返回 -1 */
    fun nearestIndex(lab: FloatArray, excludedCodes: Set<String> = emptySet()): Int {
        var best = -1
        var bestDelta = Double.MAX_VALUE
        for (i in colors.indices) {
            if (colors[i].code in excludedCodes) continue
            val d = Ciede2000.deltaE(lab, colors[i].lab)
            if (d < bestDelta) {
                bestDelta = d
                best = i
            }
        }
        return best
    }

    fun indexOfCode(code: String): Int = colors.indexOfFirst { it.code == code }
}

object PaletteJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** hex "#RRGGBB" 或 "RRGGBB" → 0xFFRRGGBB；非法返回 null */
    fun parseHex(hex: String): Int? {
        val s = hex.removePrefix("#").trim()
        if (s.length != 6 || s.any { Character.digit(it, 16) < 0 }) return null
        return (0xFF shl 24) or s.toInt(16)
    }

    fun parse(text: String): BeadPalette {
        val dto = json.decodeFromString(PaletteDto.serializer(), text)
        val entries = dto.colors.mapNotNull { c ->
            val argb = parseHex(c.hex) ?: return@mapNotNull null
            BeadPalette.Entry(c.code, c.name, argb, ColorConversions.argbToLab(argb))
        }
        require(entries.isNotEmpty()) { "色卡 ${dto.brand} 没有有效颜色" }
        return BeadPalette(
            brand = dto.brand,
            brandLabel = dto.brandLabel,
            beadSize = BeadSize.fromTag(dto.beadSize),
            source = dto.source,
            colors = entries,
        )
    }
}
