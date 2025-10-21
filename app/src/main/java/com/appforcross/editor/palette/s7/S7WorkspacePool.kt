package com.appforcross.editor.palette.s7

import com.appforcross.editor.config.FeatureFlags
import com.handmadeapp.quant.LosProfiler
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * Workspace pool for heavy-weight allocations used across S7 stages.
 * The pool keeps byte buffers and primitive arrays sized for the
 * current workload (width * height for pixel planes and k for palette slots).
 */
object S7WorkspacePool {

    private const val MASK_COUNT = 6
    private const val STAGE = "workspace"

    private val byteBuffers = HashMap<Int, ArrayDeque<ByteBuffer>>()
    private val doubleArrays = HashMap<Int, ArrayDeque<DoubleArray>>()
    private val floatArrays = HashMap<Int, ArrayDeque<FloatArray>>()
    private val intArrays = HashMap<Int, ArrayDeque<IntArray>>()
    private val byteArrays = HashMap<Int, ArrayDeque<ByteArray>>()

    private val inUse = IdentityHashMap<Workspace, Boolean>()

    fun acquire(width: Int, height: Int, k: Int, bytesPerPixel: Int): Workspace {
        require(width >= 0 && height >= 0) { "width/height must be non-negative" }
        require(k >= 0) { "k must be non-negative" }
        require(bytesPerPixel > 0) { "bytesPerPixel must be positive" }
        val total = width * height
        val poolingEnabled = FeatureFlags.S7_BUFFER_POOL_ENABLED
        val indexBuffer = obtainByteBuffer(total * bytesPerPixel, poolingEnabled)
        val planes = Array(5) {
            obtainDoubleArray(total, "double_plane_$it", poolingEnabled)
        }
        val paletteLab = obtainDoubleArray(k * 3, "palette_lab", poolingEnabled)
        val paletteHue = obtainDoubleArray(k, "palette_hue", poolingEnabled)
        val masks = Array(MASK_COUNT) {
            obtainFloatArray(total, "mask_$it", poolingEnabled)
        }
        val previewPixels = obtainIntArray(total, "preview_pixels", poolingEnabled)
        val assigned = obtainIntArray(total, "assigned", poolingEnabled)
        val counts = obtainIntArray(k, "counts", poolingEnabled)
        val paletteRoles = obtainByteArray(k, "palette_roles", poolingEnabled)
        val paletteColors = obtainIntArray(k, "palette_colors", poolingEnabled)
        val zones = obtainByteArray(total, "zones", poolingEnabled)
        val row = obtainIntArray(width, "row_buffer", poolingEnabled)
        val workspace = Workspace(
            total = total,
            width = width,
            height = height,
            k = k,
            bytesPerPixel = bytesPerPixel,
            indexBuffer = indexBuffer,
            doublePlanes = planes,
            paletteLab = paletteLab,
            paletteHue = paletteHue,
            floatMasks = masks,
            previewPixels = previewPixels,
            assigned = assigned,
            counts = counts,
            paletteRoles = paletteRoles,
            paletteColors = paletteColors,
            zones = zones,
            row = row,
            pooling = poolingEnabled
        )
        if (poolingEnabled) {
            inUse[workspace] = true
        }
        return workspace
    }

    internal fun release(workspace: Workspace) {
        if (inUse.remove(workspace) == null) return
        recycleByteBuffer(workspace.indexBuffer)
        workspace.doublePlanes.forEach { recycleDoubleArray(it) }
        recycleDoubleArray(workspace.paletteLab)
        recycleDoubleArray(workspace.paletteHue)
        workspace.floatMasks.forEach { recycleFloatArray(it) }
        recycleIntArray(workspace.previewPixels)
        recycleIntArray(workspace.assigned)
        recycleIntArray(workspace.counts)
        recycleByteArray(workspace.paletteRoles)
        recycleIntArray(workspace.paletteColors)
        recycleByteArray(workspace.zones)
        recycleIntArray(workspace.rowBuffer)
    }

    private fun obtainByteBuffer(capacity: Int, pooling: Boolean): ByteBuffer {
        if (capacity <= 0) return ByteBuffer.allocate(0)
        if (!pooling) {
            LosProfiler.record(STAGE, "index_bytes", capacity.toLong())
            return ByteBuffer.allocate(capacity)
        }
        val queue = byteBuffers.getOrPut(capacity) { ArrayDeque() }
        val buffer = queue.pollFirst()
        if (buffer != null) {
            buffer.clear()
            return buffer
        }
        LosProfiler.record(STAGE, "index_bytes", capacity.toLong())
        return ByteBuffer.allocate(capacity).apply { clear() }
    }

    private fun recycleByteBuffer(buffer: ByteBuffer) {
        val capacity = buffer.capacity()
        if (capacity <= 0) return
        buffer.clear()
        byteBuffers.getOrPut(capacity) { ArrayDeque() }.addLast(buffer)
    }

