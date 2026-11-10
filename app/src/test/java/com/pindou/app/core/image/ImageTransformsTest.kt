package com.pindou.app.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTransformsTest {

    /** 4×4 图，pixel(x,y) = y*4+x */
    private val img44 = IntArray(16) { it }

    @Test
    fun `裁剪提取子区域`() {
        val out = ImageTransforms.crop(img44, 4, 4, CropRect(1f, 1f, 3f, 3f))
        assertEquals(2, out.width)
        assertEquals(2, out.height)
        // y=1 行：5,6；y=2 行：9,10
        assertTrue(out.pixels.contentEquals(intArrayOf(5, 6, 9, 10)))
    }

    @Test
    fun `裁剪框越界时收敛到合法区域`() {
        val out = ImageTransforms.crop(img44, 4, 4, CropRect(-5f, -5f, 99f, 99f))
        assertEquals(4, out.width)
        assertEquals(4, out.height)
        assertTrue(out.pixels.contentEquals(img44))
    }

    @Test
    fun `退化裁剪框收敛为单像素`() {
        val out = ImageTransforms.crop(img44, 4, 4, CropRect(2f, 2f, 2f, 2f))
        assertEquals(1, out.width)
        assertEquals(1, out.height)
        assertEquals(10, out.pixels[0]) // pixel(2,2)
    }

    @Test
    fun `顺时针旋转的像素映射`() {
        // 2×3 图：0,1 / 10,11 / 20,21 → 顺时针转成 3×2
        val src = intArrayOf(0, 1, 10, 11, 20, 21)
        val out = ImageTransforms.rotate90(src, 2, 3, clockwise = true)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
        // 逆时针四分之…… 顺时针后第一行 = 原第一列倒序：20,10,0
        assertTrue(out.pixels.contentEquals(intArrayOf(20, 10, 0, 21, 11, 1)))
    }

    @Test
    fun `逆时针旋转四次等于原图`() {
        val src = intArrayOf(1, 2, 3, 4, 5, 6)
        var cur = ImageTransforms.rotate90(src, 3, 2, clockwise = false)
        repeat(3) { cur = ImageTransforms.rotate90(cur.pixels, cur.width, cur.height, clockwise = false) }
        assertEquals(3, cur.width)
        assertEquals(2, cur.height)
        assertTrue(cur.pixels.contentEquals(src))
    }

    @Test
    fun `顺逆时针互逆`() {
        val cw = ImageTransforms.rotate90(img44, 4, 4, clockwise = true)
        val back = ImageTransforms.rotate90(cw.pixels, cw.width, cw.height, clockwise = false)
        assertTrue(back.pixels.contentEquals(img44))
    }

    @Test
    fun `水平与垂直镜像`() {
        val src = intArrayOf(0, 1, 10, 11, 20, 21) // 2×3
        val mh = ImageTransforms.mirror(src, 2, 3, horizontal = true)
        assertTrue(mh.pixels.contentEquals(intArrayOf(1, 0, 11, 10, 21, 20)))
        val mv = ImageTransforms.mirror(src, 2, 3, horizontal = false)
        assertTrue(mv.pixels.contentEquals(intArrayOf(20, 21, 10, 11, 0, 1)))
    }

    @Test
    fun `旋转裁剪框与旋转像素一致`() {
        // 全图旋转后裁剪框应为新全图
        val full = ImageTransforms.rotateRect(CropRect(0f, 0f, 4f, 2f), 4, 2, clockwise = true)
        assertEquals(CropRect(0f, 0f, 2f, 4f), full)
        // 子框 (1,0,3,1)（覆盖旧图 x∈[1,2], y=0 行）旋转后应覆盖新图 x'=1 列, y'∈[1,2]
        val sub = ImageTransforms.rotateRect(CropRect(1f, 0f, 3f, 1f), 4, 2, clockwise = true)
        assertEquals(CropRect(1f, 1f, 2f, 3f), sub)
        // 逆时针全图（4×2 → 新图 2×4，全图框不变换为全图框）
        val ccw = ImageTransforms.rotateRect(CropRect(0f, 0f, 4f, 2f), 4, 2, clockwise = false)
        assertEquals(CropRect(0f, 0f, 2f, 4f), ccw)
    }

    @Test
    fun `镜像裁剪框`() {
        val h = ImageTransforms.mirrorRect(CropRect(0f, 0f, 1f, 2f), 4, 2, horizontal = true)
        assertEquals(CropRect(3f, 0f, 4f, 2f), h)
        val v = ImageTransforms.mirrorRect(CropRect(0f, 0f, 4f, 1f), 4, 2, horizontal = false)
        assertEquals(CropRect(0f, 1f, 4f, 2f), v)
    }

    @Test
    fun `平移裁剪框钳制在边界内`() {
        val r = CropRect(0f, 0f, 10f, 10f)
        val left = ImageTransforms.movedBy(r, -5f, -5f, 100f, 100f)
        assertEquals(CropRect(0f, 0f, 10f, 10f), left)
        val right = ImageTransforms.movedBy(r, 95f, 95f, 100f, 100f)
        assertEquals(CropRect(90f, 90f, 100f, 100f), right)
    }

    @Test
    fun `锚点建框自由比例`() {
        val r = ImageTransforms.rectFromAnchor(0f, 0f, 50f, 30f, null, 100f, 100f)
        assertEquals(CropRect(0f, 0f, 50f, 30f), r)
        // 拖过锚点反向时收敛到边界角
        val neg = ImageTransforms.rectFromAnchor(0f, 0f, -10f, -10f, null, 100f, 100f)
        assertEquals(CropRect(0f, 0f, 1f, 1f), neg)
    }

    @Test
    fun `锚点建框锁定比例`() {
        // 锚点在左上，比例 2:1
        val r = ImageTransforms.rectFromAnchor(0f, 0f, 100f, 40f, 2f, 1000f, 1000f)
        assertEquals(CropRect(0f, 0f, 100f, 50f), r)
        // 锚点靠近右缘时等比钳制
        val clamped = ImageTransforms.rectFromAnchor(900f, 0f, 1000f, 500f, 1f, 1000f, 1000f)
        assertEquals(CropRect(900f, 0f, 1000f, 100f), clamped)
    }

    @Test
    fun `裁剪后再旋转等于旋转后裁剪区域`() {
        // 4×2 图，pixel(x,y)=y*4+x；裁 (1,0,3,1) 后顺时针旋转，
        // 与先旋转整图、再按旋转后裁剪框裁剪结果一致
        val src = IntArray(8) { it }
        val cropped = ImageTransforms.crop(src, 4, 2, CropRect(1f, 0f, 3f, 1f))
        val a = ImageTransforms.rotate90(cropped.pixels, cropped.width, cropped.height, true)
        val rotated = ImageTransforms.rotate90(src, 4, 2, true)
        val rect = ImageTransforms.rotateRect(CropRect(1f, 0f, 3f, 1f), 4, 2, true)
        val b = ImageTransforms.crop(rotated.pixels, rotated.width, rotated.height, rect)
        assertTrue(a.pixels.contentEquals(b.pixels))
    }

    @Test
    fun `自适应网格竖图长边锚定`() {
        // 914×1667 竖图，长边 58 → 宽 58×914/1667 = 31.8 → 32
        assertEquals(32 to 58, ImageTransforms.adaptiveGridSize(914, 1667, 58))
        // 长边 29 → 16×29
        assertEquals(16 to 29, ImageTransforms.adaptiveGridSize(914, 1667, 29))
    }

    @Test
    fun `自适应网格横图与方图`() {
        // 1920×1076 横图，长边 29 → 29×16
        assertEquals(29 to 16, ImageTransforms.adaptiveGridSize(1920, 1076, 29))
        // 方图原样
        assertEquals(29 to 29, ImageTransforms.adaptiveGridSize(100, 100, 29))
    }

    @Test
    fun `自适应网格钳制在合法范围`() {
        // 极扁图短边取整后低于 5 → 钳到 5
        assertEquals(200 to 5, ImageTransforms.adaptiveGridSize(2000, 10, 200))
        assertEquals(5 to 200, ImageTransforms.adaptiveGridSize(10, 2000, 200))
        // anchor 越界收敛
        assertEquals(200 to 200, ImageTransforms.adaptiveGridSize(100, 100, 999))
    }

    @Test
    fun `倍数缩放基础网格`() {
        assertEquals(32 to 58, ImageTransforms.scaledGridSize(16, 29, 2.0))
        assertEquals(8 to 15, ImageTransforms.scaledGridSize(16, 29, 0.5)) // 14.5 → 15
        assertEquals(16 to 29, ImageTransforms.scaledGridSize(16, 29, 1.0))
        // 1.5 倍：24 × 43.5 → 44
        assertEquals(24 to 44, ImageTransforms.scaledGridSize(16, 29, 1.5))
    }

    @Test
    fun `倍数缩放钳制在合法范围`() {
        assertEquals(200 to 200, ImageTransforms.scaledGridSize(100, 180, 3.0))
        assertEquals(5 to 8, ImageTransforms.scaledGridSize(8, 15, 0.5)) // 4→5, 7.5→8
    }
}
