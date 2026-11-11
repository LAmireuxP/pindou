package com.pindou.app.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {

    @Test
    fun `每次递增同时增加总数和对应来源`() {
        var s = SettingsStore.Stats()
        s = s.bump(SettingsStore.CreationKind.PHOTO)
        s = s.bump(SettingsStore.CreationKind.PIXEL)
        s = s.bump(SettingsStore.CreationKind.PIXEL)
        s = s.bump(SettingsStore.CreationKind.CHART)
        s = s.bump(SettingsStore.CreationKind.TEXT)
        s = s.bump(SettingsStore.CreationKind.BLANK)
        s = s.bump(SettingsStore.CreationKind.BPAT)

        assertEquals(7, s.total)
        assertEquals(1, s.photo)
        assertEquals(2, s.pixel)
        assertEquals(1, s.chart)
        assertEquals(1, s.text)
        assertEquals(1, s.blank)
        assertEquals(1, s.bpat)
    }

    @Test
    fun `count 与各分项一致`() {
        val s = SettingsStore.Stats(
            total = 21,
            photo = 1,
            pixel = 2,
            chart = 3,
            text = 4,
            blank = 5,
            bpat = 6,
        )
        SettingsStore.CreationKind.entries.forEach { kind ->
            assertEquals(s.count(kind), s.bump(kind).let { it.count(kind) - 1 })
        }
        assertEquals(21, s.total)
    }

    @Test
    fun `递增是纯函数不改原对象`() {
        val original = SettingsStore.Stats(total = 3, photo = 3)
        val next = original.bump(SettingsStore.CreationKind.PHOTO)
        assertEquals(3, original.total)
        assertEquals(3, original.photo)
        assertEquals(4, next.total)
        assertEquals(4, next.photo)
    }
}
