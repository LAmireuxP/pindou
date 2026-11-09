package com.pindou.app.ui.shadcn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * shadcn/ui 组件层。
 * 颜色全部走 MaterialTheme 的 shadcn token（primary / secondary / outline / muted-foreground），
 * 不在调用点写死颜色。按钮统一 40dp 高、8dp 圆角、14sp medium 字重。
 */

enum class ShVariant { Default, Secondary, Outline, Ghost, Destructive }

private val ShButtonShape = RoundedCornerShape(8.dp)
private val ShButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

/** 主按钮：包一层 Material3 Button，套 shadcn 的尺寸/圆角/字重，保留传入的 colors/shape 覆盖能力 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: ShVariant = ShVariant.Default,
    shape: Shape = ShButtonShape,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ShButtonPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val resolvedColors = colors ?: when (variant) {
        ShVariant.Default -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        ShVariant.Secondary -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        )
        // Outline 用透明底 + 描边，内容色跟前景
        ShVariant.Outline -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
        // Ghost 完全透明，仅点击态
        ShVariant.Ghost -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
        ShVariant.Destructive -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    }
    val resolvedBorder = border ?: if (variant == ShVariant.Outline) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    } else {
        null
    }
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 40.dp),
        enabled = enabled,
        shape = shape,
        colors = resolvedColors,
        elevation = elevation ?: ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        border = resolvedBorder,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** 描边按钮：shadcn `variant="outline"` */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ShButtonShape,
    colors: ButtonColors? = null,
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    contentPadding: PaddingValues = ShButtonPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 40.dp),
        enabled = enabled,
        shape = shape,
        colors = colors ?: ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** 文本按钮：shadcn `variant="ghost"`，用于对话框操作和行内链接 */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ShButtonShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    ),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    MaterialTextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 40.dp),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** shadcn Card：白底 + 1dp 边框 + 12dp 圆角，无阴影 */
@Composable
fun ShCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content,
    )
}

/** 页面大标题 + 说明，统一页面顶部留白 */
@Composable
fun ShPageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 区块标题行，右侧放操作 */
@Composable
fun ShSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        // trailing 整体包一层 Row：否则 SpaceBetween 会把标题、数字、按钮三者均分，数字跑到中间
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

/** shadcn Badge 风格筛选 chip：选中实心主色，未选中描边 */
@Composable
fun ShChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(9999.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** shadcn Input：白底、1dp 边框、8dp 圆角、前置搜索图标 */
@Composable
fun ShSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = trailing,
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** 首页入口卡片的图标方块：44dp、10dp 圆角、淡色底 */
@Composable
fun ShIconTile(
    icon: ImageVector,
    container: Color,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
/** 区块小标题（无右侧操作），用于预览页这类纯展示区块 */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.padding(vertical = 2.dp),
    )
}
