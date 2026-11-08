package com.pindou.app.core.pattern

import com.pindou.app.core.palette.PaletteJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BpatCodecTest {

    private val colors = listOf(
        BpatCodec.BpatColorSource("A1", "#FF0000", "大红"),
        BpatCodec.BpatColorSource("B2", "#00FF00", "草绿"),
    )

    private fun samplePages(): List<IntArray> = listOf(
        IntArray(9) { if (it % 2 == 0) 0 else 1 },
        IntArray(9) { -1 }, // 第二页全空格
    )

    @Test
    fun `编码解码往返`() {
        val json = BpatCodec.encode(
            title = "测试图纸",
            album = "专辑一",
            brand = "MARD",
            beadSizeTag = "MM5",
            width = 3,
            height = 3,
            pages = samplePages(),
            paletteColors = colors,
            savedAt = 1234567890L,
        )
        val data = BpatCodec.decode(json)
        assertEquals("bpat-1", Regex(""""format":"([^"]+)"""").find(json)!!.groupValues[1])
        assertEquals("测试图纸", data.title)
        assertEquals("专辑一", data.album)
        assertEquals("MARD", data.brand)
        assertEquals(3, data.width)
        assertEquals(2, data.pages.size)
        assertEquals(1234567890L, data.savedAt)
        // 第 1 页逐格还原
        val p0 = data.pages[0]
        for (i in 0 until 9) assertEquals(if (i % 2 == 0) 0 else 1, p0[i])
        // 色表还原（hex 转 argb）
        assertEquals(2, data.colors.size)
        assertEquals(0xFFFF0000.toInt(), data.colors[0].hex)
        assertEquals("A1", data.colors[0].code)
        assertEquals("大红", data.colors[0].name)
    }

    @Test
    fun `空格编码为 255 且解码还原 -1`() {
        val json = BpatCodec.encode("", "", "MARD", "MM5", 3, 3, samplePages(), colors)
        val data = BpatCodec.decode(json)
        assertTrue(data.pages[1].all { it == -1 })
        // 原始字节里第 2 页首格确实是 255
        val raw = java.util.Base64.getDecoder().decode(
            Regex(""""pixels":\["[^"]+","([^"]+)"]""").find(json)!!.groupValues[1],
        )
        assertEquals(255, raw[0].toInt() and 0xFF)
    }

    @Test
    fun `越界索引与负数一律按空格落盘`() {
        val page = intArrayOf(-5, 0, 99, 1, -1, 0, 1, 0, 1)
        val data = BpatCodec.decode(
            BpatCodec.encode("", "", "MARD", "MM5", 3, 3, listOf(page), colors),
        )
        // -5 越界 → 空格；99 ≥ 色表大小 → 空格
        assertEquals(-1, data.pages[0][0])
        assertEquals(0, data.pages[0][1])
        assertEquals(-1, data.pages[0][2])
    }

    @Test
    fun `格式不符与非法 JSON 抛 IllegalArgumentException`() {
        try {
            BpatCodec.decode("""{"format":"other-1","w":3,"h":3,"colors":[],"pixels":[]}""")
            throw AssertionError("应当抛出 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            BpatCodec.decode("不是 json")
            throw AssertionError("应当抛出 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `解码时忽略未知字段`() {
        val json = BpatCodec.encode("", "", "MARD", "MM5", 3, 3, samplePages(), colors)
        val withExtra = json.replaceFirst("{", """{"vendor":{"app":"x"},""")
        val data = BpatCodec.decode(withExtra)
        assertEquals(2, data.pages.size)
    }

    @Test
    fun `hex 解析非法返回 null`() {
        assertNull(PaletteJson.parseHex("#ZZZZZZ"))
        assertEquals(0xFF112233.toInt(), PaletteJson.parseHex("#112233"))
    }
}
