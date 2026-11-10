package com.pindou.app.core.palette

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

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
            val d = matchScore(lab, colors[i])
            if (d < bestDelta) {
                bestDelta = d
                best = i
            }
        }
        return best
    }

    /**
     * 色卡匹配评分 = CIEDE2000 + 中性色相收敛。
     *
     * CIEDE2000 在低彩度区对色相几乎不敏感：浅灰（a≈b≈0）和浅蓝灰
     * （a≈-1, b≈-6）算出来的 ΔE 只有 2~3，于是内耳的灰会被选成冷灰/蓝灰，
     * 拼出来就是一圈脏蓝点。这里对"源色接近中性"的情况按目标彩度加罚：
     * 源彩度越低、目标偏色越重，罚分越高，够把蓝灰压到真正的中性灰后面。
     *
     * 彩度 ≥ [NEUTRAL_CHROMA] 的源色（粉、绿、蓝）走原 ΔE，不受影响。
     */
    fun matchScore(src: FloatArray, entry: Entry): Double {
        val d = Ciede2000.deltaE(src, entry.lab)
        val sa = src[1]
        val sb = src[2]
        val sc = sqrt(sa * sa + sb * sb)
        if (sc >= NEUTRAL_CHROMA) return d

        val da = entry.lab[1]
        val db = entry.lab[2]
        val dc = sqrt(da * da + db * db)
        val drift = (dc - sc).coerceAtLeast(0f)
        if (drift <= 0f) return d
        // sc=0 满权重；sc 接近阈值时衰减到 0，避免浅粉被误判成"灰"
        val weight = NEUTRAL_PENALTY * (1f - sc / NEUTRAL_CHROMA)
        return d + drift * weight
    }

    private companion object {
        /** 低于此彩度视为中性色（灰/白/黑），需要防色相漂移 */
        const val NEUTRAL_CHROMA = 7f

        /** 每单位彩度差的罚分系数；目标偏色 6 时约 +5.4 ΔE */
        const val NEUTRAL_PENALTY = 0.9f
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
