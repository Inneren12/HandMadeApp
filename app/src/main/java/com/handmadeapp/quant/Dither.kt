package com.handmadeapp.quant

import com.appforcross.editor.config.FeatureFlags
import com.handmadeapp.logging.Logger
import java.io.Closeable
import java.util.ArrayDeque
import java.util.IdentityHashMap
import kotlin.math.max

/**
 * Buffer pool for S7 dithering stages. Provides reusable line buffers and
 * planar error buffers depending on the configuration (line buffers enabled or not).
 */
object DitherBuffers {

    private const val METRIC = "s7.alloc_bytes_hotpath"
    private const val STAGE = "dither"

    private val intArrays = HashMap<Int, ArrayDeque<IntArray>>()
    private val lineInUse = IdentityHashMap<LineWorkspace, Boolean>()
    private val planeInUse = IdentityHashMap<PlaneWorkspace, Boolean>()

    fun acquireLineWorkspace(length: Int): LineWorkspace {
        val pooling = FeatureFlags.S7_BUFFER_POOL_ENABLED
        val current = obtainIntArray(length, "line_current", pooling)
        val next = obtainIntArray(length, "line_next", pooling)
        val workspace = LineWorkspace(current, next, pooling)
        if (pooling) {
            lineInUse[workspace] = true
        }
        return workspace
    }

    fun acquirePlaneWorkspace(length: Int): PlaneWorkspace {
        val pooling = FeatureFlags.S7_BUFFER_POOL_ENABLED
        val plane = obtainIntArray(length, "error_plane", pooling)
        val workspace = PlaneWorkspace(plane, pooling)
        if (pooling) {
            planeInUse[workspace] = true
        }
        return workspace
    }

    @Synchronized
    private fun obtainIntArray(length: Int, label: String, pooling: Boolean): IntArray {
        if (length <= 0) return IntArray(0)
        if (!pooling) {
            logHotAllocation(label, length.toLong() * Int.SIZE_BYTES, "alloc", false)
            return IntArray(length)
        }
        val queue = intArrays.getOrPut(length) { ArrayDeque() }
        val existing = queue.pollFirst()
        if (existing != null) {
            return existing
        }
        logHotAllocation(label, length.toLong() * Int.SIZE_BYTES, "pool_miss", true)
        return IntArray(length)
    }

    @Synchronized
    private fun recycle(array: IntArray) {
        if (array.isEmpty()) return
        intArrays.getOrPut(array.size) { ArrayDeque() }.addLast(array)
    }

    private fun logHotAllocation(buffer: String, bytes: Long, reason: String, pooling: Boolean) {
        LosProfiler.record(STAGE, buffer, bytes)
        Logger.i(
            "PALETTE",
            METRIC,
            mapOf(
                "stage" to STAGE,
                "buffer" to buffer,
                "bytes" to max(0L, bytes),
                "reason" to reason,
                "pooling" to pooling
            )
        )
    }

    class LineWorkspace internal constructor(
        val current: IntArray,
        val next: IntArray,
        private val pooling: Boolean
    ) : Closeable {
        @Volatile
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            if (!pooling) return
            synchronized(DitherBuffers) {
                if (lineInUse.remove(this) != null) {
                    recycle(current)
                    recycle(next)
                }
            }
        }
    }

    class PlaneWorkspace internal constructor(
        val errors: IntArray,
        private val pooling: Boolean
    ) : Closeable {
        @Volatile
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            if (!pooling) return
            synchronized(DitherBuffers) {
                if (planeInUse.remove(this) != null) {
                    recycle(errors)
                }
            }
        }
    }
}
