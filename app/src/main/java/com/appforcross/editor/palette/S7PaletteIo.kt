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

object S7PaletteIo {
    fun writeInitJson(sessionDir: File, init: S7InitResult) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "palette_init.json")
        val root = JSONObject()
        root.put("K0", init.colors.size)
        root.put("notes", JSONArray(init.notes))
        val colorsJson = JSONArray()
        for ((index, color) in init.colors.withIndex()) {
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
            colorsJson.put(obj)
        }
        root.put("colors", colorsJson)
        val anchorsJson = JSONObject()
        for ((name, idx) in init.anchors) {
            anchorsJson.put(name, idx)
        }
        root.put("anchors", anchorsJson)
        val quotasJson = JSONObject()
        for ((zone, count) in init.quotasUsed) {
            quotasJson.put(zone.name, count)
        }
        root.put("quotas_used", quotasJson)
        val paramsJson = JSONObject()
        for ((k, v) in init.params) {
            when (v) {
                null -> paramsJson.put(k, JSONObject.NULL)
                is Number, is Boolean -> paramsJson.put(k, v)
                is Map<*, *> -> paramsJson.put(k, JSONObject(v.mapKeys { it.key.toString() }))
                else -> paramsJson.put(k, v.toString())
            }
        }
        root.put("params", paramsJson)
        FileWriter(out, false).use { writer ->
            writer.write(root.toString(2))
        }
    }

    fun writeStripPng(sessionDir: File, init: S7InitResult, swatchW: Int = 64, swatchH: Int = 48) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "palette_strip.png")
        val k = init.colors.size
        if (k == 0) return
        val width = max(1, swatchW * k)
        val height = max(swatchH, (swatchH * 1.5f).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = swatchH * 0.35f
        }
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = swatchH * 0.22f
        }
        for ((index, color) in init.colors.withIndex()) {
            val left = index * swatchW.toFloat()
            rect.set(left, 0f, left + swatchW, swatchH.toFloat())
            paint.color = color.sRGB
            canvas.drawRect(rect, paint)
            val label = "${index}" 
            textPaint.color = if (color.okLab[0] < 0.55f) Color.WHITE else Color.BLACK
            canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize / 3f, textPaint)
            legendPaint.color = Color.WHITE
            canvas.drawText(color.role.name, rect.centerX(), swatchH + legendPaint.textSize * 1.2f, legendPaint)
        }
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()
        Logger.i(
            "PALETTE",
            "overlay.palette.ready",
            mapOf("K0" to k, "w_strip" to width, "h_strip" to height)
        )
    }

    fun writeRolesCsv(sessionDir: File, init: S7InitResult) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "palette_roles.csv")
        FileWriter(out, false).use { writer ->
            writer.appendLine("index,role,L,a,b,R,G,B,protected,spreadMin,clipped")
            for ((index, color) in init.colors.withIndex()) {
                val argb = color.sRGB
                val spread = if (color.spreadMin.isInfinite()) "" else String.format(Locale.US, "%.3f", color.spreadMin)
                writer.appendLine(
                    listOf(
                        index.toString(),
                        color.role.name,
                        String.format(Locale.US, "%.5f", color.okLab[0]),
                        String.format(Locale.US, "%.5f", color.okLab[1]),
                        String.format(Locale.US, "%.5f", color.okLab[2]),
                        Color.red(argb).toString(),
                        Color.green(argb).toString(),
                        Color.blue(argb).toString(),
                        color.protected.toString(),
                        spread,
                        color.clipped.toString()
                    ).joinToString(",")
                )
            }
        }
    }

    private fun ensurePaletteDir(sessionDir: File): File {
        val dir = File(sessionDir, "palette")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
