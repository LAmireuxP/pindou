package com.pindou.app.core.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 裁剪框：图像像素坐标系下的连续矩形，right/bottom 为开区间边界。
 * 所有矩形运算都保证结果完整落在图像范围内（最小 1×1）。
 */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        fun full(width: Int, height: Int): CropRect =
            CropRect(0f, 0f, width.toFloat(), height.toFloat())
    }
}

/** 裁剪框可拖动的角柄 */
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** 裁剪框可拖动的边 */
enum class CropEdgeSide { LEFT, RIGHT, TOP, BOTTOM }

/** 变换结果（新数组，不与源数组共享） */
class TransformedImage(val pixels: IntArray, val width: Int, val height: Int)

/**
 * 图像几何变换（裁剪/旋转/镜像）与裁剪框运算。
 * 纯 Kotlin 实现，像素为 ARGB 行优先，可在任意线程执行。
 */
object ImageTransforms {

    // ---------- 像素变换 ----------

    /** 按裁剪框提取子图；框坐标越界或退化时自动收敛到合法区域 */
    fun crop(pixels: IntArray, srcW: Int, srcH: Int, rect: CropRect): TransformedImage {
        require(srcW > 0 && srcH > 0 && pixels.size == srcW * srcH) { "源图像素尺寸不符" }
        val x0 = rect.left.roundToInt().coerceIn(0, srcW - 1)
        val y0 = rect.top.roundToInt().coerceIn(0, srcH - 1)
        val x1 = rect.right.roundToInt().coerceIn(x0 + 1, srcW)
        val y1 = rect.bottom.roundToInt().coerceIn(y0 + 1, srcH)
        val w = x1 - x0
        val h = y1 - y0
        val out = IntArray(w * h)
        for (row in 0 until h) {
            System.arraycopy(pixels, (y0 + row) * srcW + x0, out, row * w, w)
        }
        return TransformedImage(out, w, h)
    }

