package com.handmadeapp.ui.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Size
import android.view.View
import com.appforcross.editor.config.FeatureFlags
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

    private enum class Mode { NONE, SAMPLING, RESIDUAL, SPREAD_BEFORE, SPREAD_AFTER, INDEX }

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
    private var indexShowGrid: Boolean = false
    private var indexShowCost: Boolean = false
    private var indexCostBitmap: Bitmap? = null
    private var indexBpp: Int = 8
    private var mode: Mode = Mode.NONE
    private var featureFlagStatuses: List<FeatureFlags.FlagStatus> = emptyList()
    private val flagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.scaledDensity
    }
    private val flagBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

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
        this.indexCostBitmap = null
        this.indexShowGrid = false
        this.indexShowCost = false
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

    fun setIndexOverlay(
        imageSize: Size,
        indexBpp: Int,
        showGrid: Boolean,
        costHeatmap: Bitmap?,
        showCost: Boolean
    ) {
        this.imageSize = imageSize
        this.sampling = null
        this.indexBpp = indexBpp
        this.indexShowGrid = showGrid
        this.indexCostBitmap = costHeatmap
        this.indexShowCost = showCost && costHeatmap != null
        this.mode = Mode.INDEX
        Logger.i(
            "PALETTE",
            "overlay.index.ready",
            mapOf(
                "w" to imageSize.width,
                "h" to imageSize.height,
                "index_bpp" to indexBpp,
                "preview" to true,
                "grid" to showGrid,
                "cost" to (showCost && costHeatmap != null)
            )
        )
        invalidate()
    }

    fun setFeatureFlagStatus(statuses: List<FeatureFlags.FlagStatus>) {
        featureFlagStatuses = statuses
        invalidate()
    }

    fun updateIndexOverlay(showGrid: Boolean, showCost: Boolean) {
        this.indexShowGrid = showGrid
        this.indexShowCost = showCost && indexCostBitmap != null
        if (mode == Mode.INDEX) {
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentMode = mode
        if (currentMode == Mode.NONE) {
            drawFeatureFlagBadges(canvas)
            return
        }
        val img = imageSize ?: run {
            drawFeatureFlagBadges(canvas)
            return
        }

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) {
            drawFeatureFlagBadges(canvas)
            return
        }

        val scale = min(viewW / img.width.toFloat(), viewH / img.height.toFloat())
        val offsetX = (viewW - img.width * scale) / 2f
        val offsetY = (viewH - img.height * scale) / 2f

        val density = resources.displayMetrics.density
        val heatRadius = max(12f * density, 32f * scale)
        val pointRadius = max(2.5f * density, 2f)

        if (currentMode == Mode.INDEX) {
            drawIndexOverlay(canvas, img.width, img.height, offsetX, offsetY, scale)
            drawFeatureFlagBadges(canvas)
            return
        }

        val data = sampling ?: run {
            drawFeatureFlagBadges(canvas)
            return
        }

        val mapper: (S7Sample) -> Pair<Float, Float> = { sample ->
            val x = offsetX + sample.x * scale
            val y = offsetY + sample.y * scale
            x to y
        }

        when (currentMode) {
            Mode.SAMPLING -> {
                if (!showHeat && !showPoints) {
                    drawFeatureFlagBadges(canvas)
                    return
                }
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
                val residual = residualErrors ?: run {
                    drawFeatureFlagBadges(canvas)
                    return
                }
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
                val ambiguity = spreadAmbiguity ?: run {
                    drawFeatureFlagBadges(canvas)
                    return
                }
                if (ambiguity.size != data.samples.size) {
                    drawFeatureFlagBadges(canvas)
                    return
                }
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
                val affected = spreadAffected ?: run {
                    drawFeatureFlagBadges(canvas)
                    return
                }
                if (affected.size != data.samples.size) {
                    drawFeatureFlagBadges(canvas)
                    return
                }
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
            Mode.INDEX -> return
        }
        drawFeatureFlagBadges(canvas)
    }

    private fun drawIndexOverlay(canvas: Canvas, imgW: Int, imgH: Int, offsetX: Float, offsetY: Float, scale: Float) {
        val showCost = indexShowCost && indexCostBitmap != null
        val showGrid = indexShowGrid
        if (!showCost && !showGrid) return
        if (showCost) {
            val bitmap = indexCostBitmap ?: return
            val dest = RectF(offsetX, offsetY, offsetX + imgW * scale, offsetY + imgH * scale)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 180 }
            canvas.drawBitmap(bitmap, null, dest, paint)
        }
        if (showGrid) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(110, 255, 255, 255)
                strokeWidth = max(1f, scale * 0.35f)
            }
            for (x in 0..imgW) {
                val px = offsetX + x * scale
                canvas.drawLine(px, offsetY, px, offsetY + imgH * scale, paint)
            }
            for (y in 0..imgH) {
                val py = offsetY + y * scale
                canvas.drawLine(offsetX, py, offsetX + imgW * scale, py, paint)
            }
        }
    }

    private fun drawFeatureFlagBadges(canvas: Canvas) {
        if (featureFlagStatuses.isEmpty()) return
        val density = resources.displayMetrics.density
        val margin = 8f * density
        val padding = 6f * density
        val radius = 8f * density
        var y = margin
        val textMetrics = flagTextPaint.fontMetrics
        val textHeight = textMetrics.bottom - textMetrics.top
        for (status in featureFlagStatuses) {
            val text = badgeText(status)
            val textWidth = flagTextPaint.measureText(text)
            val boxWidth = textWidth + padding * 2f
            val boxHeight = textHeight + padding * 2f
            flagBackgroundPaint.color = if (status.enabled) {
                Color.argb(170, 34, 139, 34)
            } else {
                Color.argb(170, 139, 34, 34)
            }
            val rect = RectF(margin, y, margin + boxWidth, y + boxHeight)
            canvas.drawRoundRect(rect, radius, radius, flagBackgroundPaint)
            val baseline = rect.top + padding - textMetrics.top
            flagTextPaint.color = Color.WHITE
            canvas.drawText(text, rect.left + padding, baseline, flagTextPaint)
            y += boxHeight + margin * 0.6f
        }
    }

    private fun badgeText(status: FeatureFlags.FlagStatus): String {
        val stage = status.stage.badgeLabel()
        val source = status.source.badgeLabel(status.overrideStage)
        return if (source != null) {
            "${status.flag.displayName}: $stage ($source)"
        } else {
            "${status.flag.displayName}: $stage"
        }
    }

    private fun FeatureFlags.Stage.badgeLabel(): String = when (this) {
        FeatureFlags.Stage.DISABLED -> "off"
        FeatureFlags.Stage.CANARY -> "canary"
        FeatureFlags.Stage.RAMP -> "ramp"
        FeatureFlags.Stage.FULL -> "full"
    }

    private fun FeatureFlags.Source.badgeLabel(overrideStage: FeatureFlags.Stage?): String? = when (this) {
        FeatureFlags.Source.DEFAULT -> "default"
        FeatureFlags.Source.STORED -> "rollout"
        FeatureFlags.Source.OVERRIDE -> overrideStage?.badgeLabel()?.let { "override $it" } ?: "override"
    }
}
