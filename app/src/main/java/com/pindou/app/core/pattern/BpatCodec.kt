package com.pindou.app.core.pattern

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * .bpat 图纸文件编解码（bpat-1 通用交换格式）。
 *
 * 结构：
 * ```
 * {
 *   "format": "bpat-1",
 *   "title": "...", "album": "...",
 *   "brand": "MARD", "beadSize": "MM5",
 *   "w": 29, "h": 29,
 *   "colors": [{"code":"A1","hex":"#RRGGBB","name":"..."}],   // 全项目去重色表
 *   "pixels": ["<base64>"],   // 每页一段；每格 1 字节 = colors 下标，255 = 空格
 *   "savedAt": 1700000000000
 * }
 * ```
 * 纯 Kotlin/JVM，可单测。
 */
object BpatCodec {

    const val FORMAT = "bpat-1"
    const val EMPTY = 255.toByte()

    @Serializable
    private data class ColorDto(
        val code: String,
        val hex: String,
        val name: String? = null,
    )

    @Serializable
    private data class BpatDto(
        val format: String,
        val title: String = "",
        val album: String = "",
        val brand: String = "",
        val beadSize: String = "MM5",
        val w: Int,
        val h: Int,
        val colors: List<ColorDto>,
        val pixels: List<String>,
        val savedAt: Long = 0,
    )

    /** 解码后的图纸数据：cells 为色卡下标（-1 = 空格），palette 配套 */
    data class BpatData(
        val title: String,
        val album: String,
        val brand: String,
        val beadSizeTag: String,
        val width: Int,
        val height: Int,
        val colors: List<ColorDto>,
        val pages: List<IntArray>,
        val savedAt: Long,
    ) {
        data class ColorDto(val code: String, val hex: Int, val name: String?)
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 编码：pages 每页格子（-1 = 空格），paletteColors 需覆盖所有非负索引 */
    fun encode(
        title: String,
        album: String,
        brand: String,
        beadSizeTag: String,
        width: Int,
        height: Int,
        pages: List<IntArray>,
        paletteColors: List<BpatColorSource>,
        savedAt: Long = System.currentTimeMillis(),
    ): String {
        require(pages.isNotEmpty()) { "页数据为空" }
        val size = width * height
        val pixels = pages.map { page ->
            require(page.size == size) { "页数据尺寸与网格不符" }
            val bytes = ByteArray(size)
            for (i in page.indices) {
                val v = page[i]
                bytes[i] = if (v < 0 || v >= paletteColors.size) EMPTY else v.toByte()
            }
            Base64.getEncoder().encodeToString(bytes)
        }
        val dto = BpatDto(
            format = FORMAT,
            title = title,
            album = album,
            brand = brand,
            beadSize = beadSizeTag,
            w = width,
            h = height,
            colors = paletteColors.map { ColorDto(it.code, it.hex, it.name) },
            pixels = pixels,
            savedAt = savedAt,
        )
        return json.encodeToString(BpatDto.serializer(), dto)
    }

    /** 编码用的色源（避免 core 依赖运行时色卡类型之外的抽象） */
    data class BpatColorSource(val code: String, val hex: String, val name: String?)

    /** 解码；格式不符抛 IllegalArgumentException */
    fun decode(text: String): BpatData {
        val dto = try {
            json.decodeFromString(BpatDto.serializer(), text)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的 .bpat 图纸文件", e)
        }
        require(dto.format == FORMAT) { "不支持的图纸格式：${dto.format}" }
        require(dto.w in 1..200 && dto.h in 1..200) { "网格尺寸超出范围 ${dto.w}×${dto.h}" }
        require(dto.colors.isNotEmpty()) { "色表为空" }
        require(dto.pixels.isNotEmpty()) { "页数据为空" }
        val size = dto.w * dto.h
        val pages = dto.pixels.mapIndexed { pi, b64 ->
            val bytes = try {
                Base64.getDecoder().decode(b64)
            } catch (e: Exception) {
                throw IllegalArgumentException("第 ${pi + 1} 页像素数据损坏", e)
            }
            require(bytes.size == size) { "第 ${pi + 1} 页数据尺寸不符" }
            IntArray(size) { i ->
                val v = bytes[i].toInt() and 0xFF
                if (v == 255 || v >= dto.colors.size) -1 else v
            }
        }
        val colors = dto.colors.map { c ->
            val hex = com.pindou.app.core.palette.PaletteJson.parseHex(c.hex)
                ?: error("非法颜色值：${c.hex}")
            BpatData.ColorDto(c.code, hex, c.name)
        }
        return BpatData(
            title = dto.title,
            album = dto.album,
            brand = dto.brand,
            beadSizeTag = dto.beadSize,
            width = dto.w,
            height = dto.h,
            colors = colors,
            pages = pages,
            savedAt = dto.savedAt,
        )
    }
}
