package com.handmadeapp.ui.importer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

fun createResidualBitmap(base: Bitmap, residual: Bitmap, alpha: Float = 0.6f): Bitmap {
    // UI-рендер в половинном размере — ощутимо быстрее и почти не отличим визуально
    val w = (base.width * 0.5f).toInt().coerceAtLeast(1)
    val h = (base.height * 0.5f).toInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val baseScaled = Bitmap.createScaledBitmap(base, w, h, true)
    val residualScaled = Bitmap.createScaledBitmap(residual, w, h, true)
    val canvas = Canvas(out)
    val dst = RectF(0f, 0f, w.toFloat(), h.toFloat())
    canvas.drawBitmap(baseScaled, null, dst, null)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = (alpha * 255).toInt() }
    canvas.drawBitmap(residualScaled, null, dst, paint)
    if (baseScaled !== base) {
        baseScaled.recycle()
    }
    if (residualScaled !== residual) {
        residualScaled.recycle()
    }
    return out
}
