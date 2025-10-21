package com.appforcross.editor.palette.s7.tiles

import kotlin.math.max
import kotlin.math.min

/**
 * Precomputes deterministic tile traversal order for S7 stages.
 *
 * Tiles are generated in row-major order with the requested overlap so that
 * reduceTiles can later replay the same deterministic order when combining
 * per-tile aggregates.
 */
class S7TileScheduler(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val tileWidth: Int,
    private val tileHeight: Int,
    private val overlap: Int
) {

    init {
        require(imageWidth > 0 && imageHeight > 0) { "Image must be non-empty" }
        require(tileWidth > 0 && tileHeight > 0) { "Tile size must be positive" }
        require(overlap >= 0) { "Overlap must be non-negative" }
    }

    data class Tile(
        val tileId: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    private val tilesInternal: List<Tile> = buildTiles()

    val tiles: List<Tile>
        get() = tilesInternal

    val tileCount: Int
        get() = tilesInternal.size

    private fun buildTiles(): List<Tile> {
        val xStarts = computeStarts(imageWidth, tileWidth, overlap)
        val yStarts = computeStarts(imageHeight, tileHeight, overlap)
        val tiles = ArrayList<Tile>(xStarts.size * yStarts.size)
        var tileId = 0
        for (y in yStarts) {
            val h = min(tileHeight, imageHeight - y)
            for (x in xStarts) {
                val w = min(tileWidth, imageWidth - x)
                tiles += Tile(tileId++, x, y, w, h)
            }
        }
        return tiles
    }

    private fun computeStarts(size: Int, tile: Int, overlap: Int): IntArray {
        if (tile >= size) return intArrayOf(0)
        val step = max(1, tile - overlap)
        val maxStart = size - tile
        val positions = ArrayList<Int>()
        var start = 0
        while (true) {
            positions += start
            if (start >= maxStart) break
            var next = start + step
            if (next > maxStart) {
                next = maxStart
            }
            if (next == start) {
                // Ensure forward progress even if parameters degenerate.
                next = min(maxStart, start + 1)
                if (next == start) break
            }
            start = next
        }
        return positions.toIntArray()
    }

    fun forEachTile(action: (Tile) -> Unit) {
        tilesInternal.forEach(action)
    }

    fun reduceTiles(reducer: (Tile) -> Unit) {
        tilesInternal.forEach(reducer)
    }
}
