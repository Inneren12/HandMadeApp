package com.appforcross.editor.palette.s7.dither

import com.appforcross.editor.config.FeatureFlags
import com.appforcross.editor.palette.s7.assign.S7AssignCache

object S7DitherEngine {

    private const val WEIGHT_RIGHT = 7
    private const val WEIGHT_DOWN_LEFT = 3
    private const val WEIGHT_DOWN = 5
    private const val WEIGHT_DOWN_RIGHT = 1
    private const val WEIGHT_DENOMINATOR = 16

    fun ditherTile(tileCtx: S7DitherTileContext, assignCache: S7AssignCache) {
        if (!FeatureFlags.S7_DITHER_LINEBUFFERS_ENABLED) {
            ditherTileWithoutLineBuffers(tileCtx, assignCache)
            return
        }
        val width = tileCtx.tileWidth
        val height = tileCtx.tileHeight
        val padding = tileCtx.padding
        val values = tileCtx.values
        val output = tileCtx.output
        val quantizer = tileCtx.quantizer

        val bufferWidth = width + padding * 2
        var currentErrors = IntArray(bufferWidth)
        var nextErrors = IntArray(bufferWidth)

        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val errorIndex = x + padding
                val adjusted = values[index] + currentErrors[errorIndex]
                val quantized = quantizer.quantize(x, y, adjusted, assignCache)
                output[index] = quantized
                var error = adjusted - quantized
                if (error > S7DitherSpec.SCALE) {
                    error = S7DitherSpec.SCALE
                } else if (error < -S7DitherSpec.SCALE) {
                    error = -S7DitherSpec.SCALE
                }
                if (error != 0) {
                    accumulate(currentErrors, errorIndex + 1, error, WEIGHT_RIGHT)
                    accumulate(nextErrors, errorIndex - 1, error, WEIGHT_DOWN_LEFT)
                    accumulate(nextErrors, errorIndex, error, WEIGHT_DOWN)
                    accumulate(nextErrors, errorIndex + 1, error, WEIGHT_DOWN_RIGHT)
                }
                index++
            }
            val tmp = currentErrors
            currentErrors = nextErrors
            nextErrors = tmp
            nextErrors.fill(0)
        }
    }

    private fun ditherTileWithoutLineBuffers(tileCtx: S7DitherTileContext, assignCache: S7AssignCache) {
        val width = tileCtx.tileWidth
        val height = tileCtx.tileHeight
        val padding = tileCtx.padding
        val values = tileCtx.values
        val output = tileCtx.output
        val quantizer = tileCtx.quantizer
        val bufferWidth = width + padding * 2
        val errors = IntArray(bufferWidth * height)
        var index = 0
        for (y in 0 until height) {
            val rowOffset = y * bufferWidth
            val rowStart = rowOffset + padding
            val rowEnd = rowStart + width - 1
            for (x in 0 until width) {
                val errorIndex = rowOffset + padding + x
                val adjusted = values[index] + errors[errorIndex]
                val quantized = quantizer.quantize(x, y, adjusted, assignCache)
                output[index] = quantized
                var error = adjusted - quantized
                if (error > S7DitherSpec.SCALE) {
                    error = S7DitherSpec.SCALE
                } else if (error < -S7DitherSpec.SCALE) {
                    error = -S7DitherSpec.SCALE
                }
                if (error != 0) {
                    if (errorIndex + 1 <= rowEnd) {
                        accumulate(errors, errorIndex + 1, error, WEIGHT_RIGHT)
                    }
                    if (y + 1 < height) {
                        val nextRowOffset = (y + 1) * bufferWidth
                        val downIndex = nextRowOffset + padding + x
                        accumulate(errors, downIndex, error, WEIGHT_DOWN)
                        val downLeftCol = x + padding - 1
                        if (downLeftCol >= 0) {
                            accumulate(errors, nextRowOffset + downLeftCol, error, WEIGHT_DOWN_LEFT)
                        }
                        val downRightCol = x + padding + 1
                        if (downRightCol < bufferWidth) {
                            accumulate(errors, nextRowOffset + downRightCol, error, WEIGHT_DOWN_RIGHT)
                        }
                    }
                }
                index++
            }
        }
    }

    private fun accumulate(buffer: IntArray, index: Int, error: Int, weight: Int) {
        if (index < 0 || index >= buffer.size) return
        val delta = (error * weight) / WEIGHT_DENOMINATOR
        if (delta == 0) return
        val updated = buffer[index] + delta
        buffer[index] = updated.coerceIn(-S7DitherSpec.SCALE, S7DitherSpec.SCALE)
    }

    private fun Int.coerceIn(minValue: Int, maxValue: Int): Int {
        return if (this < minValue) minValue else if (this > maxValue) maxValue else this
    }
}
