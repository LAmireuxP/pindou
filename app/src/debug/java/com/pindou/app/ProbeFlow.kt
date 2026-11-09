package com.pindou.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import com.pindou.app.ui.AppViewModel
import com.pindou.app.ui.GenState
import com.pindou.app.ui.Screen
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 真机自测流程：导入→裁剪→78×72→自动优选→预览→回参数页看四档得分。全程不依赖输入注入。 */
object ProbeFlow {

    private val log = StringBuilder()
    private lateinit var outDir: File
    private lateinit var activity: Activity

    fun run(act: Activity, vm: AppViewModel) {
        activity = act
        outDir = File(act.getExternalFilesDir(null), "probe").apply { mkdirs() }
        try {
            execute(vm)
        } catch (t: Throwable) {
            say("FATAL ${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.take(12).forEach { say("  at $it") }
        } finally {
            flush()
        }
    }

    private fun execute(vm: AppViewModel) {
        say("model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}")
        val img = File(activity.cacheDir, "probe_input.jpg")
        activity.assets.open("probe_input.jpg").use { input ->
            img.outputStream().use { input.copyTo(it) }
        }

        val t0 = SystemClock.elapsedRealtime()
        vm.onImagePicked(Uri.fromFile(img))
        waitUntil(20_000) { vm.screen == Screen.CROP && vm.imagePixels != null }
        say("import ${SystemClock.elapsedRealtime() - t0}ms ${vm.imageW}x${vm.imageH}")
        sleep(800)
        shot("01_crop")

        vm.confirmCrop()
        waitUntil(5_000) { vm.screen == Screen.PARAMS }
        say("artGrid=${vm.pixelArtGrid} crop=${vm.cropRect}")
        // 像素画按原画网格生成（56×50），照片按 78 宽推导高度
        if (vm.pixelArtGrid != null) vm.applyArtGrid() else vm.setGridWidthPreset(78)
        sleep(900)
        say("grid=${vm.gridW}x${vm.gridH} palette=${vm.selectedPalette?.brand}")
        shot("02_params")

        val t1 = SystemClock.elapsedRealtime()
        vm.autoTune()
        // 自动优选现在停在参数页（四档得分要给人看见），等它跑完而不是等跳页
        waitUntil(120_000) { !vm.autoTuneRunning }
        say("autoTune ${SystemClock.elapsedRealtime() - t1}ms")
        sleep(1000)
        shot("03_params_scores")

        say("info=${vm.autoTuneInfo}")
        say("scores=" + vm.autoTuneScores.joinToString { "${it.first}:${it.second}" })
        (vm.genState as? GenState.Done)?.let { g ->
            val q = g.result.quality
            say(
                "quality score=${q.score} mean=%.2f p90=%.2f colors=%d break=%.1f%% flat=%.1f%%"
                    .format(q.meanDeltaE, q.p90DeltaE, q.colorCount, q.breakRate * 100, q.flattenRate * 100)
            )
            say("grid=${g.result.width}x${g.result.height} elapsed=${g.result.elapsedMs}ms")
        }

        vm.openPreview()
        waitUntil(5_000) { vm.screen == Screen.PREVIEW }
        sleep(1200)
        shot("03_preview")
        vm.handleBack()
        sleep(900)
        shot("04_params_scores")

        // 背景移除专项：同一张图、同一档参数，只打开开关
        vm.removeBackground = true
        vm.backgroundTolerance = 12f
        vm.generate()
        waitUntil(120_000) { vm.genState is GenState.Done }
        sleep(1000)
        (vm.genState as? GenState.Done)?.let { g ->
            say("bg_on keep=${g.result.cells.count { it >= 0 }} grid=${g.result.width}x${g.result.height}")
        }
        shot("05_params_bg_on")
        vm.openPreview()
        waitUntil(5_000) { vm.screen == Screen.PREVIEW }
        sleep(1200)
        shot("06_preview_bg_on")

        // 导出 PNG 专项：拉回电脑看色号标注的清晰度
        val pages = vm.currentPages()
        val exported = com.pindou.app.core.export.ExportService.exportPng(
            activity, pages, "probe_cat", symbol = false,
        )
        say("export=$exported")
        // PDF 同源验证：横向 A4 + 高分辨率位图 + Canvas 色号
        val pdf = com.pindou.app.core.export.ExportService.exportPdf(
            activity, pages, "probe_cat",
        )
        say("pdf=$pdf")
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (cond()) return
            sleep(80)
        }
        error("timeout ${timeoutMs}ms")
    }

    private fun sleep(ms: Long) = Thread.sleep(ms)

    private fun shot(name: String) {
        val f = File(outDir, "$name.png")
        val latch = CountDownLatch(1)
        var ok = false
        activity.runOnUiThread {
            try {
                val view = activity.window.decorView
                if (view.width > 0 && view.height > 0) {
                    val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bmp))
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    ok = true
                }
            } catch (t: Throwable) {
                say("shot $name error ${t.message}")
            } finally {
                latch.countDown()
            }
        }
        latch.await(15, TimeUnit.SECONDS)
        say("shot $name ${if (ok) "ok ${f.length()}" else "failed"}")
    }

    private fun say(line: String) {
        log.append(line).append('\n')
        flush()
    }

    private fun flush() {
        if (this::outDir.isInitialized) {
            runCatching { File(outDir, "probe_log.txt").writeText(log.toString()) }
        }
    }
}
