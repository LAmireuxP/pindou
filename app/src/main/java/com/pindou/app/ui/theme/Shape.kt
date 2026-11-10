package com.pindou.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// shadcn/ui 圆角：控件 10 / 卡片 16 / 弹层 22。
// 移动端比 web 大两档，触感更软，与淡绿低饱和配色配套。
val ClayShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

val PillShape = RoundedCornerShape(9999.dp)
