package com.pindou.app.ui.theme

import androidx.compose.ui.graphics.Color

// shadcn/ui 设计语言 + AirDeck 品牌色
// 中性色取 shadcn zinc 体系；主色取 AirDeck 图标蓝，辅助色取图标四色块（青/琥珀/紫）
object ClayColors {
    // —— shadcn 中性 token（light） ——
    val Primary = Color(0xFF2563EB)          // AirDeck 蓝（blue-600）
    val PrimaryActive = Color(0xFF1D4ED8)    // blue-700
    val PrimaryDisabled = Color(0xFFE4E4E7)  // zinc-200
    val Ink = Color(0xFF09090B)              // zinc-950
    val Body = Color(0xFF3F3F46)             // zinc-700
    val BodyStrong = Color(0xFF18181B)       // zinc-900
    val Muted = Color(0xFF71717A)            // zinc-500
    val MutedSoft = Color(0xFFA1A1AA)        // zinc-400
    val Hairline = Color(0xFFE4E4E7)         // zinc-200（边框）
    val HairlineSoft = Color(0xFFF4F4F5)     // zinc-100
    val Canvas = Color(0xFFFAFAFA)           // zinc-50 页面底
    val SurfaceSoft = Color(0xFFF4F4F5)
    val SurfaceCard = Color(0xFFFFFFFF)
    val SurfaceStrong = Color(0xFFF4F4F5)
    val SurfaceDark = Color(0xFF09090B)
    val SurfaceDarkElevated = Color(0xFF18181B)
    val OnPrimary = Color(0xFFFFFFFF)
    val OnDark = Color(0xFFFAFAFA)
    val OnDarkSoft = Color(0xFFA1A1AA)
    // —— AirDeck 品牌四色（图标色块） ——
    val BrandBlue = Color(0xFF3B82F6)        // blue-500
    val BrandPink = Color(0xFF8B5CF6)        // violet-500（历史命名保留，现为品牌紫）
    val BrandTeal = Color(0xFF0F766E)        // teal-700
    val BrandLavender = Color(0xFFC4B5FD)    // violet-300
    val BrandPeach = Color(0xFFFDBA74)       // orange-300
    val BrandOchre = Color(0xFFF59E0B)       // amber-500
    val BrandMint = Color(0xFF2DD4BF)        // teal-400
    val BrandCoral = Color(0xFFFB7185)       // rose-400
    val Success = Color(0xFF16A34A)
    val Warning = Color(0xFFD97706)
    val Error = Color(0xFFDC2626)
}
