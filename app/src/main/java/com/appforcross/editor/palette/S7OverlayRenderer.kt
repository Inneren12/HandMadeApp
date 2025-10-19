package com.appforcross.editor.palette

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
}
