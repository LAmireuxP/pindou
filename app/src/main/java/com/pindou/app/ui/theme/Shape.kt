package com.pindou.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// shadcn/ui 圆角：sm 6 / md 8 / lg 8（按钮输入）/ xl 12（卡片）/ 2xl 16（弹层）
val ClayShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

val PillShape = RoundedCornerShape(9999.dp)
