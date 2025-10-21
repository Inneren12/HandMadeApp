package com.appforcross.editor.palette.s7

import com.appforcross.editor.config.FeatureFlags
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

    private val byteBuffers = HashMap<Int, ArrayDeque<ByteBuffer>>()
    private val doubleArrays = HashMap<Int, ArrayDeque<DoubleArray>>()
    private val floatArrays = HashMap<Int, ArrayDeque<FloatArray>>()

    private val inUse = IdentityHashMap<Workspace, Boolean>()

    fun acquire(total: Int, k: Int, bytesPerPixel: Int): Workspace {
        require(total >= 0) { "total must be non-negative" }
        require(k >= 0) { "k must be non-negative" }
        require(bytesPerPixel > 0) { "bytesPerPixel must be positive" }
        val poolingEnabled = FeatureFlags.S7_BUFFER_POOL_ENABLED
        val indexBuffer = if (poolingEnabled) {
            obtainByteBuffer(total * bytesPerPixel)
        } else {
            ByteBuffer.allocate((total * bytesPerPixel).coerceAtLeast(0))
        }
        val planes = Array(5) {
            if (poolingEnabled) obtainDoubleArray(total) else DoubleArray(total)
        }
        val paletteLab = if (poolingEnabled) obtainDoubleArray(k * 3) else DoubleArray(k * 3)
        val paletteHue = if (poolingEnabled) obtainDoubleArray(k) else DoubleArray(k)
        val masks = Array(MASK_COUNT) {
            if (poolingEnabled) obtainFloatArray(total) else FloatArray(total)
        }
        val workspace = Workspace(
            total = total,
            k = k,
            bytesPerPixel = bytesPerPixel,
            indexBuffer = indexBuffer,
            doublePlanes = planes,
            paletteLab = paletteLab,
            paletteHue = paletteHue,
            floatMasks = masks
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
    }

    private fun obtainByteBuffer(capacity: Int): ByteBuffer {
        if (capacity <= 0) return ByteBuffer.allocate(0)
        val queue = byteBuffers.getOrPut(capacity) { ArrayDeque() }
        val buffer = queue.pollFirst() ?: ByteBuffer.allocate(capacity)
        buffer.clear()
        return buffer
    }

    private fun recycleByteBuffer(buffer: ByteBuffer) {
        val capacity = buffer.capacity()
        if (capacity <= 0) return
        buffer.clear()
        byteBuffers.getOrPut(capacity) { ArrayDeque() }.addLast(buffer)
    }

    private fun obtainDoubleArray(length: Int): DoubleArray {
        if (length <= 0) return DoubleArray(0)
        val queue = doubleArrays.getOrPut(length) { ArrayDeque() }
        return queue.pollFirst() ?: DoubleArray(length)
    }

    private fun recycleDoubleArray(array: DoubleArray) {
        val length = array.size
        if (length <= 0) return
        doubleArrays.getOrPut(length) { ArrayDeque() }.addLast(array)
    }

    private fun obtainFloatArray(length: Int): FloatArray {
        if (length <= 0) return FloatArray(0)
        val queue = floatArrays.getOrPut(length) { ArrayDeque() }
        return queue.pollFirst() ?: FloatArray(length)
    }

    private fun recycleFloatArray(array: FloatArray) {
        val length = array.size
        if (length <= 0) return
        floatArrays.getOrPut(length) { ArrayDeque() }.addLast(array)
    }

    class Workspace internal constructor(
        val total: Int,
        val k: Int,
        val bytesPerPixel: Int,
        val indexBuffer: ByteBuffer,
        internal val doublePlanes: Array<DoubleArray>,
        internal val paletteLab: DoubleArray,
        internal val paletteHue: DoubleArray,
        internal val floatMasks: Array<FloatArray>
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

        override fun close() {
            release(this)
        }
    }
}