    /** 旋转 90°；两个方向的输出尺寸都交换为 srcH×srcW */
    fun rotate90(pixels: IntArray, srcW: Int, srcH: Int, clockwise: Boolean): TransformedImage {
        require(srcW > 0 && srcH > 0 && pixels.size == srcW * srcH) { "源图像素尺寸不符" }
        val w = srcH
        val h = srcW
        val out = IntArray(srcW * srcH)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val p = pixels[y * srcW + x]
                if (clockwise) {
                    val nx = srcH - 1 - y
                    val ny = x
                    out[ny * w + nx] = p
                } else {
                    val nx = y
                    val ny = srcW - 1 - x
                    out[ny * w + nx] = p
                }
            }
        }
        return TransformedImage(out, w, h)
    }

    /** 镜像翻转 */
    fun mirror(pixels: IntArray, srcW: Int, srcH: Int, horizontal: Boolean): TransformedImage {
        require(srcW > 0 && srcH > 0 && pixels.size == srcW * srcH) { "源图像素尺寸不符" }
        val out = IntArray(srcW * srcH)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val nx = if (horizontal) srcW - 1 - x else x
                val ny = if (horizontal) y else srcH - 1 - y
                out[ny * srcW + nx] = pixels[y * srcW + x]
            }
        }
        return TransformedImage(out, srcW, srcH)
    }

    // ---------- 裁剪框运算 ----------

    /** 裁剪框随图像旋转后的新位置（与像素映射保持一致） */
    fun rotateRect(rect: CropRect, srcW: Int, srcH: Int, clockwise: Boolean): CropRect =
        if (clockwise) {
            CropRect(
                left = srcH - rect.bottom, top = rect.left,
                right = srcH - rect.top, bottom = rect.right,
            )
        } else {
            CropRect(
                left = rect.top, top = srcW - rect.right,
                right = rect.bottom, bottom = srcW - rect.left,
            )
        }

    /** 裁剪框随图像镜像后的新位置 */
    fun mirrorRect(rect: CropRect, srcW: Int, srcH: Int, horizontal: Boolean): CropRect =
        if (horizontal) {
            CropRect(
                left = srcW - rect.right, top = rect.top,
                right = srcW - rect.left, bottom = rect.bottom,
            )
        } else {
            CropRect(
                left = rect.left, top = srcH - rect.bottom,
                right = rect.right, bottom = srcH - rect.top,
            )
        }

    /** 平移裁剪框并钳制到图像范围内（尺寸不变） */
    fun movedBy(rect: CropRect, dx: Float, dy: Float, maxW: Float, maxH: Float): CropRect {
        val w = rect.width
        val h = rect.height
        val left = (rect.left + dx).coerceIn(0f, (maxW - w).coerceAtLeast(0f))
        val top = (rect.top + dy).coerceIn(0f, (maxH - h).coerceAtLeast(0f))
        return CropRect(left, top, left + w, top + h)
    }

    /**
     * 图像内最大的居中正方形选区。
     *
     * 默认给正方形：拼豆图纸常见的模板是方阵（30×30、50×50），
     * 手机随手拍的照片却是 4:3 / 16:9。直接按全图生成，要么被拉伸，
     * 要么上下留一大条白边；居中正方形把主体留在中间，同时保证
     * 宽高比锁死，后续任何网格都不会变形。
     */
    fun centerSquare(width: Int, height: Int): CropRect {
        require(width > 0 && height > 0) { "图片尺寸非法: ${width}x$height" }
        val side = min(width, height).toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        return CropRect(left, top, left + side, top + side)
    }

    /**
     * 图像内最大的居中定比选区（用于「锁定网格比例」时把选区吸附到该比例）。
     * @param ratio 宽/高
     */
    fun centeredAspect(width: Int, height: Int, ratio: Float): CropRect {
        require(width > 0 && height > 0) { "图片尺寸非法: ${width}x$height" }
        require(ratio > 0f) { "比例非法: $ratio" }
        val imgRatio = width.toFloat() / height
        val w: Float
        val h: Float
        if (imgRatio > ratio) {
            h = height.toFloat()
            w = h * ratio
        } else {
            w = width.toFloat()
            h = w / ratio
        }
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return CropRect(left, top, left + w, top + h)
    }

    /**
     * 以现有选区中心取最大正方形，并保证落在图内。
     *
     * 从自由裁剪切到正方形时保留用户已经框好的主体位置，而不是把
     * 选区重新拉回整图中心。
     */
    fun squareAround(rect: CropRect, maxW: Float, maxH: Float): CropRect {
        require(maxW > 0f && maxH > 0f) { "图像尺寸非法: ${maxW}x$maxH" }
        val side = min(min(rect.width, rect.height), min(maxW, maxH)).coerceAtLeast(1f)
        val cx = ((rect.left + rect.right) / 2f).coerceIn(side / 2f, maxW - side / 2f)
        val cy = ((rect.top + rect.bottom) / 2f).coerceIn(side / 2f, maxH - side / 2f)
        return CropRect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
    }

    /**
     * 从锚点向目标点拉出一个矩形（角柄缩放与重新框选共用）。
     * @param ratio 宽高比（宽/高）；null = 自由比例。锁定比例时结果等比钳制在图像范围内。
     */
    fun rectFromAnchor(
        anchorX: Float,
        anchorY: Float,
        targetX: Float,
        targetY: Float,
        ratio: Float?,
        maxW: Float,
        maxH: Float,
    ): CropRect {
        val sx = if (targetX >= anchorX) 1f else -1f
        val sy = if (targetY >= anchorY) 1f else -1f
        var w = abs(targetX - anchorX)
        var h = abs(targetY - anchorY)
        if (w < 1f) w = 1f
        if (h < 1f) h = 1f
        if (ratio != null && ratio > 0f) {
            if (w / h > ratio) h = w / ratio else w = h * ratio
            val maxDx = if (sx > 0f) maxW - anchorX else anchorX
            val maxDy = if (sy > 0f) maxH - anchorY else anchorY
            val k = min(1f, min(maxDx / w, maxDy / h))
            if (k < 1f) {
                w *= k
                h *= k
            }
        }
        return sanitize(anchorX, anchorY, anchorX + sx * w, anchorY + sy * h, maxW, maxH)
    }

    /**
     * 锁定比例时拖动一条边。
     *
     * 旧实现把手指的纵向坐标也传给 rectFromAnchor，横向拖动时高度会跟着
     * 纵向抖动，选区在「等比例」约束下反复回弹。这里只取垂直于该边的那
     * 一个坐标：拖左边只读 x，拖上边只读 y；另一轴围绕原中心对称展开，
     * 并先按图像边界算出可用的最大尺寸，避免先放大再裁切造成跳动。
     *
     * @param target 手指在图像坐标系里的 x（左右边）或 y（上下边）
     * @param ratio  宽/高，必须 > 0
     */
    fun edgeResizeLocked(
        rect: CropRect,
        side: CropEdgeSide,
        target: Float,
        ratio: Float,
        maxW: Float,
        maxH: Float,
    ): CropRect {
        require(ratio > 0f) { "比例非法: $ratio" }
        if (maxW <= 0f || maxH <= 0f) return rect

        when (side) {
            CropEdgeSide.LEFT, CropEdgeSide.RIGHT -> {
                val fixedX = if (side == CropEdgeSide.LEFT) rect.right else rect.left
                // 不允许越过对边：越过时宽度按 1px 处理，选框不会左右翻转
                val safeTarget = if (side == CropEdgeSide.LEFT) {
                    target.coerceIn(0f, fixedX - 1f)
                } else {
                    target.coerceIn(fixedX + 1f, maxW)
                }
                val rawW = abs(fixedX - safeTarget).coerceAtLeast(1f)
                val maxHorizontal = if (side == CropEdgeSide.LEFT) fixedX else maxW - fixedX
                val centerY = (rect.top + rect.bottom) / 2f
                // 以原中心向上下展开，最多到图像边缘；再换算成宽度上限
                val maxHByCenter = 2f * min(centerY, maxH - centerY)
                val maxWByHeight = (maxHByCenter * ratio).coerceAtLeast(1f)
                val w = min(rawW, min(maxHorizontal.coerceAtLeast(1f), maxWByHeight))
                val h = (w / ratio).coerceIn(1f, maxH)
                val left = if (side == CropEdgeSide.LEFT) fixedX - w else fixedX
                val top = (centerY - h / 2f).coerceIn(0f, (maxH - h).coerceAtLeast(0f))
                return CropRect(left, top, left + w, top + h)
            }
            CropEdgeSide.TOP, CropEdgeSide.BOTTOM -> {
                val fixedY = if (side == CropEdgeSide.TOP) rect.bottom else rect.top
                val safeTarget = if (side == CropEdgeSide.TOP) {
                    target.coerceIn(0f, fixedY - 1f)
                } else {
                    target.coerceIn(fixedY + 1f, maxH)
                }
                val rawH = abs(fixedY - safeTarget).coerceAtLeast(1f)
                val maxVertical = if (side == CropEdgeSide.TOP) fixedY else maxH - fixedY
                val centerX = (rect.left + rect.right) / 2f
                val maxWByCenter = 2f * min(centerX, maxW - centerX)
                val maxHByWidth = (maxWByCenter / ratio).coerceAtLeast(1f)
                val h = min(rawH, min(maxVertical.coerceAtLeast(1f), maxHByWidth))
                val w = (h * ratio).coerceIn(1f, maxW)
                val top = if (side == CropEdgeSide.TOP) fixedY - h else fixedY
                val left = (centerX - w / 2f).coerceIn(0f, (maxW - w).coerceAtLeast(0f))
                return CropRect(left, top, left + w, top + h)
            }
        }
    }

    /** 把任意四元组收敛为合法裁剪框（在图像内、至少 1×1） */
    private fun sanitize(l: Float, t: Float, r: Float, b: Float, maxW: Float, maxH: Float): CropRect {
        val x1 = min(l, r).coerceIn(0f, maxW)
        val x2 = max(l, r).coerceIn(0f, maxW)
        val y1 = min(t, b).coerceIn(0f, maxH)
        val y2 = max(t, b).coerceIn(0f, maxH)
        val w = (x2 - x1).coerceAtLeast(1f).coerceAtMost(maxW)
        val h = (y2 - y1).coerceAtLeast(1f).coerceAtMost(maxH)
        val left = min(x1, maxW - w)
        val top = min(y1, maxH - h)
        return CropRect(left, top, left + w, top + h)
    }

    /**
     * 按图片宽高比自适应计算网格尺寸：图片长边映射为 anchor 格，短边按比例取整。
     * 结果两边都钳制在 5..200。降采样是宽高独立映射，比例不一致会拉伸图案，
     * 生成前用本函数让网格跟随图片比例。
     */
    fun adaptiveGridSize(imgW: Int, imgH: Int, anchor: Int): Pair<Int, Int> {
        require(imgW > 0 && imgH > 0) { "图片尺寸非法: ${imgW}x$imgH" }
        val a = anchor.coerceIn(5, 200)
        return if (imgW >= imgH) {
            val gh = (a.toDouble() * imgH / imgW).roundToInt().coerceIn(5, 200)
            a to gh
        } else {
            val gw = (a.toDouble() * imgW / imgH).roundToInt().coerceIn(5, 200)
            gw to a
        }
    }

    /** 按倍数整体缩放基础网格尺寸，两边各自钳制在 5..200 */
    fun scaledGridSize(baseW: Int, baseH: Int, scale: Double): Pair<Int, Int> {
        require(baseW > 0 && baseH > 0 && scale > 0.0) { "缩放参数非法" }
        val w = (baseW * scale).roundToInt().coerceIn(5, 200)
        val h = (baseH * scale).roundToInt().coerceIn(5, 200)
        return w to h
    }

    /**
     * 让网格宽高比跟随选区：自由裁剪时不再出现「方框配长格」。
     *
     * 锚定长边，短边按比例推导，格数总量基本不变；返回值两边都钳制在
     * 5..200，保证任何极端选区都能生成合法网格。
     *
     * @param aspect 选区宽/高
     * @param anchor 锚定的长边格数
     */
    fun gridSizeForAspect(aspect: Float, anchor: Int): Pair<Int, Int> {
        require(aspect > 0f) { "比例非法: $aspect" }
        val a = anchor.coerceIn(5, 200)
        return if (aspect >= 1f) {
            a to (a / aspect).roundToInt().coerceIn(5, 200)
        } else {
            (a * aspect).roundToInt().coerceIn(5, 200) to a
        }
    }
}
