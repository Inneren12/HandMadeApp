package com.appforcross.editor.palette

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object S7KneedleIo {
    fun writeGainCsv(sessionDir: File, rows: List<S7KneedleRow>) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "palette_gain.csv")
        FileWriter(out, false).use { writer ->
            writer.appendLine("k,de95,deMed,gbi,tc,isl,gain,F,D,flags")
            for (row in rows) {
                writer.appendLine(
                    listOf(
                        row.k.toString(),
                        String.format(Locale.US, "%.5f", row.de95),
                        String.format(Locale.US, "%.5f", row.deMed),
                        String.format(Locale.US, "%.6f", row.gbi),
                        String.format(Locale.US, "%.6f", row.tc),
                        String.format(Locale.US, "%.6f", row.isl),
                        String.format(Locale.US, "%.6f", row.gain),
                        String.format(Locale.US, "%.6f", row.F),
                        String.format(Locale.US, "%.6f", row.D),
                        row.flags ?: ""
                    ).joinToString(",")
                )
            }
        }
    }

    fun writeKneedlePng(sessionDir: File, rows: List<S7KneedleRow>, Kstar: Int) {
        if (rows.isEmpty()) return
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "kneedle_curve.png")
        val width = 960
        val height = 540
        val padLeft = 80f
        val padRight = 40f
        val padTop = 50f
        val padBottom = 80f
        val plotWidth = width - padLeft - padRight
        val plotHeight = height - padTop - padBottom
        val minK = rows.first().k
        val maxK = rows.last().k
        val minF = rows.minOf { it.F }
        val maxF = rows.maxOf { it.F }
        val minD = rows.minOf { it.D }
        val maxD = rows.maxOf { it.D }
        val fRange = (maxF - minF).takeIf { it > 1e-6f } ?: 1f
        val dRange = (maxD - minD).takeIf { it > 1e-6f } ?: 1f
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 2f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 26f
        }
        val fPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(33, 150, 243)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        val dPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 111, 0)
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        val axisX = height - padBottom
        canvas.drawLine(padLeft, axisX, width - padRight, axisX, axisPaint)
        canvas.drawLine(padLeft, padTop, padLeft, axisX, axisPaint)
        fun xFor(k: Int): Float {
            if (maxK == minK) return padLeft + plotWidth / 2f
            val t = (k - minK).toFloat() / (maxK - minK).toFloat()
            return padLeft + t * plotWidth
        }
        fun yForF(value: Float): Float {
            val norm = (value - minF) / fRange
            return axisX - norm * plotHeight
        }
        fun yForD(value: Float): Float {
            val norm = (value - minD) / dRange
            return axisX - norm * plotHeight
        }
        val fPath = Path()
        val dPath = Path()
        rows.forEachIndexed { index, row ->
            val x = xFor(row.k)
            val yF = yForF(row.F)
            val yD = yForD(row.D)
            if (index == 0) {
                fPath.moveTo(x, yF)
                dPath.moveTo(x, yD)
            } else {
                fPath.lineTo(x, yF)
                dPath.lineTo(x, yD)
            }
        }
        canvas.drawPath(fPath, fPaint)
        canvas.drawPath(dPath, dPaint)
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            strokeWidth = 3f
        }
        val markerX = xFor(Kstar)
        canvas.drawLine(markerX, padTop, markerX, axisX, markerPaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("K* = $Kstar", markerX, padTop - 12f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("F(K)", padLeft + 12f, padTop + 24f, Paint(textPaint).apply { color = fPaint.color })
        canvas.drawText("D(K)", padLeft + 12f, padTop + 52f, Paint(textPaint).apply { color = dPaint.color })
        val rangeText = "F∈[${"%.2f".format(Locale.US, minF)}, ${"%.2f".format(Locale.US, maxF)}]; D∈[${"%.3f".format(Locale.US, minD)}, ${"%.3f".format(Locale.US, maxD)}]"
        canvas.drawText(rangeText, padLeft, height - 30f, textPaint)
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()
    }

    fun writeFinalPalette(sessionDir: File, colors: List<S7InitColor>, Kstar: Int) {
        val dir = ensurePaletteDir(sessionDir)
        val jsonOut = File(dir, "palette_final_k.json")
        val root = JSONObject()
        root.put("Kstar", Kstar)
        root.put("K", colors.size)
        val arr = JSONArray()
        colors.forEachIndexed { index, color ->
            val obj = JSONObject()
            obj.put("index", index)
            obj.put("L", color.okLab[0].toDouble())
            obj.put("a", color.okLab[1].toDouble())
            obj.put("b", color.okLab[2].toDouble())
            obj.put("sRGB", String.format(Locale.US, "#%08X", color.sRGB))
            obj.put("protected", color.protected)
            obj.put("role", color.role.name)
            obj.put("spreadMin", if (color.spreadMin.isInfinite()) null else color.spreadMin.toDouble())
            obj.put("clipped", color.clipped)
            arr.put(obj)
        }
        root.put("colors", arr)
        FileWriter(jsonOut, false).use { writer ->
            writer.write(root.toString(2))
        }
        val stripOut = File(dir, "palette_final_strip.png")
        writePaletteStrip(stripOut, colors)
    }

    fun writeResidualHeatmap(sessionDir: File, bitmap: Bitmap, Kstar: Int) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "residual_k${Kstar}_heatmap.png")
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
    }

    fun writeIndexPreview(sessionDir: File, bitmap: Bitmap, Kstar: Int) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "index_preview_k${Kstar}.png")
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
    }

    private fun writePaletteStrip(file: File, colors: List<S7InitColor>) {
        if (colors.isEmpty()) return
        val swatchW = 64
        val swatchH = 48
        val width = max(1, swatchW * colors.size)
        val height = swatchH * 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = swatchH * 0.35f
        }
        val rolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = swatchH * 0.24f
        }
        colors.forEachIndexed { index, color ->
            val left = index * swatchW.toFloat()
            paint.color = color.sRGB
            canvas.drawRect(left, 0f, left + swatchW, swatchH.toFloat(), paint)
            textPaint.color = if (color.okLab[0] < 0.55f) Color.WHITE else Color.BLACK
            canvas.drawText(index.toString(), left + swatchW / 2f, swatchH * 0.6f, textPaint)
            rolePaint.color = Color.WHITE
            canvas.drawText(color.role.name, left + swatchW / 2f, swatchH + rolePaint.textSize * 1.4f, rolePaint)
        }
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()
    }

    private fun ensurePaletteDir(sessionDir: File): File {
        val dir = File(sessionDir, "palette")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
