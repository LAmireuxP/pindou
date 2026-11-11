package com.pindou.app.core.pattern

import com.pindou.app.core.image.CropRect
import com.pindou.app.core.image.ImageTransforms
import com.pindou.app.core.image.TransformedImage
import com.pindou.app.core.palette.BeadPalette
import kotlin.math.roundToInt

/**
 * 像素图导入的单一转换入口。
 *
 * 校准页的「看图纸」和顶部「完成」必须来自同一次计算：之前预览用的是
 * 逐格原始采样色，完成时又重跑一遍 refine + 色卡量化，两边的网格、空格、
 * 色号都可能不同，用户看到 A、生成出 B。
 *
 * 这里把「网格收进图内 → 裁图 → 按画像素网格生成」固定成一条路径：
 * 预览直接铺 [Conversion.result] 的色号，完成时复用同一个结果，
 * 不再二次 refine / 二次采样。纯 Kotlin，可在任意线程调用。
 */
object PixelImportPipeline {

    /**
     * 进入校准页时准备好的网格。
     *
     * [applied] 表示智能对齐真的改动了网格；[detected] 表示自动检测
     * 没有落到兜底网格，UI 用它区分「自动识别」与「手动网格」。
     */
    data class Prepared(
        val grid: PixelCalibration.Grid,
        val method: PixelGridInspector.Method,
        val detected: Boolean,
        val score: Float,
        val applied: Boolean,
    )

    /**
     * 一次完整的转换结果：裁出的原图、图纸色号、预览像素。
     *
     * [previewArgb] 与 [result] 一一对应，空格为 0。UI 只读它，
     * 不再自己按格采样，避免出现两套颜色。
     */
    data class Conversion(
        val grid: PixelCalibration.Grid,
        val cropped: TransformedImage,
        val result: PatternResult,
        val previewArgb: IntArray,
    )

    /**
     * 检测 → 智能吸附 → 收进图内。
     *
     * 智能对齐默认开启，所以这里必须在进页面之前就执行 [PixelCalibration.refine]；
     * 只把开关设成 true、等用户手动关一次再开，正是之前「默认开启但不生效」的根因。
     *
     * @param detect 可注入的检测器，单元测试用它模拟一个偏掉的初始网格
     */
    fun prepare(
        pixels: IntArray,
        w: Int,
        h: Int,
        smart: Boolean,
        threshold: Float,
        detect: (IntArray, Int, Int) -> PixelGridInspector.Detection =
            { p, pw, ph -> PixelGridInspector.detect(p, pw, ph) },
    ): Prepared {
        val detection = detect(pixels, w, h)
        var grid = detection.grid
        var method = detection.method
        var score = detection.confidence
        var applied = false
        if (smart) {
            val alignment = PixelCalibration.refine(pixels, w, h, grid, threshold)
            score = alignment.score
            applied = alignment.applied
            if (alignment.applied) {
                grid = alignment.grid
                method = PixelGridInspector.Method.MANUAL
            }
        }
        // 页面上看到的网格就是完成时裁的网格：先把整张网格收进图内，
        // 避免用户对齐完、点完成时格子突然缩小或平移。
        grid = grid.fittedWithin(w, h)
        return Prepared(
            grid = grid,
            method = method,
            detected = detection.method != PixelGridInspector.Method.FALLBACK,
            score = score,
            applied = applied,
        )
    }

    /**
     * 按网格裁图并生成最终图纸。
     *
     * [options] 里的网格尺寸、过滤档会被强制覆盖：校准页已经确定了
     * 列/行，生成阶段再按参数页的 29×29 走就会把整张图压扁。
     */
    fun convert(
        pixels: IntArray,
        w: Int,
        h: Int,
        grid: PixelCalibration.Grid,
        palette: BeadPalette,
        options: GeneratorOptions,
        onStage: (GenerationStage, Float) -> Unit = { _, _ -> },
    ): Conversion {
        require(w > 0 && h > 0 && pixels.size == w * h) { "源图像素尺寸不符" }
        val fitted = grid.fittedWithin(w, h)

        // 与确认按钮原来那段裁图逐行一致：取整后再夹一次，保证裁出的
        // 宽高等于网格覆盖范围，采样块长不会被边缘余数拉偏。
        val l = fitted.offsetX.roundToInt().coerceIn(0, w - 1)
        val t = fitted.offsetY.roundToInt().coerceIn(0, h - 1)
        val r = fitted.right.roundToInt().coerceIn(l + 1, w)
        val b = fitted.bottom.roundToInt().coerceIn(t + 1, h)
        val cropped = ImageTransforms.crop(
            pixels, w, h,
            CropRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()),
        )

        val exact = options.copy(
            gridW = fitted.cols.coerceIn(1, 500),
            gridH = fitted.rows.coerceIn(1, 500),
            // 限色/抖动/去背是照片参数。用户上一轮调过照片后直接导像素画，
            // 这些开关会原样带进来，把 1:1 的色块抖散或吃掉，这里统一关掉。
            maxColors = null,
            ditheringEnabled = false,
            removeBackground = false,
            filterLevel = FilterLevel.NONE,
            exactArt = true,
            pixelGrid = fitted.cols to fitted.rows,
            autoFrame = false,
            // 画像素是作者选好的固定颜色，曝光修正会整体提亮，把原色拉偏；
            // 这条管线只做 1:1 还原。
            autoExposure = false,
        )
        val result = PatternGenerator.generate(
            cropped.pixels, cropped.width, cropped.height,
            exact, palette, onStage,
        )
        val argb = IntArray(result.cells.size)
        for (i in argb.indices) {
            val idx = result.cells[i]
            argb[i] = if (idx in result.palette.colors.indices) {
                result.palette.colors[idx].argb
            } else {
                0
            }
        }
        return Conversion(fitted, cropped, result, argb)
    }
}
