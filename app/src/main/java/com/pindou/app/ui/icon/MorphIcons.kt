package com.pindou.app.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 24dp 线性图标，几何数据取自 Lucide（Morphicons 的默认图标集），ISC 许可。
 *
 * 每个图标都是一个 [ImageVector]，由 SVG path 在首次使用时解析；统一 2dp 描边、
 * 圆头圆角，交给 Material 的 Icon(tint=...) 上色，不依赖 material-icons-extended。
 */
object MorphIcons {
    val ArrowLeft: ImageVector by lazy {
        strokeIcon(
            name = "ArrowLeft",
            "m12 19-7-7 7-7",
            "M19 12H5",
        )
    }

    val ArrowRight: ImageVector by lazy {
        strokeIcon(
            name = "ArrowRight",
            "M5 12h14",
            "m12 5 7 7-7 7",
        )
    }

    val ChevronLeft: ImageVector by lazy {
        strokeIcon(
            name = "ChevronLeft",
            "m15 18-6-6 6-6",
        )
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon(
            name = "ChevronRight",
            "m9 18 6-6-6-6",
        )
    }

    val ArrowUp: ImageVector by lazy {
        strokeIcon(
            name = "ArrowUp",
            "m5 12 7-7 7 7",
            "M12 19V5",
        )
    }

    val ArrowDown: ImageVector by lazy {
        strokeIcon(
            name = "ArrowDown",
            "M12 5v14",
            "m19 12-7 7-7-7",
        )
    }

    val Check: ImageVector by lazy {
        strokeIcon(
            name = "Check",
            "M20 6 9 17l-5-5",
        )
    }

    val Star: ImageVector by lazy {
        strokeIcon(
            name = "Star",
            "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z",
        )
    }

    val Minus: ImageVector by lazy {
        strokeIcon(
            name = "Minus",
            "M5 12h14",
        )
    }

    val CircleCheck: ImageVector by lazy {
        strokeIcon(
            name = "CircleCheck",
            "m16 9-5.5 5.5L8 12",
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
        )
    }

    val X: ImageVector by lazy {
        strokeIcon(
            name = "X",
            "M18 6 6 18",
            "m6 6 12 12",
        )
    }

    val Search: ImageVector by lazy {
        strokeIcon(
            name = "Search",
            "m21 21-4.34-4.34",
            "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0",
        )
    }

    val MoreVertical: ImageVector by lazy {
        strokeIcon(
            name = "MoreVertical",
            "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
            "M11 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
            "M11 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
        )
    }

    val Undo: ImageVector by lazy {
        strokeIcon(
            name = "Undo",
            "M9 14 4 9l5-5",
            "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11",
        )
    }

    val Redo: ImageVector by lazy {
        strokeIcon(
            name = "Redo",
            "m15 14 5-5-5-5",
            "M20 9H9.5A5.5 5.5 0 0 0 4 14.5A5.5 5.5 0 0 0 9.5 20H13",
        )
    }

    val RotateCcw: ImageVector by lazy {
        strokeIcon(
            name = "RotateCcw",
            "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
            "M3 3v5h5",
        )
    }

    val RotateCw: ImageVector by lazy {
        strokeIcon(
            name = "RotateCw",
            "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8",
            "M21 3v5h-5",
        )
    }

    val RefreshCcw: ImageVector by lazy {
        strokeIcon(
            name = "RefreshCcw",
            "M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
            "M3 3v5h5",
            "M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16",
            "M16 16h5v5",
        )
    }

    val Download: ImageVector by lazy {
        strokeIcon(
            name = "Download",
            "M12 15V3",
            "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
            "m7 10 5 5 5-5",
        )
    }

    val Copy: ImageVector by lazy {
        strokeIcon(
            name = "Copy",
            "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
            "M10 8H20A2 2 0 0 1 22 10V20A2 2 0 0 1 20 22H10A2 2 0 0 1 8 20V10A2 2 0 0 1 10 8Z",
        )
    }

    val Trash: ImageVector by lazy {
        strokeIcon(
            name = "Trash",
            "M10 11v6",
            "M14 11v6",
            "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
            "M3 6h18",
            "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
        )
    }

    val Lock: ImageVector by lazy {
        strokeIcon(
            name = "Lock",
            "M7 11V7a5 5 0 0 1 10 0v4",
            "M5 11H19A2 2 0 0 1 21 13V20A2 2 0 0 1 19 22H5A2 2 0 0 1 3 20V13A2 2 0 0 1 5 11Z",
        )
    }

    val LockOpen: ImageVector by lazy {
        strokeIcon(
            name = "LockOpen",
            "M7 11V7a5 5 0 0 1 9.9-1",
            "M5 11H19A2 2 0 0 1 21 13V20A2 2 0 0 1 19 22H5A2 2 0 0 1 3 20V13A2 2 0 0 1 5 11Z",
        )
    }