    private fun obtainDoubleArray(length: Int, label: String, pooling: Boolean): DoubleArray {
        if (length <= 0) return DoubleArray(0)
        if (!pooling) {
            LosProfiler.record(STAGE, label, length.toLong() * Double.SIZE_BYTES)
            return DoubleArray(length)
        }
        val queue = doubleArrays.getOrPut(length) { ArrayDeque() }
        val array = queue.pollFirst()
        if (array != null) {
            return array
        }
        LosProfiler.record(STAGE, label, length.toLong() * Double.SIZE_BYTES)
        return DoubleArray(length)
    }

    private fun recycleDoubleArray(array: DoubleArray) {
        val length = array.size
        if (length <= 0) return
        doubleArrays.getOrPut(length) { ArrayDeque() }.addLast(array)
    }

    private fun obtainFloatArray(length: Int, label: String, pooling: Boolean): FloatArray {
        if (length <= 0) return FloatArray(0)
        if (!pooling) {
            LosProfiler.record(STAGE, label, length.toLong() * Float.SIZE_BYTES)
            return FloatArray(length)
        }
        val queue = floatArrays.getOrPut(length) { ArrayDeque() }
        val array = queue.pollFirst()
        if (array != null) {
            return array
        }
        LosProfiler.record(STAGE, label, length.toLong() * Float.SIZE_BYTES)
        return FloatArray(length)
    }

    private fun recycleFloatArray(array: FloatArray) {
        val length = array.size
        if (length <= 0) return
        floatArrays.getOrPut(length) { ArrayDeque() }.addLast(array)
    }

    private fun obtainIntArray(length: Int, label: String, pooling: Boolean): IntArray {
        if (length <= 0) return IntArray(0)
        if (!pooling) {
            LosProfiler.record(STAGE, label, length.toLong() * Int.SIZE_BYTES)
            return IntArray(length)
        }
        val queue = intArrays.getOrPut(length) { ArrayDeque() }
        val array = queue.pollFirst()
        if (array != null) {
            return array
        }
        LosProfiler.record(STAGE, label, length.toLong() * Int.SIZE_BYTES)
        return IntArray(length)
    }

    private fun recycleIntArray(array: IntArray) {
        if (array.isEmpty()) return
        intArrays.getOrPut(array.size) { ArrayDeque() }.addLast(array)
    }

    private fun obtainByteArray(length: Int, label: String, pooling: Boolean): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (!pooling) {
            LosProfiler.record(STAGE, label, length.toLong())
            return ByteArray(length)
        }
        val queue = byteArrays.getOrPut(length) { ArrayDeque() }
        val array = queue.pollFirst()
        if (array != null) {
            return array
        }
        LosProfiler.record(STAGE, label, length.toLong())
        return ByteArray(length)
    }

    private fun recycleByteArray(array: ByteArray) {
        if (array.isEmpty()) return
        byteArrays.getOrPut(array.size) { ArrayDeque() }.addLast(array)
    }

    class Workspace internal constructor(
        val total: Int,
        val width: Int,
        val height: Int,
        val k: Int,
        val bytesPerPixel: Int,
        val indexBuffer: ByteBuffer,
        internal val doublePlanes: Array<DoubleArray>,
        internal val paletteLab: DoubleArray,
        internal val paletteHue: DoubleArray,
        internal val floatMasks: Array<FloatArray>,
        internal val previewPixels: IntArray,
        internal val assigned: IntArray,
        internal val counts: IntArray,
        internal val paletteRoles: ByteArray,
        internal val paletteColors: IntArray,
        internal val zones: ByteArray,
        internal val row: IntArray,
        internal val pooling: Boolean
    ) : Closeable {
        val lPlane: DoubleArray get() = doublePlanes[0]
        val aPlane: DoubleArray get() = doublePlanes[1]
        val bPlane: DoubleArray get() = doublePlanes[2]
        val huePlane: DoubleArray get() = doublePlanes[3]
        val costPlane: DoubleArray get() = doublePlanes[4]
        val paletteLabData: DoubleArray get() = paletteLab
        val paletteHueData: DoubleArray get() = paletteHue
        val masks: Array<FloatArray> get() = floatMasks
        val indexBytes: ByteArray get() = indexBuffer.array()
        val doublePlaneCount: Int get() = doublePlanes.size
        val floatPlaneCount: Int get() = floatMasks.size
        val intPlaneCount: Int get() = 5
        val bytePlaneCount: Int get() = 2
        val preview: IntArray get() = previewPixels
        val assignedOwners: IntArray get() = assigned
        val countsBuffer: IntArray get() = counts
        val paletteRolesBuffer: ByteArray get() = paletteRoles
        val paletteColorsBuffer: IntArray get() = paletteColors
        val zonesBuffer: ByteArray get() = zones
        val rowBuffer: IntArray get() = row
        val poolingEnabled: Boolean get() = pooling

        override fun close() {
            release(this)
        }
    }
}
