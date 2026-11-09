package com.pindou.app.core.chart

import com.pindou.app.core.color.Ciede2000
import com.pindou.app.core.color.ColorConversions
import com.pindou.app.core.palette.BeadPalette
import com.pindou.app.core.pattern.PatternResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * 色号图（别人做好的拼豆图纸）识别。
 *
 * 输入是一张"画着网格 + 每格填了颜色 + 格子里印着色号"的图，输出是可编辑的
 * 格子数据。难点不在取色，在于先知道自己站在哪个网格上：
 * 同一张图里格子边长可能 10.x 像素（截图缩放过的），网格线和格子里的小字
 * 都是高频噪声，按"整图均分"切格子会在右半边累积漂移，一格压到隔壁。
 *
 * 这里的做法和颂拼豆（com.pixel.ibeads 3.9.0）的思路一致——先定网格，
 * 再逐格判定——但模型换成了纯几何，不依赖 ncnn / ONNX：
 *   1. 列（行）方向做 RGB 三通道梯度累加，网格线在曲线上是一串等距尖峰；
 *   2. 峰值挑选后取相邻间隔的中位数得到格子边长，再用"复数相位平均"求出
 *      整条网格的相位（避免逐峰相减被漏检线带偏）；
 *   3. 最小二乘把 peaks ≈ offset + k·pitch 重新拟一遍，得到小数边长；
 *   4. 智能对齐：在边长 ±4% / 相位 ±pitch 范围内做小步搜索，取"预测网格线
 *      落点梯度均值"最大的参数，等价于把红线和图纸格子贴死。
 *
 * 逐格判定用众数而不是均值：色号图每格是纯色，众数能整块保留原色；均值会
 * 把格子边上的网格线、小字混进来，白格被拉灰、深色格发脏。
 */
object ChartRecognizer {

    /** 格子边长允许范围（源图像素）。小于 4px 的格子已经看不清色号了。 */
    private const val MIN_CELL = 4.0

    /** 上限：一张图里最多 400 列，再多说明锁到了小字笔画上 */
    private const val MAX_COLS = 400

    /** 判定"和纸白同色"的每通道容差 */
    private const val PAPER_TOLERANCE = 14

    /** 小字墨色：亮度低于这个值且彩度低，视为格子里的色号文字 */
    private const val INK_LUMA = 110
    private const val INK_CHROMA = 48

