package com.pindou.app.core.pattern

import com.pindou.app.core.color.ColorConversions
import com.pindou.app.core.palette.BeadPalette

/**
 * 小亮斑救援：把"嵌在深色小块里的高光"点回白豆。
 *
 * 78 格下漫画眼睛只有 3~4 格宽，眼球里的白高光直径不足一格。面积平均会把
 * 它摊进整格灰，量化再取最近色，最后得到一坨纯黑——这是"眼睛不还原"的
 * 根因。颜色层面找不回来（平均值里已经没有白色信息），所以判定放在
 * 覆盖面积上：某格近白像素占比 ≥ [MIN_SPOT]，且它属于一个面积 ≤ [MAX_AREA]
 * 的深色连通块，就把这块里近白占比最高的那一格改成白豆。
 *
 * 限定"小而实的小块"：整条轮廓线、大块头发都是深色连通块，面积远超
 * 阈值一律跳过；眼睛、牙齿这类紧凑实心小块才会命中。再加两道闸：
 * - 候选格必须 8 邻域里至少 [MIN_DARK_NEIGHBORS] 个是深色，否则命中的总是
 *   贴着白背景的边缘格（那里近白占比反而最高），白点会跑到眼睛外侧；
 * - 连通块填充率（面积 / 外接框面积）≥ [MIN_FILL]，把细长描边段挡掉。
 * 复杂度 O(格数)。
 */
object HighlightRescue {

    /** 深色判定：Lab 亮度上限（0..100） */
    private const val DARK_MAX_L = 45f

    /** 命中所需近白像素占比（%）。78 格下眼球高光直径不足一格，摊开后就 30~50% */
    private const val MIN_SPOT = 24

    /** 双高光：眼球里上下两个反光点，第二格的最低占比 */
    private const val SECOND_MIN_SPOT = 20

    /** 块面积达到这个值才允许点第二个高光（16 格 = 4×4 眼球） */
    private const val MIN_AREA_FOR_SECOND = 12

    /** 两个高光格之间的曼哈顿距离下限，防止两点并成一块大白 */
    private const val MIN_SECOND_DIST = 2

    /** 深色连通块最小面积（格）：2 格的多半是短线，点白反而脏 */
    private const val MIN_AREA = 5

    /** 候选格必须是块的"内部格"（上下左右 4 邻全深色）。1 格宽的描边、
     *  V 形闭眼、嘴角这类笔画没有内部格，天然被挡掉；只有眼睛、实心鼻尖
     *  这种 ≥3 格宽的块才有候选，白点不会跑到笔画外面。 */

    /** 连通块填充率下限：面积 / 外接框，挡掉细长描边段 */
    private const val MIN_FILL = 0.70

