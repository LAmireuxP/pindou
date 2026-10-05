package com.pindou.app.core.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CIEDE2000 色差公式（Sharma 2005 实现注记版）。
 * 单元测试覆盖 University of Rochester 的 34 组标准用例。
 */
object Ciede2000 {

    fun deltaE(lab1: FloatArray, lab2: FloatArray): Double =
        deltaE(
            lab1[0].toDouble(), lab1[1].toDouble(), lab1[2].toDouble(),
            lab2[0].toDouble(), lab2[1].toDouble(), lab2[2].toDouble(),
        )

    fun deltaE(
        l1: Double, a1: Double, b1: Double,
        l2: Double, a2: Double, b2: Double,
    ): Double {
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val cAvg = (c1 + c2) / 2.0
        val g = 0.5 * (1.0 - sqrt(cAvg.pow(7) / (cAvg.pow(7) + 25.0.pow(7))))
        val a1p = (1.0 + g) * a1
        val a2p = (1.0 + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)
        val h1p = hueAngle(a1p, b1)
        val h2p = hueAngle(a2p, b2)

        val dLp = l2 - l1
        val dCp = c2p - c1p

        var dhp = 0.0
        if (c1p * c2p != 0.0) {
            dhp = h2p - h1p
            when {
                dhp > 180.0 -> dhp -= 360.0
                dhp < -180.0 -> dhp += 360.0
            }
        }
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2.0))

        val lAvg = (l1 + l2) / 2.0
        val cpAvg = (c1p + c2p) / 2.0

        val hpAvg = if (c1p * c2p == 0.0) {
            h1p + h2p
        } else {
            val sum = h1p + h2p
            when {
                abs(h1p - h2p) <= 180.0 -> sum / 2.0
                sum < 360.0 -> (sum + 360.0) / 2.0
                else -> (sum - 360.0) / 2.0
            }
        }

        // 注意：多行算术表达式的运算符必须放在行尾；放在行首时 K2 会把每行解析成独立语句（求值后丢弃）
        val t = 1.0 - 0.17 * cos(Math.toRadians(hpAvg - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * hpAvg)) +
            0.32 * cos(Math.toRadians(3.0 * hpAvg + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * hpAvg - 63.0))
        val dTheta = 30.0 * exp(-((hpAvg - 275.0) / 25.0).pow(2))
        val rc = 2.0 * sqrt(cpAvg.pow(7) / (cpAvg.pow(7) + 25.0.pow(7)))
        val sl = 1.0 + (0.015 * (lAvg - 50.0).pow(2)) / sqrt(20.0 + (lAvg - 50.0).pow(2))
        val sc = 1.0 + 0.045 * cpAvg
        val sh = 1.0 + 0.015 * cpAvg * t
        val rt = -sin(Math.toRadians(2.0 * dTheta)) * rc

        val termL = dLp / sl
        val termC = dCp / sc
        val termH = dHp / sh
        return sqrt(termL * termL + termC * termC + termH * termH + rt * termC * termH)
    }

    private fun hueAngle(ap: Double, b: Double): Double {
        if (ap == 0.0 && b == 0.0) return 0.0
        val deg = Math.toDegrees(atan2(b, ap))
        return if (deg >= 0.0) deg else deg + 360.0
    }
}
