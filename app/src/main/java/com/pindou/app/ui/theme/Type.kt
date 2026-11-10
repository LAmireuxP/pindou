package com.pindou.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// shadcn/ui 字阶：页面标题 28/700，区块标题 18/600，正文 14/400，按钮 14/500
private val DisplayLarge = TextStyle(
    fontSize = 28.sp, fontWeight = FontWeight(700), lineHeight = 34.sp, letterSpacing = (-0.6).sp,
)

private val DisplayMedium = TextStyle(
    fontSize = 24.sp, fontWeight = FontWeight(700), lineHeight = 30.sp, letterSpacing = (-0.4).sp,
)

private val HeadlineMedium = TextStyle(
    fontSize = 20.sp, fontWeight = FontWeight(600), lineHeight = 26.sp, letterSpacing = (-0.2).sp,
)

private val TitleLarge = TextStyle(
    fontSize = 17.sp, fontWeight = FontWeight(600), lineHeight = 24.sp,
)

private val TitleMedium = TextStyle(
    fontSize = 15.sp, fontWeight = FontWeight(600), lineHeight = 21.sp,
)

private val TitleSmall = TextStyle(
    fontSize = 13.sp, fontWeight = FontWeight(600), lineHeight = 19.sp,
)

private val BodyLarge = TextStyle(
    fontSize = 16.sp, fontWeight = FontWeight(400), lineHeight = 24.sp,
)

private val BodyMedium = TextStyle(
    fontSize = 14.sp, fontWeight = FontWeight(400), lineHeight = 21.sp,
)

private val BodySmall = TextStyle(
    fontSize = 12.sp, fontWeight = FontWeight(400), lineHeight = 18.sp,
)

private val LabelLarge = TextStyle(
    fontSize = 14.sp, fontWeight = FontWeight(500), lineHeight = 20.sp,
)

private val LabelMedium = TextStyle(
    fontSize = 12.sp, fontWeight = FontWeight(500), lineHeight = 16.sp,
)

private val LabelSmall = TextStyle(
    fontSize = 11.sp, fontWeight = FontWeight(500), lineHeight = 16.sp,
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
