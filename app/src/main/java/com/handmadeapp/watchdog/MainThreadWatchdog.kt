package com.handmadeapp.watchdog

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.handmadeapp.logging.Logger
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight watchdog that pings the main thread and reports delayed responses.
 */
class MainThreadWatchdog(
    private val intervalMs: Long = 2_000L,
    private val timeoutMs: Long = 1_000L,
    private val throttleMs: Long = 5_000L
) {
    private val metadataProviderRef = AtomicReference<() -> Map<String, Any?>>({ emptyMap() })
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val lastLogAt = AtomicLong(0L)
    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    fun setMetadataProvider(provider: () -> Map<String, Any?>) {
        metadataProviderRef.set(provider)
    }

    fun start() {
        if (running.compareAndSet(false, true)) {
            Logger.i(
                "WATCHDOG",
                "watchdog.enabled",
                mapOf("interval_ms" to intervalMs, "timeout_ms" to timeoutMs)
            )
            lastLogAt.set(0L)
            scheduler = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "watchdog").apply { isDaemon = true }
            }
            scheduleNext()
        }
    }

    fun stop() {
        if (running.getAndSet(false)) {
            Logger.i("WATCHDOG", "watchdog.disabled")
            scheduler?.shutdownNow()
            scheduler = null
        }
    }

    private fun scheduleNext() {
        if (!running.get()) return
        scheduler?.schedule({
            if (!running.get()) return@schedule
            val pingSentAt = SystemClock.elapsedRealtime()
            mainHandler.post {
                val delay = SystemClock.elapsedRealtime() - pingSentAt
                if (delay > timeoutMs) {
                    maybeReportSlowResponse(delay)
                }
            }
            scheduleNext()
        }, intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun maybeReportSlowResponse(delay: Long) {
        val now = SystemClock.elapsedRealtime()
        val last = lastLogAt.get()
        if (last != 0L && now - last < throttleMs) return
        lastLogAt.set(now)

        Debug.dumpStack()
        val stackFrames = Looper.getMainLooper().thread.stackTrace.take(32)
        val stack = stackFrames.map { it.toString() }
        val data = LinkedHashMap<String, Any?>(4)
        data["delay_ms"] = delay
        data["interval_ms"] = intervalMs
        data["timeout_ms"] = timeoutMs
        data["stack"] = stack
        val metadata = metadataProviderRef.get().invoke()
        if (metadata.isNotEmpty()) {
            data["s7"] = metadata
        }
        Logger.w("WATCHDOG", "watchdog.anr_risk", data)
    }
}
