package com.pindou.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Clay typography 体系映射到 Material3（display 用系统无衬线加粗替代 Plain Black，M8 再评估内置圆润字体）
private val DisplayLarge = TextStyle(
    fontSize = 40.sp, fontWeight = FontWeight(500), lineHeight = 44.sp, letterSpacing = (-1).sp,
)

private val DisplayMedium = TextStyle(
    fontSize = 32.sp, fontWeight = FontWeight(500), lineHeight = 37.sp, letterSpacing = (-0.5).sp,
)

private val HeadlineMedium = TextStyle(
    fontSize = 24.sp, fontWeight = FontWeight(600), lineHeight = 31.sp, letterSpacing = (-0.3).sp,
)

private val TitleLarge = TextStyle(
    fontSize = 18.sp, fontWeight = FontWeight(600), lineHeight = 25.sp,
)

private val TitleMedium = TextStyle(
    fontSize = 16.sp, fontWeight = FontWeight(600), lineHeight = 22.sp,
)

private val TitleSmall = TextStyle(
    fontSize = 14.sp, fontWeight = FontWeight(600), lineHeight = 20.sp,
)

private val BodyLarge = TextStyle(
    fontSize = 16.sp, fontWeight = FontWeight(400), lineHeight = 25.sp,
)

private val BodyMedium = TextStyle(
    fontSize = 14.sp, fontWeight = FontWeight(400), lineHeight = 22.sp,
)

private val BodySmall = TextStyle(
    fontSize = 13.sp, fontWeight = FontWeight(500), lineHeight = 18.sp,
)

private val LabelLarge = TextStyle(
    fontSize = 14.sp, fontWeight = FontWeight(600), lineHeight = 14.sp,
)

private val LabelMedium = TextStyle(
    fontSize = 12.sp, fontWeight = FontWeight(600), lineHeight = 17.sp, letterSpacing = 1.5.sp,
)

private val LabelSmall = TextStyle(
    fontSize = 12.sp, fontWeight = FontWeight(500), lineHeight = 17.sp,
)

val ClayTypography = Typography(
    displayLarge = DisplayLarge,
    displayMedium = DisplayMedium,
    headlineMedium = HeadlineMedium,
    titleLarge = TitleLarge,
    titleMedium = TitleMedium,
    titleSmall = TitleSmall,
    bodyLarge = BodyLarge,
    bodyMedium = BodyMedium,
    bodySmall = BodySmall,
    labelLarge = LabelLarge,
    labelMedium = LabelMedium,
    labelSmall = LabelSmall,
)
