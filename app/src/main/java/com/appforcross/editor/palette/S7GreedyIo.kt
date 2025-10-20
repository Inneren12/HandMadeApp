package com.appforcross.editor.palette

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.handmadeapp.logging.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.Locale
import kotlin.math.max

object S7GreedyIo {
    fun writeIterCsv(sessionDir: File, iters: List<S7GreedyIterStat>) {
        val dir = ensurePaletteDir(sessionDir)
        val file = File(dir, "palette_greedy_iter.csv")
        FileWriter(file, false).use { writer ->
            writer.appendLine("k,zone,impSum,clusterSize,medoid_L,medoid_a,medoid_b,nearestDe,added,reason")
            for (iter in iters) {
                val med = iter.medoidOkLab
                val l = if (med.isNotEmpty()) med[0] else 0f
                val a = if (med.size > 1) med[1] else 0f
                val b = if (med.size > 2) med[2] else 0f
                val line = listOf(
                    iter.k.toString(),
                    iter.zone.name,
                    String.format(Locale.US, "%.6f", iter.impSum),
                    iter.clusterSize.toString(),
                    String.format(Locale.US, "%.6f", l),
                    String.format(Locale.US, "%.6f", a),
                    String.format(Locale.US, "%.6f", b),
                    String.format(Locale.US, "%.4f", iter.nearestDe),
                    iter.added.toString(),
                    iter.reason ?: ""
                ).joinToString(",")
                writer.appendLine(line)
            }
        }
        Logger.i("PALETTE", "greedy.io.iter", mapOf("path" to file.absolutePath))
    }

    fun writePaletteSnapshot(sessionDir: File, colors: List<S7InitColor>, k: Int) {
        if (colors.isEmpty()) return
        val dir = ensurePaletteDir(sessionDir)
        val suffix = String.format(Locale.US, "%02d", k)
        val jsonFile = File(dir, "palette_k${suffix}.json")
        val stripFile = File(dir, "palette_k${suffix}_strip.png")
        writePaletteJson(jsonFile, colors, k)
        writeStripPng(stripFile, colors)
    }

    fun writeResidualHeatmap(sessionDir: File, residual: Bitmap) {
        val dir = ensurePaletteDir(sessionDir)
        val file = File(dir, "residual_heatmap.png")
        FileOutputStream(file).use { fos ->
            residual.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        Logger.i(
            "PALETTE",
            "greedy.io.residual",
            mapOf("path" to file.absolutePath, "w" to residual.width, "h" to residual.height)
        )
    }

    private fun writePaletteJson(file: File, colors: List<S7InitColor>, k: Int) {
        val root = JSONObject()
        root.put("K", k)
        val colorsJson = JSONArray()
        for ((index, color) in colors.withIndex()) {
            val obj = JSONObject()
            obj.put("index", index)
            obj.put("L", color.okLab[0].toDouble())
            obj.put("a", color.okLab[1].toDouble())
            obj.put("b", color.okLab[2].toDouble())
            obj.put("sRGB", String.format(Locale.US, "#%08X", color.sRGB))
            obj.put("role", color.role.name)
            obj.put("protected", color.protected)
            obj.put("spreadMin", if (color.spreadMin.isInfinite()) null else color.spreadMin.toDouble())
            obj.put("clipped", color.clipped)
            colorsJson.put(obj)
        }
        root.put("colors", colorsJson)
        FileWriter(file, false).use { writer ->
            writer.write(root.toString(2))
        }
    }

    private fun writeStripPng(file: File, colors: List<S7InitColor>, swatchW: Int = 64, swatchH: Int = 48) {
        val k = colors.size
        val width = max(1, swatchW * k)
        val height = max(swatchH, (swatchH * 1.5f).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val rect = RectF()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = swatchH * 0.35f
        }
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = swatchH * 0.22f
        }
        for ((index, color) in colors.withIndex()) {
            val left = index * swatchW.toFloat()
            rect.set(left, 0f, left + swatchW, swatchH.toFloat())
            paint.color = color.sRGB
            canvas.drawRect(rect, paint)
            val textColor = if (color.okLab[0] < 0.55f) Color.WHITE else Color.BLACK
            textPaint.color = textColor
            canvas.drawText(index.toString(), rect.centerX(), rect.centerY() + textPaint.textSize / 3f, textPaint)
            legendPaint.color = Color.WHITE
            canvas.drawText(color.role.name, rect.centerX(), swatchH + legendPaint.textSize * 1.2f, legendPaint)
        }
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()
        Logger.i("PALETTE", "greedy.io.snapshot", mapOf("path" to file.absolutePath, "k" to k))
    }

    private fun ensurePaletteDir(sessionDir: File): File {
        val dir = File(sessionDir, "palette")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
