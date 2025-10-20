package com.appforcross.editor.palette

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.handmadeapp.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.Locale
import kotlin.math.pow

object S7Spread2OptIo {

    fun writeDistMatrixCsv(sessionDir: File, colors: List<S7InitColor>, tag: String) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "dist_matrix_${tag}.csv")
        FileWriter(out, false).use { writer ->
            writer.appendLine("i,j,deltaE")
            for (i in colors.indices) {
                for (j in i + 1 until colors.size) {
                    val de = deltaE(colors[i].okLab, colors[j].okLab)
                    writer.appendLine(
                        listOf(i, j, String.format(Locale.US, "%.6f", de)).joinToString(",")
                    )
                }
            }
        }
    }

    fun writeViolationsCsv(sessionDir: File, violations: List<S7SpreadViolation>, tag: String) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "violations_${tag}.csv")
        FileWriter(out, false).use { writer ->
            writer.appendLine("i,j,deltaE")
            for (violation in violations) {
                writer.appendLine(
                    listOf(
                        violation.i,
                        violation.j,
                        String.format(Locale.US, "%.6f", violation.de)
                    ).joinToString(",")
                )
            }
        }
    }

    fun writePairFixesCsv(sessionDir: File, fixes: List<S7PairFix>) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "2opt_fixes.csv")
        FileWriter(out, false).use { writer ->
            writer.appendLine("i,j,de_before,de_after,variant,gain,reason,move_index,delta,clipped")
            for (fix in fixes) {
                if (fix.moves.isEmpty()) {
                    writer.appendLine(
                        listOf(
                            fix.i,
                            fix.j,
                            String.format(Locale.US, "%.6f", fix.deBefore),
                            String.format(Locale.US, "%.6f", fix.deAfter),
                            fix.variant,
                            String.format(Locale.US, "%.6f", fix.gain),
                            fix.reason ?: "",
                            "",
                            "",
                            ""
                        ).joinToString(",")
                    )
                } else {
                    for (move in fix.moves) {
                        writer.appendLine(
                            listOf(
                                fix.i,
                                fix.j,
                                String.format(Locale.US, "%.6f", fix.deBefore),
                                String.format(Locale.US, "%.6f", fix.deAfter),
                                fix.variant,
                                String.format(Locale.US, "%.6f", fix.gain),
                                fix.reason ?: "accepted",
                                move.i,
                                String.format(Locale.US, "%.6f", move.delta),
                                move.clipped
                            ).joinToString(",")
                        )
                    }
                }
            }
        }
    }

    fun writePaletteStrip(sessionDir: File, colors: List<S7InitColor>, tag: String) {
        if (colors.isEmpty()) return
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "palette_strip_${tag}.png")
        val swatchW = 64
        val swatchH = 48
        val width = maxOf(1, swatchW * colors.size)
        val height = (swatchH * 1.5f).toInt().coerceAtLeast(swatchH)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = swatchH * 0.38f
        }
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = swatchH * 0.24f
            color = Color.WHITE
        }
        for ((index, color) in colors.withIndex()) {
            val left = index * swatchW.toFloat()
            rect.set(left, 0f, left + swatchW, swatchH.toFloat())
            paint.color = color.sRGB
            canvas.drawRect(rect, paint)
            val textColor = if (color.okLab[0] < 0.55f) Color.WHITE else Color.BLACK
            textPaint.color = textColor
            canvas.drawText(index.toString(), rect.centerX(), rect.centerY() + textPaint.textSize / 3f, textPaint)
            val role = color.role.name
            canvas.drawText(role, rect.centerX(), swatchH + legendPaint.textSize * 1.2f, legendPaint)
        }
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()
    }

    fun writeAffectedHeatmap(sessionDir: File, bitmap: Bitmap, tag: String) {
        val dir = ensurePaletteDir(sessionDir)
        val out = File(dir, "affected_${tag}.png")
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        Logger.i(
            "PALETTE",
            "spread.heatmap.save",
            mapOf("tag" to tag, "w" to bitmap.width, "h" to bitmap.height)
        )
    }

    private fun ensurePaletteDir(sessionDir: File): File {
        val dir = File(sessionDir, "palette")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun deltaE(lab1: FloatArray, lab2: FloatArray): Float {
        return deltaE(lab1[0], lab1[1], lab1[2], lab2[0], lab2[1], lab2[2])
    }

    private fun deltaE(L1: Float, a1: Float, b1: Float, L2: Float, a2: Float, b2: Float): Float {
        val L1d = L1.toDouble()
        val a1d = a1.toDouble()
        val b1d = b1.toDouble()
        val L2d = L2.toDouble()
        val a2d = a2.toDouble()
        val b2d = b2.toDouble()
        val avgL = (L1d + L2d) * 0.5
        val c1 = kotlin.math.sqrt(a1d * a1d + b1d * b1d)
        val c2 = kotlin.math.sqrt(a2d * a2d + b2d * b2d)
        val avgC = (c1 + c2) * 0.5
        val g = 0.5 * (1.0 - kotlin.math.sqrt(avgC.pow(7.0) / (avgC.pow(7.0) + 25.0.pow(7.0))))
        val a1p = a1d * (1.0 + g)
        val a2p = a2d * (1.0 + g)
        val c1p = kotlin.math.sqrt(a1p * a1p + b1d * b1d)
        val c2p = kotlin.math.sqrt(a2p * a2p + b2d * b2d)
        val avgCp = (c1p + c2p) * 0.5
        val h1p = hueAngle(b1d, a1p)
        val h2p = hueAngle(b2d, a2p)
        val deltahp = hueDelta(c1p, c2p, h1p, h2p)
        val deltaLp = L2d - L1d
        val deltaCp = c2p - c1p
        val deltaHp = 2.0 * kotlin.math.sqrt(c1p * c2p) * kotlin.math.sin(deltahp / 2.0)
        val avgHp = meanHue(h1p, h2p, c1p, c2p)
        val t = 1.0 - 0.17 * kotlin.math.cos(avgHp - degToRad(30.0)) + 0.24 * kotlin.math.cos(2.0 * avgHp) +
            0.32 * kotlin.math.cos(3.0 * avgHp + degToRad(6.0)) - 0.20 * kotlin.math.cos(4.0 * avgHp - degToRad(63.0))
        val sl = 1.0 + (0.015 * (avgL - 50.0).pow(2.0)) / kotlin.math.sqrt(20.0 + (avgL - 50.0).pow(2.0))
        val sc = 1.0 + 0.045 * avgCp
        val sh = 1.0 + 0.015 * avgCp * t
        val deltaTheta = degToRad(30.0) * kotlin.math.exp(-((avgHp - degToRad(275.0)) / degToRad(25.0)).pow(2.0))
        val rc = 2.0 * kotlin.math.sqrt(avgCp.pow(7.0) / (avgCp.pow(7.0) + 25.0.pow(7.0)))
        val rt = -kotlin.math.sin(2.0 * deltaTheta) * rc
        val termL = deltaLp / sl
        val termC = deltaCp / sc
        val termH = deltaHp / sh
        val deltaE = kotlin.math.sqrt(termL * termL + termC * termC + termH * termH + rt * termC * termH)
        return deltaE.toFloat()
    }

    private fun hueAngle(b: Double, ap: Double): Double {
        if (ap == 0.0 && b == 0.0) return 0.0
        var angle = kotlin.math.atan2(b, ap)
        if (angle < 0.0) angle += 2.0 * Math.PI
        return angle
    }

    private fun hueDelta(c1p: Double, c2p: Double, h1p: Double, h2p: Double): Double {
        if (c1p * c2p == 0.0) return 0.0
        val diff = h2p - h1p
        return when {
            diff > Math.PI -> (h2p - 2.0 * Math.PI) - h1p
            diff < -Math.PI -> (h2p + 2.0 * Math.PI) - h1p
            else -> diff
        }
    }

    private fun meanHue(h1p: Double, h2p: Double, c1p: Double, c2p: Double): Double {
        if (c1p * c2p == 0.0) return h1p + h2p
        val diff = kotlin.math.abs(h1p - h2p)
        return when {
            diff <= Math.PI -> (h1p + h2p) / 2.0
            h1p + h2p < 2.0 * Math.PI -> (h1p + h2p + 2.0 * Math.PI) / 2.0
            else -> (h1p + h2p - 2.0 * Math.PI) / 2.0
        }
    }

    private fun degToRad(deg: Double): Double = deg / 180.0 * Math.PI
}
