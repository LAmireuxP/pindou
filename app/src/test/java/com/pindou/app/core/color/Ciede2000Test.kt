package com.pindou.app.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ciede2000Test {

    private fun loadReferenceRows(): List<DoubleArray> {
        val stream = javaClass.getResourceAsStream("/ciede2000_reference_data.txt")
            ?: error("找不到 ciede2000_reference_data.txt")
        return stream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1) // 表头
            .map { line -> line.split(",").map { it.trim().toDouble() }.toDoubleArray() }
    }

    @Test
    fun `sharma 标准用例全部通过`() {
        println("Ciede2000 loaded from: " + Ciede2000::class.java.protectionDomain.codeSource.location)
        val rows = loadReferenceRows()
        assertEquals(34, rows.size)
        var maxError = 0.0
        for (row in rows) {
            val actual = Ciede2000.deltaE(row[0], row[1], row[2], row[3], row[4], row[5])
            val expected = row[6]
            val error = kotlin.math.abs(actual - expected)
            maxError = maxOf(maxError, error)
            assertTrue(
                "用例 (${row[0]},${row[1]},${row[2]}) vs (${row[3]},${row[4]},${row[5]}): 期望 $expected 实际 $actual",
                error < 1e-4,
            )
        }
        println("CIEDE2000 最大误差: $maxError")
    }

    @Test
    fun `对称性`() {
        val rows = loadReferenceRows()
        for (row in rows) {
            val forward = Ciede2000.deltaE(row[0], row[1], row[2], row[3], row[4], row[5])
            val backward = Ciede2000.deltaE(row[3], row[4], row[5], row[0], row[1], row[2])
            assertEquals(forward, backward, 1e-12)
        }
    }

    @Test
    fun `相同颜色色差为 0`() {
        assertEquals(0.0, Ciede2000.deltaE(50.0, 2.5, 0.0, 50.0, 2.5, 0.0), 1e-12)
    }
}
