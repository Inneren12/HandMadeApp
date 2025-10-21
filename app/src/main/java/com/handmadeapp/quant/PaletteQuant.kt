package com.handmadeapp.quant

import com.appforcross.editor.config.FeatureFlags
import com.handmadeapp.logging.Logger
import java.io.Closeable
import java.util.ArrayDeque
import java.util.IdentityHashMap
import kotlin.math.max

/**
 * Buffer pool for palette quantization stages (assignment, incremental updates).
 * Arrays sized by the preview sample count / tile count are expensive to allocate
 * inside hot paths (assign recompute, spread2opt copies). This pool reuses them
 * when {@link FeatureFlags#S7_BUFFER_POOL_ENABLED} is enabled.
 */
object PaletteQuantBuffers {

    private const val METRIC = "s7.alloc_bytes_hotpath"
    private const val STAGE = "assign"

    private val intArrays = HashMap<Int, ArrayDeque<IntArray>>()
    private val floatArrays = HashMap<Int, ArrayDeque<FloatArray>>()
    private val doubleArrays = HashMap<Int, ArrayDeque<DoubleArray>>()
    private val booleanArrays = HashMap<Int, ArrayDeque<BooleanArray>>()

    private val inUse = IdentityHashMap<Workspace, Boolean>()

    @Synchronized
    fun acquire(sampleCount: Int, paletteSize: Int, tileCount: Int): Workspace {
        require(sampleCount >= 0) { "sampleCount must be non-negative" }
        require(paletteSize >= 0) { "paletteSize must be non-negative" }
        require(tileCount >= 0) { "tileCount must be non-negative" }
        val pooling = FeatureFlags.S7_BUFFER_POOL_ENABLED
        val tileIndices = obtainIntArray(sampleCount, "tile_indices", pooling)
        val owners = obtainIntArray(sampleCount, "owners", pooling)
        val secondOwners = obtainIntArray(sampleCount, "second_owners", pooling)
        val d2min = obtainFloatArray(sampleCount, "d2min", pooling)
        val d2second = obtainFloatArray(sampleCount, "d2second", pooling)
        val errorPerTile = obtainFloatArray(tileCount, "error_tile", pooling)
        val weights = obtainFloatArray(sampleCount, "weights", pooling)
        val riskWeights = obtainFloatArray(sampleCount, "risk_weights", pooling)
        val perColorImportance = obtainDoubleArray(paletteSize, "color_importance", pooling)
        val invalidTiles = obtainBooleanArray(tileCount, "invalid_tiles", pooling)
        val counts = obtainIntArray(tileCount, "tile_counts", pooling)
        val offsets = obtainIntArray(tileCount, "tile_offsets", pooling)
        val workspace = Workspace(
            tileIndices = tileIndices,
            owners = owners,
            secondOwners = secondOwners,
            d2min = d2min,
            d2second = d2second,
            errorPerTile = errorPerTile,
            weights = weights,
            riskWeights = riskWeights,
            perColorImportance = perColorImportance,
            invalidTiles = invalidTiles,
            scratchCounts = counts,
            scratchOffsets = offsets,
            pooling = pooling
        )
        if (pooling) {
            inUse[workspace] = true
        }
        return workspace
    }

    @Synchronized
    private fun release(workspace: Workspace) {
        if (!workspace.pooling) return
        if (inUse.remove(workspace) == null) return
        recycleIntArray(workspace.tileIndices)
        recycleIntArray(workspace.owners)
        recycleIntArray(workspace.secondOwners)
        recycleFloatArray(workspace.d2min)
        recycleFloatArray(workspace.d2second)
        recycleFloatArray(workspace.errorPerTile)
        recycleFloatArray(workspace.weights)
        recycleFloatArray(workspace.riskWeights)
        recycleDoubleArray(workspace.perColorImportance)
        recycleBooleanArray(workspace.invalidTiles)
        workspace.scratchCounts?.let { recycleIntArray(it) }
        workspace.scratchOffsets?.let { recycleIntArray(it) }
        workspace.clearScratch()
    }

