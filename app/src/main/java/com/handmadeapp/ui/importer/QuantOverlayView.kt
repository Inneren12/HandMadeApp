package com.handmadeapp.ui.importer

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Size
import android.view.View
import com.appforcross.editor.palette.S7OverlayRenderer
import com.appforcross.editor.palette.S7Sample
import com.appforcross.editor.palette.S7SamplingResult
import com.handmadeapp.logging.Logger
import kotlin.math.max
import kotlin.math.min

class QuantOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Mode { NONE, SAMPLING, RESIDUAL }

    private var imageSize: Size? = null
    private var sampling: S7SamplingResult? = null
    private var showHeat = true
    private var showPoints = true
    private var residualErrors: FloatArray? = null
    private var residualDeMed: Float = 0f
    private var residualDe95: Float = 0f
    private var mode: Mode = Mode.NONE

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setSamplingData(imageSize: Size, sampling: S7SamplingResult?, heat: Boolean, points: Boolean) {
        this.imageSize = imageSize
        this.sampling = sampling
        this.showHeat = heat
        this.showPoints = points
        this.residualErrors = null
        this.mode = if (sampling != null && (heat || points)) Mode.SAMPLING else Mode.NONE
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

    fun setResidualData(imageSize: Size, sampling: S7SamplingResult?, errors: FloatArray?, deMed: Float, de95: Float) {
        this.imageSize = imageSize
        this.sampling = sampling
        this.showHeat = false
        this.showPoints = false
        this.residualErrors = errors
        this.residualDeMed = deMed
        this.residualDe95 = de95
        this.mode = if (sampling != null && errors != null) Mode.RESIDUAL else Mode.NONE
        if (mode == Mode.RESIDUAL) {
            Logger.i(
                "PALETTE",
                "overlay.residual.ready",
                mapOf(
                    "w" to (imageSize?.width ?: width),
                    "h" to (imageSize?.height ?: height),
                    "de95" to de95,
                    "deMed" to deMed
                )
            )
        }
        invalidate()
    }

    fun clearOverlay() {
        this.mode = Mode.NONE
        this.sampling = null
        this.residualErrors = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentMode = mode
        if (currentMode == Mode.NONE) return
        val img = imageSize ?: return
        val data = sampling ?: return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val scale = min(viewW / img.width.toFloat(), viewH / img.height.toFloat())
        val offsetX = (viewW - img.width * scale) / 2f
        val offsetY = (viewH - img.height * scale) / 2f

        val density = resources.displayMetrics.density
        val heatRadius = max(12f * density, 32f * scale)
        val pointRadius = max(2.5f * density, 2f)

        val mapper: (S7Sample) -> Pair<Float, Float> = { sample ->
            val x = offsetX + sample.x * scale
            val y = offsetY + sample.y * scale
            x to y
        }

        when (currentMode) {
            Mode.SAMPLING -> {
                if (!showHeat && !showPoints) return
                S7OverlayRenderer.draw(
                    canvas = canvas,
                    sampling = data,
                    coordinateMapper = mapper,
                    heat = showHeat,
                    points = showPoints,
                    heatRadius = heatRadius,
                    pointRadius = pointRadius
                )
            }
            Mode.RESIDUAL -> {
                val residual = residualErrors ?: return
                val residualRadius = max(18f * density, 36f * scale)
                S7OverlayRenderer.drawResidualHeatmap(
                    canvas = canvas,
                    sampling = data,
                    errors = residual,
                    coordinateMapper = mapper,
                    radius = residualRadius,
                    deMed = residualDeMed,
                    de95 = residualDe95
                )
            }
            Mode.NONE -> return
        }
    }
}
