package com.appforcross.editor.palette.s7.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class S7TileSchedulerTest {

    @Test
    fun deterministicReduceProducesStableIndex() {
        val width = 37
        val height = 29
        val scheduler = S7TileScheduler(
            imageWidth = width,
            imageHeight = height,
            tileWidth = 9,
            tileHeight = 7,
            overlap = 1
        )

        fun runSimulation(seed: Int): ByteArray {
            val random = Random(seed)
            val tileBuffers = HashMap<Int, ByteArray>()
            val shuffledTiles = scheduler.tiles.shuffled(random)
            shuffledTiles.forEach { tile ->
                val buffer = ByteArray(tile.width * tile.height) { (tile.tileId and 0xFF).toByte() }
                tileBuffers[tile.tileId] = buffer
            }
            val dest = ByteArray(width * height)
            scheduler.reduceTiles { tile ->
                val buffer = tileBuffers.getValue(tile.tileId)
                writeTile(buffer, tile, dest, width)
            }
            return dest
        }

        val first = runSimulation(seed = 1)
        val second = runSimulation(seed = 2)
        assertArrayEquals(first, second)
    }

    private fun writeTile(source: ByteArray, tile: S7TileScheduler.Tile, dest: ByteArray, imageWidth: Int) {
        var srcPos = 0
        for (row in 0 until tile.height) {
            val destIndex = (tile.y + row) * imageWidth + tile.x
            source.copyInto(dest, destIndex, srcPos, srcPos + tile.width)
            srcPos += tile.width
        }
    }
}
