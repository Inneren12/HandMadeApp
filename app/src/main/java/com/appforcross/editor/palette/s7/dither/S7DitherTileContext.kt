package com.appforcross.editor.palette.s7.dither

import com.appforcross.editor.palette.s7.assign.S7AssignCache

fun interface S7DitherQuantizer {
    fun quantize(x: Int, y: Int, value: Int, assignCache: S7AssignCache): Int
}

class S7DitherTileContext(
    val tileWidth: Int,
    val tileHeight: Int,
    val padding: Int,
    val values: IntArray,
    val output: IntArray,
    val quantizer: S7DitherQuantizer
) {
    init {
        require(tileWidth > 0) { "tileWidth must be positive" }
        require(tileHeight > 0) { "tileHeight must be positive" }
        require(padding >= 0) { "padding must be non-negative" }
        require(values.size == tileWidth * tileHeight) {
            "values length must match tile dimensions"
        }
        require(output.size == values.size) { "output length must match tile dimensions" }
    }

    fun indexOf(x: Int, y: Int): Int {
        require(x in 0 until tileWidth) { "x out of bounds" }
        require(y in 0 until tileHeight) { "y out of bounds" }
        return y * tileWidth + x
    }
}
