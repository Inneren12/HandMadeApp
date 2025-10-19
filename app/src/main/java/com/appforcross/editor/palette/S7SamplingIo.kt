package com.appforcross.editor.palette

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter

object S7SamplingIo {
    fun writeJson(sessionDir: File, sampling: S7SamplingResult) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "sampling.json")
        val params = sampling.params
        val root = JSONObject()
        root.put("algo", params["algo"] ?: "S7.1-sampling-v1")
        root.put("seed", params["seed"])
        root.put("device_tier", params["device_tier"])
        root.put("Nsamp_target", params["Nsamp_target"])
        root.put("Nsamp_real", params["Nsamp_real"])

        val wRoiJson = JSONObject()
        val wRoi = (params["w_roi"] as? Map<*, *>) ?: S7SamplingSpec.ROI_WEIGHTS
        for ((k, v) in wRoi) {
            val name = if (k is S7SamplingSpec.Zone) k.name else k.toString()
            val value = when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            wRoiJson.put(name, value)
        }
        root.put("w_roi", wRoiJson)

        val betasJson = JSONObject()
        val betas = (params["betas"] as? Map<*, *>) ?: mapOf(
            "edge" to S7SamplingSpec.BETA_EDGE,
            "band" to S7SamplingSpec.BETA_BAND,
            "noise" to S7SamplingSpec.BETA_NOISE
        )
        for ((k, v) in betas) {
            val value = when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            betasJson.put(k.toString(), value)
        }
        root.put("betas", betasJson)

        val roiJson = JSONObject()
        for ((zone, count) in sampling.roiHist) {
            roiJson.put(zone.name, count)
        }
        root.put("roi_hist", roiJson)

        params["coverage_ok"]?.let { root.put("coverage_ok", it) }
        params["coverage_reason"]?.let { root.put("coverage_reason", it) }
        root.put("notes", params["notes"] ?: "stratified; superpixel-lite=false")

        FileWriter(out, false).use { writer ->
            writer.write(root.toString(2))
        }
    }

    fun writeRoiHistogramPng(sessionDir: File, sampling: S7SamplingResult, w: Int, h: Int) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "sampling_overlay.png")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        S7OverlayRenderer.draw(
            canvas = canvas,
            sampling = sampling,
            coordinateMapper = { sample -> sample.x.toFloat() to sample.y.toFloat() },
            heat = true,
            points = true,
            heatRadius = maxOf(w, h) * 0.01f + 6f,
            pointRadius = 3f
        )
        FileOutputStream(out).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bmp.recycle()
    }

    private fun ensurePaletteDir(sessionDir: File): File {
        val dir = File(sessionDir, "palette")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