    val Eye: ImageVector by lazy {
        strokeIcon(
            name = "Eye",
            "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        )
    }

    val EyeOff: ImageVector by lazy {
        strokeIcon(
            name = "EyeOff",
            "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49",
            "M14.084 14.158a3 3 0 0 1-4.242-4.242",
            "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143",
            "m2 2 20 20",
        )
    }

    val House: ImageVector by lazy {
        strokeIcon(
            name = "House",
            "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
            "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        )
    }

    val CirclePlus: ImageVector by lazy {
        strokeIcon(
            name = "CirclePlus",
            "M8 12h8",
            "M12 8v8",
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
        )
    }

    val Settings: ImageVector by lazy {
        strokeIcon(
            name = "Settings",
            "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        )
    }

    val Grid: ImageVector by lazy {
        strokeIcon(
            name = "Grid",
            "M3 9h18",
            "M3 15h18",
            "M9 3v18",
            "M15 3v18",
            "M5 3H19A2 0 0 0 1 21 3V21A2 0 0 0 1 19 21H5A2 0 0 0 1 3 21V3A2 0 0 0 1 5 3Z",
        )
    }

    val LayoutGrid: ImageVector by lazy {
        strokeIcon(
            name = "LayoutGrid",
            "M4 3H9A1 0 0 0 1 10 3V10A1 0 0 0 1 9 10H4A1 0 0 0 1 3 10V3A1 0 0 0 1 4 3Z",
            "M15 3H20A1 0 0 0 1 21 3V10A1 0 0 0 1 20 10H15A1 0 0 0 1 14 10V3A1 0 0 0 1 15 3Z",
            "M15 14H20A1 0 0 0 1 21 14V21A1 0 0 0 1 20 21H15A1 0 0 0 1 14 21V14A1 0 0 0 1 15 14Z",
            "M4 14H9A1 0 0 0 1 10 14V21A1 0 0 0 1 9 21H4A1 0 0 0 1 3 21V14A1 0 0 0 1 4 14Z",
        )
    }

    val Palette: ImageVector by lazy {
        strokeIcon(
            name = "Palette",
            "M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z",
            "M13 6.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0",
            "M17 10.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0",
            "M6 12.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0",
            "M8 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0",
        )
    }

    val Type: ImageVector by lazy {
        strokeIcon(
            name = "Type",
            "M12 4v16",
            "M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2",
            "M9 20h6",
        )
    }

    val Plus: ImageVector by lazy {
        strokeIcon(
            name = "Plus",
            "M5 12h14",
            "M12 5v14",
        )
    }

    val ImagePlus: ImageVector by lazy {
        strokeIcon(
            name = "ImagePlus",
            "M16 5h6",
            "M19 2v6",
            "M21 11.5V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7.5",
            "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
            "M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
        )
    }

    val FolderOpen: ImageVector by lazy {
        strokeIcon(
            name = "FolderOpen",
            "m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2",
        )
    }

    val Brush: ImageVector by lazy {
        strokeIcon(
            name = "Brush",
            "m11 10 3 3",
            "M6.5 21A3.5 3.5 0 1 0 3 17.5a2.62 2.62 0 0 1-.708 1.792A1 1 0 0 0 3 21z",
            "M9.969 17.031 21.378 5.624a1 1 0 0 0-3.002-3.002L6.967 14.031",
        )
    }

    val PaintBucket: ImageVector by lazy {
        strokeIcon(
            name = "PaintBucket",
            "M11 7 6 2",
            "M18.992 12H2.041",
            "M21.145 18.38A3.34 3.34 0 0 1 20 16.5a3.3 3.3 0 0 1-1.145 1.88c-.575.46-.855 1.02-.855 1.595A2 2 0 0 0 20 22a2 2 0 0 0 2-2.025c0-.58-.285-1.13-.855-1.595",
            "m8.5 4.5 2.148-2.148a1.205 1.205 0 0 1 1.704 0l7.296 7.296a1.205 1.205 0 0 1 0 1.704l-7.592 7.592a3.615 3.615 0 0 1-5.112 0l-3.888-3.888a3.615 3.615 0 0 1 0-5.112L5.67 7.33",
        )
    }

    val Eraser: ImageVector by lazy {
        strokeIcon(
            name = "Eraser",
            "M21 21H8a2 2 0 0 1-1.42-.587l-3.994-3.999a2 2 0 0 1 0-2.828l10-10a2 2 0 0 1 2.829 0l5.999 6a2 2 0 0 1 0 2.828L12.834 21",
            "m5.082 11.09 8.828 8.828",
        )
    }

    val Pipette: ImageVector by lazy {
        strokeIcon(
            name = "Pipette",
            "m12 9-8.414 8.414A2 2 0 0 0 3 18.828v1.344a2 2 0 0 1-.586 1.414A2 2 0 0 1 3.828 21h1.344a2 2 0 0 0 1.414-.586L15 12",
            "m18 9 .4.4a1 1 0 1 1-3 3l-3.8-3.8a1 1 0 1 1 3-3l.4.4 3.4-3.4a1 1 0 1 1 3 3z",
            "m2 22 .414-.414",
        )
    }

    val Hand: ImageVector by lazy {
        strokeIcon(
            name = "Hand",
            "M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2",
            "M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2",
            "M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8",
            "M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15",
        )
    }

    val SwapHorizontal: ImageVector by lazy {
        strokeIcon(
            name = "SwapHorizontal",
            "M8 3 4 7l4 4",
            "M4 7h16",
            "m16 21 4-4-4-4",
            "M20 17H4",
        )
    }

    val FlipHorizontal: ImageVector by lazy {
        strokeIcon(
            name = "FlipHorizontal",
            "M8 3H5a2 2 0 0 0-2 2v14c0 1.1.9 2 2 2h3",
            "M16 3h3a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-3",
            "M12 20v2",
            "M12 14v2",
            "M12 8v2",
            "M12 2v2",
        )
    }

}

private fun strokeIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
