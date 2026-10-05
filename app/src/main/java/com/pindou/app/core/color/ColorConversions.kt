package com.pindou.app.core.color

import kotlin.math.pow

/**
 * sRGB (0xFFRRGGBB) → CIE Lab（D65 白点，2° 视角）。
 * 管线中所有色差计算统一使用本实现，保证全局一致。
 */
object ColorConversions {

    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883

    private const val EPS = 216.0 / 24389.0   // (6/29)^3
    private const val KAPPA = 24389.0 / 27.0  // (29/3)^3

    fun argbToLab(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return xyzToLab(
            linear(r) * 0.4124564 + linear(g) * 0.3575761 + linear(b) * 0.1804375,
            linear(r) * 0.2126729 + linear(g) * 0.7151522 + linear(b) * 0.0721750,
            linear(r) * 0.0193339 + linear(g) * 0.1191920 + linear(b) * 0.9503041,
        )
    }

    fun xyzToLab(x: Double, y: Double, z: Double): FloatArray {
        val fx = f(x / XN)
        val fy = f(y / YN)
        val fz = f(z / ZN)
        return floatArrayOf(
            (116.0 * fy - 16.0).toFloat(),
            (500.0 * (fx - fy)).toFloat(),
            (200.0 * (fy - fz)).toFloat(),
        )
    }

    private fun linear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun f(t: Double): Double =
        if (t > EPS) Math.cbrt(t) else (KAPPA * t + 16.0) / 116.0
}
