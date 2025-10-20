package com.appforcross.editor.palette

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object S7IndexIo {
    fun writeIndexBin(file: File, width: Int, height: Int, K: Int, bpp: Int, data: ByteArray) {
        val headerSize = 4 + 2 + 1 + 1 + 4 + 4 + 2 + 2
        val buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), 'X'.code.toByte(), '1'.code.toByte()))
        buffer.putShort(1)
        buffer.put(bpp.toByte())
        buffer.put(0)
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.putShort(K.toShort())
        buffer.putShort(0)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            fos.write(buffer.array())
            fos.write(data)
        }
    }

    fun writeIndexPreviewPng(file: File, bitmap: Bitmap) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
    }

    fun writeLegendCsv(file: File, colors: List<S7InitColor>) {
        file.parentFile?.mkdirs()
        FileWriter(file, false).use { writer ->
            writer.appendLine("index,role,L,a,b,R,G,B,hex,protected")
            colors.forEachIndexed { index, color ->
                val srgb = color.sRGB
                val hex = String.format(Locale.US, "#%08X", srgb)
                val line = listOf(
                    index.toString(),
                    color.role.name,
                    String.format(Locale.US, "%.6f", color.okLab[0]),
                    String.format(Locale.US, "%.6f", color.okLab[1]),
                    String.format(Locale.US, "%.6f", color.okLab[2]),
                    Color.red(srgb).toString(),
                    Color.green(srgb).toString(),
                    Color.blue(srgb).toString(),
                    hex,
                    color.protected.toString()
                ).joinToString(",")
                writer.appendLine(line)
            }
        }
    }

    fun writeIndexMetaJson(file: File, stats: S7IndexStats) {
        file.parentFile?.mkdirs()
        val root = JSONObject()
        root.put("Kstar", stats.kStar)
        root.put("foreignZoneHits", stats.foreignZoneHits)
        root.put("edgeBreakPenaltySum", stats.edgeBreakPenaltySum)
        root.put("cohBonusSum", stats.cohBonusSum)
        root.put("meanCost", stats.meanCost)
        root.put("timeMs", stats.timeMs)
        val counts = JSONArray()
        stats.countsPerColor.forEach { counts.put(it) }
        root.put("countsPerColor", counts)
        val params = JSONObject()
        stats.params.forEach { (key, value) ->
            params.put(key, toJsonValue(value))
        }
        root.put("params", params)
        FileWriter(file, false).use { writer ->
            writer.write(root.toString(2))
        }
    }

    fun writeCostHeatmapPng(file: File, bitmap: Bitmap) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
    }

    private fun toJsonValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is Map<*, *> -> {
                val obj = JSONObject()
                value.forEach { (k, v) ->
                    if (k != null) obj.put(k.toString(), toJsonValue(v))
                }
                obj
            }
            is Iterable<*> -> {
                val arr = JSONArray()
                value.forEach { arr.put(toJsonValue(it)) }
                arr
            }
            is IntArray -> {
                val arr = JSONArray()
                value.forEach { arr.put(it) }
                arr
            }
            is FloatArray -> {
                val arr = JSONArray()
                value.forEach { arr.put(it) }
                arr
            }
            is DoubleArray -> {
                val arr = JSONArray()
                value.forEach { arr.put(it) }
                arr
            }
            is BooleanArray -> {
                val arr = JSONArray()
                value.forEach { arr.put(it) }
                arr
            }
            else -> value
        }
    }
}
