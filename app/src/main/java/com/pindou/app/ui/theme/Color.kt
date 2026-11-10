package com.pindou.app.ui.theme

import androidx.compose.ui.graphics.Color

// shadcn/ui 设计语言 + 淡绿品牌色
//
// 取色原则：中性色带一点点暖（不用纯灰，纯灰在 OLED 上发冷），
// 主色用低饱和的叶绿（对应拼豆底板/豆子的天然质感），
// 所有强调色都压在同一明度带里——页面上不会出现"跳出来"的色块。
object ClayColors {
    // —— 中性 token（light） ——
    val Primary = Color(0xFF5E9134)          // 叶绿 600：主按钮、选中态
    val PrimaryActive = Color(0xFF4A7528)    // 按下态
    val PrimaryDisabled = Color(0xFFE6E8DF)
    val Ink = Color(0xFF171A14)              // 标题
    val Body = Color(0xFF3F443A)             // 正文
    val BodyStrong = Color(0xFF23261F)
    val Muted = Color(0xFF7B8172)            // 次要文字
    val MutedSoft = Color(0xFFA3A899)
    val Hairline = Color(0xFFE4E6DC)         // 1px 边框
    val HairlineSoft = Color(0xFFF1F2EA)     // 分隔线
    val Canvas = Color(0xFFF8F8F4)           // 页面底
    val SurfaceSoft = Color(0xFFF2F3EB)      // 次级底
    val SurfaceCard = Color(0xFFFFFFFF)
    val SurfaceStrong = Color(0xFFEBEDE2)
    val SurfaceDark = Color(0xFF101208)
    val SurfaceDarkElevated = Color(0xFF1B1E15)
    val OnPrimary = Color(0xFFFFFFFF)
    val OnDark = Color(0xFFF7F8F2)
    val OnDarkSoft = Color(0xFFA3A899)

    // —— 功能色：同一明度带，饱和度全部压到 45% 左右 ——
    val BrandBlue = Color(0xFF4E8DA8)
    val BrandPink = Color(0xFFC98A9C)
    val BrandTeal = Color(0xFF4F8F79)
    val BrandLavender = Color(0xFFB9C4DA)
    val BrandPeach = Color(0xFFE2BE96)
    val BrandOchre = Color(0xFFC7A24A)
    val BrandMint = Color(0xFF7FB68F)
    val BrandCoral = Color(0xFFD08A80)
    val Success = Color(0xFF4E8A34)
    val Warning = Color(0xFFC08A2A)
    val Error = Color(0xFFC0503C)

    // —— 图标底色（极浅，白底上只作为色相提示） ——
    val TintGreen = Color(0xFFEDF4E2)
    val TintMint = Color(0xFFE4F1EA)
    val TintButter = Color(0xFFF7F0DC)
    val TintSky = Color(0xFFE6EFF3)
    val TintRose = Color(0xFFF7E9EA)
}
