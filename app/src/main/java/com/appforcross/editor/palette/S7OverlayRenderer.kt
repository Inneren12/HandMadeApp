package com.appforcross.editor.palette

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

object S7OverlayRenderer {
    fun zoneColor(zone: S7SamplingSpec.Zone): Int = when (zone) {
        S7SamplingSpec.Zone.SKIN -> Color.parseColor("#FFB347")
        S7SamplingSpec.Zone.SKY -> Color.parseColor("#4FC3F7")
        S7SamplingSpec.Zone.EDGE -> Color.parseColor("#FF5252")
        S7SamplingSpec.Zone.HITEX -> Color.parseColor("#8BC34A")
        S7SamplingSpec.Zone.FLAT -> Color.parseColor("#B39DDB")
    }

    fun draw(
        canvas: Canvas,
        sampling: S7SamplingResult?,
        coordinateMapper: (S7Sample) -> Pair<Float, Float>,
        heat: Boolean,
        points: Boolean,
        heatRadius: Float,
        pointRadius: Float
    ) {
        val data = sampling ?: return
        if (!heat && !points) return
        val samples = data.samples
        if (samples.isEmpty()) return

        val minW = samples.minOf { it.w }
        val maxW = samples.maxOf { it.w }
        val range = max(1e-6f, maxW - minW)

        val heatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(120, 255, 64, 0)
            maskFilter = BlurMaskFilter(max(heatRadius, 8f), BlurMaskFilter.Blur.NORMAL)
        }
        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        if (heat) {
            for (sample in samples) {
                val (x, y) = coordinateMapper(sample)
                val norm = ((sample.w - minW) / range).coerceIn(0f, 1f)
                val alpha = (min(220f, 40f + 220f * norm)).toInt().coerceIn(0, 255)
                val color = Color.argb(alpha, 255, (120 + 100 * norm).toInt().coerceIn(0, 255), 0)
                heatPaint.color = color
                canvas.drawCircle(x, y, heatRadius, heatPaint)
            }
        }

        if (points) {
            for (sample in samples) {
                val (x, y) = coordinateMapper(sample)
                val norm = ((sample.w - minW) / range).coerceIn(0f, 1f)
                val alpha = (min(255f, 80f + 175f * norm)).toInt().coerceIn(0, 255)
                val baseColor = zoneColor(sample.zone)
                val color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                pointPaint.color = color
                canvas.drawCircle(x, y, pointRadius, pointPaint)
            }
        }
    }

    fun drawResidualHeatmap(
        canvas: Canvas,
        sampling: S7SamplingResult?,
        errors: FloatArray?,
        coordinateMapper: (S7Sample) -> Pair<Float, Float>,
        radius: Float,
        deMed: Float,
        de95: Float
    ) {
        val data = sampling ?: return
        val err = errors ?: return
        val samples = data.samples
        if (samples.isEmpty() || err.size != samples.size) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val blurRadius = max(radius, 8f)
        paint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        for (i in samples.indices) {
            val norm = normalizeResidual(err[i], deMed, de95)
            if (norm <= 0f) continue
            val color = residualColor(norm)
            paint.color = color
            val (x, y) = coordinateMapper(samples[i])
            canvas.drawCircle(x, y, radius, paint)
        }
    }

    fun createResidualBitmap(
        width: Int,
        height: Int,
        sampling: S7SamplingResult,
        errors: FloatArray,
        deMed: Float,
        de95: Float
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val radius = max(width, height) / 80f
        drawResidualHeatmap(
            canvas = canvas,
            sampling = sampling,
            errors = errors,
            coordinateMapper = { sample -> sample.x.toFloat() to sample.y.toFloat() },
            radius = radius,
            deMed = deMed,
            de95 = de95
        )
        return bitmap
    }

    private fun normalizeResidual(value: Float, deMed: Float, de95: Float): Float {
        if (value <= deMed) return 0f
        val span = (de95 - deMed).coerceAtLeast(1e-6f)
        val norm = (value - deMed) / span
        return norm.coerceIn(0f, 1f)
    }

    private fun residualColor(norm: Float): Int {
        val startHue = 210f
        val endHue = 20f
        val hue = startHue + (endHue - startHue) * norm
        val sat = 0.4f + 0.5f * norm
        val value = 0.85f + 0.15f * norm
        val alpha = (60f + 195f * norm).toInt().coerceIn(0, 255)
        return Color.HSVToColor(alpha, floatArrayOf(hue, sat, value))
    }
}
