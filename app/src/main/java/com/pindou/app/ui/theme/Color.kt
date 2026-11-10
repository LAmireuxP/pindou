package com.pindou.app.ui.theme

import androidx.compose.ui.graphics.Color

// shadcn/ui 设计语言 + 天蓝品牌色
//
// 取色直接对齐应用图标：图标是白底 + #5FBAFF 亮蓝标记 + #E5E9F1 浅灰蓝
// 网格线。界面主色取同色相压深两档（#3D9BEA），保证白字按钮对比度；
// 中性色全部带一点蓝相，和图标的白/浅灰蓝是同一个色温，页面上不会
// 出现"绿色按钮配蓝色图标"的割裂感。
object ClayColors {
    // —— 中性 token（light） ——
    val Primary = Color(0xFF5FBAFF)          // 与图标同色：主按钮底色、选中态
    val PrimaryActive = Color(0xFF3D9BEA)    // 按下态
    val PrimaryInk = Color(0xFF136FB4)       // 浅底上的文字/图标（对白底 4.9:1）
    val PrimaryDisabled = Color(0xFFDCEBF9)
    val Ink = Color(0xFF16202B)              // 标题
    val Body = Color(0xFF3D4A57)             // 正文
    val BodyStrong = Color(0xFF1F2B37)
    val Muted = Color(0xFF75828F)            // 次要文字
    val MutedSoft = Color(0xFFA6B1BC)
    val Hairline = Color(0xFFE5E9F1)         // 1px 边框（图标网格线同色）
    val HairlineSoft = Color(0xFFF0F3F8)     // 分隔线
    val Canvas = Color(0xFFF7FAFD)           // 页面底
    val SurfaceSoft = Color(0xFFEEF4FB)      // 次级底
    val SurfaceCard = Color(0xFFFFFFFF)
    val SurfaceStrong = Color(0xFFE7EEF7)
    val SurfaceDark = Color(0xFF0E1419)
    val SurfaceDarkElevated = Color(0xFF18212A)
    val OnPrimary = Color(0xFF07293F)        // 亮蓝底上的深藏青字，白字对比度只有 2.2:1
    val OnDark = Color(0xFFF4F8FC)
    val OnDarkSoft = Color(0xFFA6B1BC)

    // —— 功能色：同一明度带，饱和度全部压到 45% 左右 ——
    val BrandBlue = Color(0xFF5FBAFF)        // 图标本身那个蓝
    val BrandPink = Color(0xFFD98BA0)
    val BrandTeal = Color(0xFF3FA085)
    val BrandLavender = Color(0xFFA9BCDA)
    val BrandPeach = Color(0xFFE2BE96)
    val BrandOchre = Color(0xFFC9A24E)
    val BrandMint = Color(0xFF63B59A)
    val BrandCoral = Color(0xFFD28379)
    val Success = Color(0xFF3E9B72)
    val Warning = Color(0xFFC9912A)
    val Error = Color(0xFFCF5346)

    // —— 图标底色（极浅，白底上只作为色相提示） ——
    val TintGreen = Color(0xFFE8F3FE)        // 名字沿用，色值已改为品牌淡蓝
    val TintMint = Color(0xFFE6F4F0)
    val TintButter = Color(0xFFFBF3E2)
    val TintSky = Color(0xFFE4F1FE)
    val TintRose = Color(0xFFFBEDF1)
}
