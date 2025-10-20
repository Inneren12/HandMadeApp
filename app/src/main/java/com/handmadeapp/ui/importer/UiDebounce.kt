package com.handmadeapp.ui.importer

import android.os.Handler
import android.os.Looper

/**
 * Простой дебаунсер для UI-событий (напр., перетаскивание SeekBar).
 * По умолчанию откладывает выполнение на 90мс, чтобы не дёргать тяжёлый рендер на каждый тик.
 */
class Debouncer(private val delayMs: Long = 90L) {
    private val h = Handler(Looper.getMainLooper())
    private var r: Runnable? = null

    fun submit(block: () -> Unit) {
        r?.let { h.removeCallbacks(it) }
        val run = Runnable { block() }
        r = run
        h.postDelayed(run, delayMs)
    }
}
