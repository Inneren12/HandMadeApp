package com.handmadeapp.ui.importer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.jvm.Volatile

/**
 * Ноу-оп заглушки для отрисовок S7-оверлеев, чтобы сборка не падала,
 * даже если конкретные реализации ещё не подключены.
 *
 * Если в ImportActivity уже есть вызовы наподобие:
 *   S7OverlayRenderer.drawSamplingOverlay(...),
 *   S7OverlayRenderer.drawResidualHeatmap(...),
 *   S7OverlayRenderer.drawIndexPreview(...),
 * эти методы будут найдены и безопасно отработают.
 *
 * Позже можно заменить на реальные реализации.
 */
object S7OverlayRenderer {
    @Volatile
    private var overlaysEnabled: Boolean = true

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        overlaysEnabled = enabled
    }

    @JvmStatic
    fun drawSamplingOverlay(canvas: Canvas?, bitmap: Bitmap?, alpha: Float = 0.45f) {
        if (!overlaysEnabled || canvas == null || bitmap == null) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (alpha * 255).toInt() }
        val dst = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        canvas.drawBitmap(bitmap, null, dst, p)
    }

    @JvmStatic
    fun drawResidualHeatmap(canvas: Canvas?, heatmap: Bitmap?, alpha: Float = 0.6f) {
        if (!overlaysEnabled || canvas == null || heatmap == null) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (alpha * 255).toInt() }
        val dst = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        canvas.drawBitmap(heatmap, null, dst, p)
    }

    @JvmStatic
    fun drawIndexPreview(canvas: Canvas?, preview: Bitmap?) {
        if (!overlaysEnabled || canvas == null || preview == null) return
        val dst = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        canvas.drawBitmap(preview, null, dst, null)
    }

    @JvmStatic
    fun drawSpreadBeforeAfter(
        canvas: Canvas?,
        before: Bitmap?,
        after: Bitmap?,
        showAfter: Boolean = true,
        alpha: Float = 0.45f
    ) {
        val bmp = if (showAfter) after else before
        if (!overlaysEnabled || canvas == null || bmp == null) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (alpha * 255).toInt() }
        val dst = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        canvas.drawBitmap(bmp, null, dst, p)
    }
}

