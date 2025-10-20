package com.handmadeapp.ui.importer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.appforcross.editor.palette.S7InitResult
import com.appforcross.editor.palette.S7InitSpec

class PaletteStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var palette: S7InitResult? = null
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    fun setPalette(result: S7InitResult?) {
        palette = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = palette ?: return
        val colors = data.colors
        if (colors.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sw = w / colors.size
        val swatchHeight = h * 0.6f
        val legendBaseline = swatchHeight + (h - swatchHeight) * 0.6f

        textPaint.textSize = swatchHeight * 0.4f
        legendPaint.textSize = (h - swatchHeight) * 0.45f

        for ((index, color) in colors.withIndex()) {
            val left = index * sw
            rect.set(left, 0f, left + sw, swatchHeight)
            swatchPaint.color = color.sRGB
            canvas.drawRect(rect, swatchPaint)

            val textColor = if (color.okLab[0] < 0.55f) Color.WHITE else Color.BLACK
            textPaint.color = textColor
            canvas.drawText(index.toString(), rect.centerX(), rect.centerY() + textPaint.textSize / 3f, textPaint)

            legendPaint.color = Color.WHITE
            val roleLabel = when (color.role) {
                S7InitSpec.PaletteZone.NEUTRAL -> "NEUT"
                else -> color.role.name
            }
            canvas.drawText(roleLabel, rect.centerX(), legendBaseline, legendPaint)
        }
    }
}
