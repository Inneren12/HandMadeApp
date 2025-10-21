package com.handmadeapp.quant

import com.handmadeapp.logging.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Collects counters for large-object-space (LOS) allocations performed during the S7 pipeline.
 * The profiler records allocations above a configurable threshold and exposes a snapshot that
 * can be emitted to logs once a pipeline stage finishes. The information is used to correlate
 * LOS pressure with profiler traces without having to instrument every allocation site manually.
 */
object LosProfiler {

    private const val METRIC = "s7.los.alloc"
    private const val DELIMITER = "::"
    private const val THRESHOLD_BYTES = 256 * 1024 // 256 KiB

    private data class Counter(
        val count: AtomicLong = AtomicLong(0),
        val bytes: AtomicLong = AtomicLong(0)
    )

    private val counters = ConcurrentHashMap<String, Counter>()

    /**
     * Record a LOS allocation for the given [stage] and [buffer] names. Only allocations above the
     * threshold are tracked to keep noise low. Thread-safe.
     */
    fun record(stage: String, buffer: String, bytes: Long) {
        val normalized = max(0L, bytes)
        if (normalized < THRESHOLD_BYTES) return
        val key = buildKey(stage, buffer)
        val counter = counters.computeIfAbsent(key) { Counter() }
        counter.count.incrementAndGet()
        counter.bytes.addAndGet(normalized)
    }

    /**
     * Capture the current counters and reset them. The snapshot is emitted to logcat to make LOS
     * pressure visible in traces.
     */
    fun snapshotAndReset(): List<Map<String, Any>> {
        if (counters.isEmpty()) return emptyList()
        val snapshot = mutableListOf<Map<String, Any>>()
        val entries = counters.entries.toTypedArray()
        counters.clear()
        for ((key, counter) in entries) {
            val (stage, buffer) = splitKey(key)
            snapshot += mapOf(
                "stage" to stage,
                "buffer" to buffer,
                "count" to counter.count.get(),
                "bytes" to counter.bytes.get()
            )
        }
        if (snapshot.isNotEmpty()) {
            Logger.i("PALETTE", METRIC, mapOf("snapshot" to snapshot))
        }
        return snapshot
    }

    private fun buildKey(stage: String, buffer: String): String {
        return stage + DELIMITER + buffer
    }

    private fun splitKey(key: String): Pair<String, String> {
        val delimiterIndex = key.indexOf(DELIMITER)
        if (delimiterIndex <= 0 || delimiterIndex >= key.length - DELIMITER.length) {
            return key to ""
        }
        val stage = key.substring(0, delimiterIndex)
        val buffer = key.substring(delimiterIndex + DELIMITER.length)
        return stage to buffer
    }
}

