package com.handmadeapp.ui.importer

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Size
import android.view.View
import com.appforcross.editor.palette.S7OverlayRenderer
import com.appforcross.editor.palette.S7SamplingResult
import com.handmadeapp.logging.Logger
import kotlin.math.max
import kotlin.math.min

class QuantOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var imageSize: Size? = null
    private var sampling: S7SamplingResult? = null
    private var showHeat = true
    private var showPoints = true

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setData(imageSize: Size, sampling: S7SamplingResult?, heat: Boolean, points: Boolean) {
        this.imageSize = imageSize
        this.sampling = sampling
        this.showHeat = heat
        this.showPoints = points
        Logger.i(
            "PALETTE",
            "overlay.ready",
            mapOf(
                "w" to width,
                "h" to height,
                "heat" to heat,
                "points" to points
            )
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val img = imageSize ?: return
        val data = sampling ?: return
        if (!showHeat && !showPoints) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val scale = min(viewW / img.width.toFloat(), viewH / img.height.toFloat())
        val offsetX = (viewW - img.width * scale) / 2f
        val offsetY = (viewH - img.height * scale) / 2f

        val density = resources.displayMetrics.density
        val heatRadius = max(12f * density, 32f * scale)
        val pointRadius = max(2.5f * density, 2f)

        S7OverlayRenderer.draw(
            canvas = canvas,
            sampling = data,
            coordinateMapper = { sample ->
                val x = offsetX + sample.x * scale
                val y = offsetY + sample.y * scale
                x to y
            },
            heat = showHeat,
            points = showPoints,
            heatRadius = heatRadius,
            pointRadius = pointRadius
        )
    }
}