    /** 高光色：纯白。色卡里没有白豆时 nearestIndex 会退到最近浅色，仍可用 */
    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * @param cells   已量化/清理完的格子（-1 = 空格），就地返回新数组
     * @param spot    每格近白像素占比（0..100），来自 [ImagePreprocessor.sample]
     */
    fun apply(
        cells: IntArray,
        w: Int,
        h: Int,
        palette: BeadPalette,
        spot: ByteArray,
        excludedCodes: Set<String> = emptySet(),
    ): IntArray {
        val n = w * h
        if (cells.size < n || spot.size < n) return cells
        val out = cells.copyOf()
        val whiteIdx = palette.nearestIndex(
            ColorConversions.argbToLab(WHITE), excludedCodes,
        )
        if (whiteIdx < 0) return cells

        val dark = BooleanArray(n)
        for (i in 0 until n) {
            val c = out[i]
            dark[i] = c >= 0 && palette.colors[c].lab[0] < DARK_MAX_L
        }

        // 面积上限随网格放大：78×80 约 62 格，200×200 时放宽到 400 格
        val maxArea = (n / 100).coerceIn(8, 400)
        val visited = BooleanArray(n)
        val stack = IntArray(n)

        for (start in 0 until n) {
            if (!dark[start] || visited[start]) continue
            // 4 邻域泛洪：8 邻域会让对角相接的两块暗部并成一块，眼睛容易和
            // 紧邻的 `<` 形闭眼黏在一起，面积一超限就整块跳过
            var top = 0
            stack[top++] = start
            visited[start] = true
            var size = 0
            var bestCell = -1
            var bestSpot = MIN_SPOT - 1
            var secondCell = -1
            var secondSpot = SECOND_MIN_SPOT - 1
            var innerCount = 0
            var minX = w; var maxX = -1; var minY = h; var maxY = -1
            while (top > 0) {
                val i = stack[--top]
                size++
                if (size > maxArea) break
                val s = spot[i].toInt()
                val x = i % w
                val y = i / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (innerCell(dark, w, h, x, y)) {
                    innerCount++
                    if (s > bestSpot) {
                        // 旧的最高格降级为第二候选，但必须离新最高格足够远
                        if (bestCell >= 0 && manhattan(bestCell, i, w) >= MIN_SECOND_DIST &&
                            sameLine(bestCell, i, w) && bestSpot > secondSpot
                        ) {
                            secondSpot = bestSpot
                            secondCell = bestCell
                        }
                        bestSpot = s
                        bestCell = i
                    } else if (s > secondSpot && s >= SECOND_MIN_SPOT &&
                        (bestCell < 0 ||
                            (manhattan(bestCell, i, w) >= MIN_SECOND_DIST && sameLine(bestCell, i, w)))
                    ) {
                        secondSpot = s
                        secondCell = i
                    }
                }
                if (x > 0) push(stack, visited, dark, i - 1)?.let { stack[top++] = it }
                if (x < w - 1) push(stack, visited, dark, i + 1)?.let { stack[top++] = it }
                if (y > 0) push(stack, visited, dark, i - w)?.let { stack[top++] = it }
                if (y < h - 1) push(stack, visited, dark, i + w)?.let { stack[top++] = it }
            }
            val bbox = (maxX - minX + 1) * (maxY - minY + 1)
            val fill = if (bbox > 0) size.toDouble() / bbox else 0.0
            // 实心度：内部格至少 2 个且占块面积 1/4 以上。眼球 4×4 是 4/16=0.25，
            // `<` 形闭眼只有 1/11 —— 后者点白会在笑眼正中戳一个洞。
            val solid = innerCount >= 2 && innerCount * 4 >= size
            if (size in MIN_AREA..maxArea && bestCell >= 0 && fill >= MIN_FILL && solid) {
                // 只替换一格：这块里近白占比最高的那格就是高光所在位置
                out[bestCell] = whiteIdx
                // 眼球这类大实心块再补一格：原图上下两个反光点，只点一格会像
                // 瞳孔被戳了个洞，两点才读得出"眼睛"
                if (secondCell >= 0 && size >= MIN_AREA_FOR_SECOND) {
                    out[secondCell] = whiteIdx
                }
                if (System.getenv("PINDOU_DEBUG_RESCUE") != null) {
                    println(
                        "RESCUE cell=(${bestCell % w},${bestCell / w}) area=$size " +
                            "bbox=${maxX - minX + 1}x${maxY - minY + 1} fill=${"%.2f".format(fill)} " +
                            "spot=$bestSpot second=(${if (secondCell >= 0) secondCell % w else -1}," +
                            "${if (secondCell >= 0) secondCell / w else -1}) spot2=$secondSpot",
                    )
                }
            }
        }
        return out
    }

    /** 4 邻全深色 = 块内部格。1 格宽的线一个都没有 */
    private fun innerCell(dark: BooleanArray, w: Int, h: Int, x: Int, y: Int): Boolean {
        if (x <= 0 || y <= 0 || x >= w - 1 || y >= h - 1) return false
        val i = y * w + x
        return dark[i - 1] && dark[i + 1] && dark[i - w] && dark[i + w]
    }

    private fun manhattan(a: Int, b: Int, w: Int): Int =
        kotlin.math.abs(a % w - b % w) + kotlin.math.abs(a / w - b / w)

    /** 同列或同行：眼球里的两个反光点是上下叠的，对角两点会读成一条斜杠 */
    private fun sameLine(a: Int, b: Int, w: Int): Boolean =
        a % w == b % w || a / w == b / w

    private fun push(stack: IntArray, visited: BooleanArray, dark: BooleanArray, i: Int): Int? {
        if (visited[i] || !dark[i]) return null
        visited[i] = true
        return i
    }
}
