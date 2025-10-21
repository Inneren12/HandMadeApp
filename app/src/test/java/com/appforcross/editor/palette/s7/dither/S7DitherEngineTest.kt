package com.appforcross.editor.palette.s7.dither

import com.appforcross.editor.palette.S7Sample
import com.appforcross.editor.palette.S7SamplingSpec
import com.appforcross.editor.palette.s7.assign.S7AssignCache
import kotlin.test.Test
import kotlin.test.assertContentEquals

class S7DitherEngineTest {

    @Test
    fun `ditherTile produces deterministic quantization`() {
        val tileWidth = 3
        val tileHeight = 3
        val padding = 1
        val values = intArrayOf(
            64, 128, 192,
            32, 160, 224,
            96, 144, 200
        )
        val output = IntArray(values.size)
        val quantizer = S7DitherQuantizer { _, _, value, _ ->
            if (value >= S7DitherSpec.SCALE / 2) S7DitherSpec.SCALE else 0
        }
        val tileCtx = S7DitherTileContext(tileWidth, tileHeight, padding, values, output, quantizer)
        val cache = createDummyAssignCache()

        S7DitherEngine.ditherTile(tileCtx, cache)

        val expected = intArrayOf(
            0, 256, 256,
            0, 0, 256,
            256, 0, 256
        )
        assertContentEquals(expected, output)
    }

    @Test
    fun `ditherTile clips excessive error propagation`() {
        val tileWidth = 3
        val tileHeight = 1
        val padding = 1
        val values = intArrayOf(1024, 0, 0)
        val output = IntArray(values.size)
        val quantizer = S7DitherQuantizer { _, _, value, _ ->
            if (value >= S7DitherSpec.SCALE / 2) S7DitherSpec.SCALE else 0
        }
        val tileCtx = S7DitherTileContext(tileWidth, tileHeight, padding, values, output, quantizer)
        val cache = createDummyAssignCache()

        S7DitherEngine.ditherTile(tileCtx, cache)

        val expected = intArrayOf(256, 0, 0)
        assertContentEquals(expected, output)
    }

    private fun createDummyAssignCache(): S7AssignCache {
        val sample = S7Sample(
            x = 0,
            y = 0,
            oklab = floatArrayOf(0f, 0f, 0f),
            zone = S7SamplingSpec.Zone.FLAT,
            E = 0f,
            R = 0f,
            N = 0f,
            w = 1f
        )
        return S7AssignCache.create(
            samples = listOf(sample),
            paletteLabs = listOf(floatArrayOf(0f, 0f, 0f)),
            tileWidth = 1,
            tileHeight = 1,
            threshold = 1f
        )
    }
}