    data class ChartGrid(
        val cols: Int,
        val rows: Int,
        val cellW: Double,
        val cellH: Double,
        val offsetX: Double,
        val offsetY: Double,
    ) {
        /** 改列数：保持网格总宽度不变，格子跟着变宽/变窄 */
        fun withCols(n: Int): ChartGrid {
            val c = n.coerceIn(3, MAX_COLS)
            return copy(cols = c, cellW = cellW * cols / c)
        }

        /** 改行数：保持总高度不变 */
        fun withRows(n: Int): ChartGrid {
            val r = n.coerceIn(3, MAX_COLS)
            return copy(rows = r, cellH = cellH * rows / r)
        }

        /** 改单元格边长：列数反向推导，网格右边界不动 */
        fun withCellSize(px: Double): ChartGrid {
            val w = px.coerceIn(MIN_CELL, 512.0)
            val h = px.coerceIn(MIN_CELL, 512.0)
            val n = (cols * cellW / w).roundToInt().coerceIn(3, MAX_COLS)
            val m = (rows * cellH / h).roundToInt().coerceIn(3, MAX_COLS)
            return ChartGrid(n, m, cols * cellW / n, rows * cellH / m, offsetX, offsetY)
        }

        fun nudged(dx: Double, dy: Double): ChartGrid = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)
    }

    /** 采样结果：argb 为每格众数色，empty 为空格标记 */
    class ChartSample(
        val cols: Int,
        val rows: Int,
        val argb: IntArray,
        val empty: BooleanArray,
        val paper: Int,
        val filledCells: Int,
    )

    // ---------------------------------------------------------------- 检测

    fun detect(pixels: IntArray, w: Int, h: Int): ChartGrid? {
        if (w < 48 || h < 48) return null
        val px = profile(pixels, w, h, vertical = true)
        val py = profile(pixels, w, h, vertical = false)
        val ax = fitAxis(findPeaks(px), w) ?: return null
        val ay = fitAxis(findPeaks(py), h) ?: return null
        if (ax.pitch < MIN_CELL || ay.pitch < MIN_CELL) return null
        if (ax.count !in 3..MAX_COLS || ay.count !in 3..MAX_COLS) return null
        // 横竖格子边长差距过大，说明锁错了（一个是小字笔画、一个是真网格）
        if (abs(ax.pitch - ay.pitch) / max(ax.pitch, ay.pitch) > 0.35) return null
        val raw = ChartGrid(
            cols = ax.count,
            rows = ay.count,
            cellW = ax.pitch,
            cellH = ay.pitch,
            offsetX = ax.offset,
            offsetY = ay.offset,
        )
        // 网格线是周期性的：offset 和 offset+pitch 给出同一组线，靠几何分不出来。
        // 用内容边界收口——四周整行整列都是空格就裁掉，避免图纸左边多出一条空列。
        return trimToContent(pixels, w, h, raw)
    }

    /**
     * 裁掉网格四周整行/整列的空格。
     *
     * 只动最外圈连成一片的空行空列，中间的空白（图案镂空）不动。全空则原样返回，
     * 防止把一张没内容的图裁成 0×0。
     */
    fun trimToContent(
        pixels: IntArray,
        w: Int,
        h: Int,
        grid: ChartGrid,
        emptyThreshold: Float = 0.55f,
    ): ChartGrid {
        val s = sample(pixels, w, h, grid, emptyThreshold)
        var minC = s.cols
        var maxC = -1
        var minR = s.rows
        var maxR = -1
        for (r in 0 until s.rows) {
            for (c in 0 until s.cols) {
                if (!s.empty[r * s.cols + c]) {
                    if (c < minC) minC = c
                    if (c > maxC) maxC = c
                    if (r < minR) minR = r
                    if (r > maxR) maxR = r
                }
            }
        }
        if (maxC < minC || maxR < minR) return grid
        val cols = maxC - minC + 1
        val rows = maxR - minR + 1
        if (cols < 3 || rows < 3) return grid
        return grid.copy(
            cols = cols,
            rows = rows,
            offsetX = grid.offsetX + minC * grid.cellW,
            offsetY = grid.offsetY + minR * grid.cellH,
        )
    }

    /**
     * 智能对齐：在当前参数附近小步搜索，最大化"网格线落点上的梯度均值"。
     *
     * 搜索顺序 floor → 步长，先粗后细两轮：粗轮 ±4% / 0.4%，细轮 ±0.5% / 0.05%。
     * 复杂度 O(轮次 × cols × 候选数)，cols ≤ 400，实测量级 < 3ms。
     */
    fun refine(pixels: IntArray, w: Int, h: Int, grid: ChartGrid): ChartGrid {
        val px = profile(pixels, w, h, vertical = true)
        val py = profile(pixels, w, h, vertical = false)
        val rx = refineAxis(px, grid.cellW, grid.offsetX, grid.cols)
        val ry = refineAxis(py, grid.cellH, grid.offsetY, grid.rows)
        return grid.copy(
            cellW = rx.first,
            offsetX = rx.second,
            cellH = ry.first,
            offsetY = ry.second,
        )
    }

    private fun refineAxis(p: FloatArray, pitch0: Double, off0: Double, count: Int): Pair<Double, Double> {
        var bestPitch = pitch0
        var bestOff = off0
        var bestScore = scoreAxis(p, pitch0, off0, count)
        var pitchRange = pitch0 * 0.04
        var pitchStep = pitch0 * 0.004
        var offStep = 0.5
        repeat(2) {
            var dp = -pitchRange
            while (dp <= pitchRange) {
                val pitch = pitch0 + dp
                if (pitch >= MIN_CELL) {
                    var k = -pitch / 2
                    while (k <= pitch / 2) {
                        val off = off0 + k
                        val s = scoreAxis(p, pitch, off, count)
                        if (s > bestScore) {
                            bestScore = s
                            bestPitch = pitch
                            bestOff = off
                        }
                        k += offStep
                    }
                }
                dp += pitchStep
            }
            pitchRange = bestPitch * 0.005
            pitchStep = bestPitch * 0.0005
            offStep = 0.1
        }
        return bestPitch to bestOff
    }

    private fun scoreAxis(p: FloatArray, pitch: Double, off: Double, count: Int): Double {
        if (pitch < MIN_CELL) return Double.NEGATIVE_INFINITY
        var sum = 0.0
        var n = 0
        var k = 0
        while (k <= count) {
            val pos = off + k * pitch
            if (pos >= -0.5 && pos < p.size - 0.5) {
                val i = floor(pos).toInt().coerceIn(0, p.size - 1)
                sum += p[i]
                n++
            }
            k++
        }
        return if (n == 0) Double.NEGATIVE_INFINITY else sum / n
    }

    private class Axis(val pitch: Double, val offset: Double, val count: Int)

    private fun fitAxis(peaks: DoubleArray, len: Int): Axis? {
        if (peaks.size < 4) return null
        val diffs = ArrayList<Double>(peaks.size - 1)
        for (i in 1 until peaks.size) diffs.add(peaks[i] - peaks[i - 1])
        if (diffs.isEmpty()) return null
        var pitch = median(diffs)
        if (pitch < MIN_CELL) return null
        // 漏检一条线会导致 2×pitch 的间隔，只用 0.55~1.6 倍范围内的间隔估边长
        repeat(4) {
            val kept = diffs.filter { it > pitch * 0.55 && it < pitch * 1.6 }
            if (kept.size >= 3) pitch = median(kept)
        }
        if (pitch < MIN_CELL || pitch > len / 3.0) return null

        // 相位：把每个峰的相位投到单位圆上求平均，漏检不影响结果
        var cs = 0.0
        var sn = 0.0
        for (v in peaks) {
            val a = 2 * PI * (v / pitch)
            cs += cos(a)
            sn += sin(a)
        }
        var off = atan2(sn, cs) / (2 * PI) * pitch
        if (off < 0) off += pitch

        // 最小二乘重拟：peaks[k] ≈ offset + k·pitch，k 用当前参数取整
        var sk = 0.0
        var sp = 0.0
        var skk = 0.0
        var skp = 0.0
        var m = 0
        for (v in peaks) {
            val k = ((v - off) / pitch).roundToInt().toDouble()
            sk += k
            sp += v
            skk += k * k
            skp += k * v
            m++
        }
        val denom = m * skk - sk * sk
        if (denom > 1e-6) {
            val b = (m * skp - sk * sp) / denom
            val a = (sp - b * sk) / m
            if (b > MIN_CELL && abs(a) < pitch * 2) {
                pitch = b
                off = a
            }
        }
        var o = off % pitch
        if (o < 0) o += pitch
        val count = floor((len - 0.5 - o) / pitch).toInt()
        if (count < 3) return null
        return Axis(pitch, o, count)
    }

    /** 列（行）方向的梯度曲线：网格线在曲线上一柱擎天，格内渐变小 */
    private fun profile(pixels: IntArray, w: Int, h: Int, vertical: Boolean): FloatArray {
        val len = if (vertical) w else h
        val lines = if (vertical) h else w
        val out = FloatArray(len)
        val step = max(1, lines / 512)
        var l = 0
        while (l < lines) {
            var prev = if (vertical) pixels[l * w] else pixels[l]
            var i = 1
            while (i < len) {
                val cur = if (vertical) pixels[l * w + i] else pixels[i * w + l]
                out[i] += channelDiff(cur, prev).toFloat()
                prev = cur
                i++
            }
            l += step
        }
        return out
    }

    /**
     * 峰值挑选：阈值取 max(均值×1.35, 均值+1σ)，比纯 3σ 更稳——
     * 色号图里网格线本身占了很多列，标准差被抬高，3σ 会一个峰都留不下。
     */
    private fun findPeaks(p: FloatArray): DoubleArray {
        val n = p.size
        if (n < 16) return DoubleArray(0)
        var mean = 0.0
        for (v in p) mean += v
        mean /= n
        var varSum = 0.0
        for (v in p) {
            val d = v - mean
            varSum += d * d
        }
        val sd = sqrt(varSum / n)
        val thr = max(mean * 1.35, mean + sd).toFloat()

        val idx = ArrayList<Int>()
        var i = 2
        while (i < n - 2) {
            val v = p[i]
            if (v >= thr && v >= p[i - 1] && v >= p[i + 1] && (v > p[i - 2] || v > p[i + 2])) {
                idx.add(i)
            }
            i++
        }
        // 相邻 3px 内的峰合并（网格线有两三条像素宽）
        val merged = ArrayList<Double>(idx.size)
        var k = 0
        while (k < idx.size) {
            var x = idx[k]
            var best = p[x]
            var j = k + 1
            while (j < idx.size && idx[j] - x <= 3) {
                if (p[idx[j]] > best) {
                    best = p[idx[j]]
                    x = idx[j]
                }
                j++
            }
            merged.add(x.toDouble())
            k = j
        }
        return merged.toDoubleArray()
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 0) (s[m - 1] + s[m]) / 2 else s[m]
    }

    // ---------------------------------------------------------------- 采样

    /**
     * 逐格采样。
     *
     * [emptyThreshold] 是"空白阈值"：格内与纸白接近的像素占比 ≥ 该值即判空，
     * 默认 0.55。格子里印着色号文字时即使底色是白的也判为实心（白豆），
     * 否则每一颗白豆都会被吃成空格。
     */
    fun sample(
        pixels: IntArray,
        w: Int,
        h: Int,
        grid: ChartGrid,
        emptyThreshold: Float = 0.55f,
    ): ChartSample {
        val paper = paperColor(pixels, w, h)
        val cols = grid.cols
        val rows = grid.rows
        val argb = IntArray(cols * rows)
        val empty = BooleanArray(cols * rows) { true }
        var filled = 0
        val hist = IntArray(32768)
        val sumR = IntArray(32768)
        val sumG = IntArray(32768)
        val sumB = IntArray(32768)
        val keys = IntArray(32768)

        for (row in 0 until rows) {
            val y0 = grid.offsetY + row * grid.cellH
            val y1 = y0 + grid.cellH
            val iy0 = (y0 + grid.cellH * 0.20).roundToInt().coerceIn(0, h - 1)
            val iy1 = (y1 - grid.cellH * 0.20).roundToInt().coerceIn(iy0 + 1, h)
            for (col in 0 until cols) {
                val x0 = grid.offsetX + col * grid.cellW
                val ixc0 = (x0 + grid.cellW * 0.20).roundToInt().coerceIn(0, w - 1)
                val ixc1 = (x0 + grid.cellW * 0.80).roundToInt().coerceIn(ixc0 + 1, w)

                keys.fill(0)
                var bucketCount = 0
                var pixelCount = 0
                var paperLike = 0
                var ink = 0
                // 大格子隔点采样：40×40 的格子在 400×400 的图上就是 25 万像素，
                // 全采一遍会卡住主线程；步长 2 对纯色格子没有精度损失。
                val stride = if (grid.cellW * grid.cellH > 900) 2 else 1
                var y = iy0
                while (y < iy1) {
                    var x = ixc0
                    while (x < ixc1) {
                        val c = pixels[y * w + x]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val pr = (paper shr 16) and 0xFF
                        val pg = (paper shr 8) and 0xFF
                        val pb = paper and 0xFF
                        if (abs(r - pr) <= PAPER_TOLERANCE &&
                            abs(g - pg) <= PAPER_TOLERANCE &&
                            abs(b - pb) <= PAPER_TOLERANCE
                        ) {
                            paperLike++
                        }
                        val luma = (r * 299 + g * 587 + b * 114) / 1000
                        if (luma < INK_LUMA && (max(r, max(g, b)) - min(r, min(g, b))) < INK_CHROMA) ink++
                        val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                        if (hist[key] == 0) {
                            // keys[0 until bucketCount] 是本格出现过的桶，后面统一清零复用
                            keys[bucketCount++] = key
                        }
                        hist[key]++
                        sumR[key] += r
                        sumG[key] += g
                        sumB[key] += b
                        pixelCount++
                        x += stride
                    }
                    y += stride
                }

                val idx = row * cols + col
                if (pixelCount == 0) {
                    empty[idx] = true
                    continue
                }
                var bestKey = 0
                var bestCount = 0
                for (n in 0 until bucketCount) {
                    val key = keys[n]
                    if (hist[key] > bestCount) {
                        bestCount = hist[key]
                        bestKey = key
                    }
                }
                val cnt = hist[bestKey]
                val r = sumR[bestKey] / cnt
                val g = sumG[bestKey] / cnt
                val b = sumB[bestKey] / cnt
                for (n in 0 until bucketCount) hist[keys[n]] = 0
                for (n in 0 until bucketCount) {
                    val key = keys[n]
                    sumR[key] = 0
                    sumG[key] = 0
                    sumB[key] = 0
                }

                val paperRatio = paperLike.toFloat() / pixelCount
                val isBlank = paperRatio >= emptyThreshold && ink < max(2, pixelCount / 200)
                if (isBlank) {
                    empty[idx] = true
                } else {
                    empty[idx] = false
                    argb[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    filled++
                }
            }
        }
        return ChartSample(cols, rows, argb, empty, paper, filled)
    }

    /** 纸白：取四角 8% 区域里出现最多的颜色，避开中间大片图案 */
    fun paperColor(pixels: IntArray, w: Int, h: Int): Int {
        val bw = max(2, w / 12)
        val bh = max(2, h / 12)
        val counts = HashMap<Int, Int>()
        fun scan(x0: Int, y0: Int, x1: Int, y1: Int) {
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val c = pixels[y * w + x] and 0xFFFFFF
                    counts[c] = (counts[c] ?: 0) + 1
                    x++
                }
                y++
            }
        }
        scan(0, 0, bw, bh)
        scan(w - bw, 0, w, bh)
        scan(0, h - bh, bw, h)
        scan(w - bw, h - bh, w, h)
        return counts.maxByOrNull { it.value }?.key?.let { (0xFF shl 24) or it } ?: 0xFFFFFFFF.toInt()
    }

    // ---------------------------------------------------------------- 落图纸

    /**
     * 采样结果 → 图纸。
     *
     * 色号图每格是纯色，这里按 5bit 量化桶 + ΔE 双重去重：同一片花瓣在图上
     * 可能因为 JPEG 有 1~2 的偏移，桶一致就直接复用上一次的色号，不会出现
     * 三四个"看起来一样"的色号把用量表撑爆。
     */
    fun toPattern(sample: ChartSample, palette: BeadPalette): PatternResult {
        val start = System.nanoTime()
        val n = sample.cols * sample.rows
        val cells = IntArray(n) { -1 }
        val cache = HashMap<Int, Int>()
        val usedLabs = ArrayList<Pair<Int, FloatArray>>()
        for (i in 0 until n) {
            if (sample.empty[i]) continue
            val c = sample.argb[i]
            // 与 sample() 里同一套 5bit 桶：key 必须完全一致，缓存才能命中
            val key = (((c shr 19) and 0x1F) shl 10) or
                (((c shr 11) and 0x1F) shl 5) or
                ((c shr 3) and 0x1F)
            var idx = cache[key]
            if (idx == null) {
                val lab = ColorConversions.argbToLab(c)
                var mapped = -1
                for ((pi, usedLab) in usedLabs) {
                    if (Ciede2000.deltaE(lab, usedLab) < 2.2) {
                        mapped = pi
                        break
                    }
                }
                if (mapped < 0) {
                    mapped = palette.nearestIndex(lab)
                    if (mapped >= 0) usedLabs.add(mapped to lab)
                }
                idx = if (mapped < 0) 0 else mapped
                cache[key] = idx
            }
            cells[i] = idx
        }
        return PatternResult(
            width = sample.cols,
            height = sample.rows,
            cells = cells,
            palette = palette,
            elapsedMs = (System.nanoTime() - start) / 1_000_000,
        )
    }

    private fun channelDiff(p: Int, q: Int): Int {
        val dr = abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
        val dg = abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
        val db = abs((p and 0xFF) - (q and 0xFF))
        return max(dr, max(dg, db))
    }
}
