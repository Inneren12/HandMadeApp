package com.handmadeapp.ui.importer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    private enum class Mode { NONE, SAMPLING, RESIDUAL, SPREAD_BEFORE, SPREAD_AFTER }

    private var imageSize: Size? = null
    private var sampling: S7SamplingResult? = null
    private var showHeat = true
    private var showPoints = true
    private var residualErrors: FloatArray? = null
    private var residualDeMed: Float = 0f
    private var residualDe95: Float = 0f
    private var spreadAmbiguity: FloatArray? = null
    private var spreadAffected: FloatArray? = null
    private var spreadMaxAmbiguity: Float = 0f
    private var spreadDeMinBefore: Float = 0f
    private var spreadDeMinAfter: Float = 0f
    private var spreadDe95Before: Float = 0f
    private var spreadDe95After: Float = 0f
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
        this.spreadAmbiguity = null
        this.spreadAffected = null
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
        this.spreadAmbiguity = null
        this.spreadAffected = null
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
        this.spreadAmbiguity = null
        this.spreadAffected = null
        invalidate()
    }

    fun setSpreadData(
        imageSize: Size,
        sampling: S7SamplingResult?,
        ambiguity: FloatArray?,
        affected: FloatArray?,
        showBefore: Boolean,
        deMinBefore: Float,
        de95Before: Float,
        deMinAfter: Float,
        de95After: Float
    ) {
        this.imageSize = imageSize
        this.sampling = sampling
        this.showHeat = true
        this.showPoints = false
        this.residualErrors = null
        this.spreadAmbiguity = ambiguity
        this.spreadAffected = affected
        this.spreadMaxAmbiguity = ambiguity?.maxOrNull() ?: 0f
        this.spreadDeMinBefore = deMinBefore
        this.spreadDe95Before = de95Before
        this.spreadDeMinAfter = deMinAfter
        this.spreadDe95After = de95After
        this.mode = when {
            sampling == null -> Mode.NONE
            showBefore && ambiguity != null -> Mode.SPREAD_BEFORE
            !showBefore && affected != null -> Mode.SPREAD_AFTER
            showBefore -> Mode.NONE
            else -> Mode.NONE
        }
        val affectedCount = affected?.count { it > 0f } ?: 0
        val currentMode = if (mode == Mode.SPREAD_BEFORE) "before" else if (mode == Mode.SPREAD_AFTER) "after" else "none"
        val deMin = if (mode == Mode.SPREAD_BEFORE) deMinBefore else deMinAfter
        val de95 = if (mode == Mode.SPREAD_BEFORE) de95Before else de95After
        Logger.i(
            "PALETTE",
            "overlay.spread.ready",
            mapOf(
                "mode" to currentMode,
                "affected_px" to affectedCount,
                "deMin" to deMin,
                "de95" to de95
            )
        )
        invalidate()
    }

    fun setSpreadMode(showBefore: Boolean) {
        val samplingData = sampling
        if (samplingData == null) {
            mode = Mode.NONE
            invalidate()
            return
        }
        mode = when {
            showBefore && spreadAmbiguity != null -> Mode.SPREAD_BEFORE
            !showBefore && spreadAffected != null -> Mode.SPREAD_AFTER
            else -> Mode.NONE
        }
        val affectedCount = spreadAffected?.count { it > 0f } ?: 0
        val currentMode = if (mode == Mode.SPREAD_BEFORE) "before" else if (mode == Mode.SPREAD_AFTER) "after" else "none"
        val deMin = if (mode == Mode.SPREAD_BEFORE) spreadDeMinBefore else spreadDeMinAfter
        val de95 = if (mode == Mode.SPREAD_BEFORE) spreadDe95Before else spreadDe95After
        Logger.i(
            "PALETTE",
            "overlay.spread.ready",
            mapOf(
                "mode" to currentMode,
                "affected_px" to affectedCount,
                "deMin" to deMin,
                "de95" to de95
            )
        )
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
            Mode.SPREAD_BEFORE -> {
                val ambiguity = spreadAmbiguity ?: return
                if (ambiguity.size != data.samples.size) return
                val radius = max(18f * density, 32f * scale)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                val maxValue = spreadMaxAmbiguity.coerceAtLeast(1e-6f)
                for (idx in data.samples.indices) {
                    val value = ambiguity[idx]
                    if (value <= 0f) continue
                    val norm = (value / maxValue).coerceIn(0f, 1f)
                    val alpha = (40f + 200f * norm).toInt().coerceIn(0, 255)
                    val color = Color.argb(alpha, 255, (120 + 80 * norm).toInt().coerceIn(0, 255), 32)
                    paint.color = color
                    val (x, y) = mapper(data.samples[idx])
                    canvas.drawCircle(x, y, radius, paint)
                }
            }
            Mode.SPREAD_AFTER -> {
                val affected = spreadAffected ?: return
                if (affected.size != data.samples.size) return
                val radius = max(18f * density, 32f * scale)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                for (idx in data.samples.indices) {
                    val value = affected[idx]
                    if (value <= 0f) continue
                    val norm = value.coerceIn(0f, 1f)
                    val alpha = (70f + 180f * norm).toInt().coerceIn(0, 255)
                    val color = Color.argb(alpha, 64, (160 + 70 * norm).toInt().coerceIn(0, 255), 255)
                    paint.color = color
                    val (x, y) = mapper(data.samples[idx])
                    canvas.drawCircle(x, y, radius, paint)
                }
            }
            Mode.NONE -> return
        }
    }
}