    private fun obtainIntArray(length: Int, label: String, pooling: Boolean): IntArray {
        if (length <= 0) return IntArray(0)
        if (!pooling) {
            logHotAllocation(label, length.toLong() * Int.SIZE_BYTES, "alloc", false)
            return IntArray(length)
        }
        val queue = intArrays.getOrPut(length) { ArrayDeque() }
        val array = queue.pollFirst()
        if (array != null) {
            return array
        }
        logHotAllocation(label, length.toLong() * Int.SIZE_BYTES, "pool_miss", true)
        return IntArray(length)
    }

    private fun obtainFloatArray(length: Int, label: String, pooling: Boolean): FloatArray {
        if (length <= 0) return FloatArray(0)
        if (!pooling) {
            logHotAllocation(label, length.toLong() * Float.SIZE_BYTES, "alloc", false)
            return FloatArray(length)
        }
        val queue = floatArrays.getOrPut(length) { ArrayDeque() }
        val array = queue.pollFirst()
        if (array != null) {
            return array
        }
        logHotAllocation(label, length.toLong() * Float.SIZE_BYTES, "pool_miss", true)
        return FloatArray(length)
    }

    private fun obtainDoubleArray(length: Int, label: String, pooling: Boolean): DoubleArray {
        if (length <= 0) return DoubleArray(0)
        if (!pooling) {
            logHotAllocation(label, length.toLong() * Double.SIZE_BYTES, "alloc", false)
            return DoubleArray(length)
        }
        val queue = doubleArrays.getOrPut(length) { ArrayDeque() }
        val array = queue.pollFirst()
        if (array != null) {
            return array
        }
        logHotAllocation(label, length.toLong() * Double.SIZE_BYTES, "pool_miss", true)
        return DoubleArray(length)
    }

    private fun obtainBooleanArray(length: Int, label: String, pooling: Boolean): BooleanArray {
        if (length <= 0) return BooleanArray(0)
        if (!pooling) {
            logHotAllocation(label, length.toLong(), "alloc", false)
            return BooleanArray(length)
        }
        val queue = booleanArrays.getOrPut(length) { ArrayDeque() }
        val array = queue.pollFirst()
        if (array != null) {
            return array
        }
        logHotAllocation(label, length.toLong(), "pool_miss", true)
        return BooleanArray(length)
    }

    private fun recycleIntArray(array: IntArray) {
        if (array.isEmpty()) return
        intArrays.getOrPut(array.size) { ArrayDeque() }.addLast(array)
    }

    private fun recycleFloatArray(array: FloatArray) {
        if (array.isEmpty()) return
        floatArrays.getOrPut(array.size) { ArrayDeque() }.addLast(array)
    }

    private fun recycleDoubleArray(array: DoubleArray) {
        if (array.isEmpty()) return
        doubleArrays.getOrPut(array.size) { ArrayDeque() }.addLast(array)
    }

    private fun recycleBooleanArray(array: BooleanArray) {
        if (array.isEmpty()) return
        booleanArrays.getOrPut(array.size) { ArrayDeque() }.addLast(array)
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

    class Workspace internal constructor(
        val tileIndices: IntArray,
        val owners: IntArray,
        val secondOwners: IntArray,
        val d2min: FloatArray,
        val d2second: FloatArray,
        val errorPerTile: FloatArray,
        val weights: FloatArray,
        val riskWeights: FloatArray,
        val perColorImportance: DoubleArray,
        val invalidTiles: BooleanArray,
        internal var scratchCounts: IntArray?,
        internal var scratchOffsets: IntArray?,
        internal val pooling: Boolean
    ) : Closeable {
        @Volatile
        private var closed = false

        internal fun clearScratch() {
            scratchCounts = null
            scratchOffsets = null
        }

        override fun close() {
            if (closed) return
            closed = true
            release(this)
        }
    }
}
