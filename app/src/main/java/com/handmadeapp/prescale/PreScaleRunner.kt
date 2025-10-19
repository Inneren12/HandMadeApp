package com.handmadeapp.prescale

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.handmadeapp.analysis.AnalyzeResult
import com.handmadeapp.diagnostics.DiagnosticsManager
import com.handmadeapp.io.ImagePrep
import com.handmadeapp.logging.Logger
import com.handmadeapp.preset.PresetGateResult
import java.io.File
import java.io.FileOutputStream

object PreScaleRunner {
    data class Output(
        val pngPath: String,
        val wst: Int,
        val hst: Int,
        val fr: PreScale.FR,
        val passed: Boolean
    )

    /**
     * Полный запуск PreScale:
     *  - Stage-2 (ImagePrep) → linear RGBA_F16
     *  - PreScale.run(...) по выбранному пресету/σ(r)
     *  - сохранение PNG в cache и diag.
     */
    fun run(
        ctx: Context,
        uri: Uri,
        analyze: AnalyzeResult,
        gate: PresetGateResult,
        targetWst: Int
    ): Output {
        val prep = ImagePrep.prepare(ctx, uri)
        val res = PreScale.run(
            linearF16 = prep.linearF16,
            preset = gate.spec,
            norm = gate.normalized,
            masksPrev = analyze.masks,
            targetWst = targetWst
        )
        prep.linearF16.recycle()
        // Сохраняем
        val out = File(ctx.cacheDir, "prescale_${res.wst}x${res.hst}.png")
        FileOutputStream(out).use { fos ->
            res.out.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        res.out.recycle()
        // Диагностика (скопируем в сессию)
        try {
            DiagnosticsManager.currentSessionDir(ctx)?.let { dir ->
                val diag = File(dir, out.name)
                out.copyTo(diag, overwrite = true)
            }
        } catch (_: Exception) { }

        Logger.i("PRESCALE", "done", mapOf(
            "png" to out.absolutePath,
            "wst" to res.wst, "hst" to res.hst,
            "ssim" to "%.4f".format(res.fr.ssim),
            "edgeKeep" to "%.4f".format(res.fr.edgeKeep),
            "banding" to "%.4f".format(res.fr.banding),
            "de95" to "%.3f".format(res.fr.de95),
            "pass" to res.passed
        ))
        return Output(
            pngPath = out.absolutePath,
            wst = res.wst, hst = res.hst,
            fr = res.fr,
            passed = res.passed
        )
    }
}
