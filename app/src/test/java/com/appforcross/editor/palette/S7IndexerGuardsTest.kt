package com.appforcross.editor.palette

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class S7IndexerGuardsTest {

    @Test
    fun `preview mode rejects oversize bitmap`() {
        val limit = S7Indexer.MAX_PREVIEW_PIXELS
        val width = 2000
        val height = ((limit / width.toLong()) + 1).toInt()

        val error = assertFailsWith<IllegalArgumentException> {
            S7Indexer.ensurePreviewBounds(
                mode = S7Indexer.Mode.PREVIEW,
                srcWidth = width,
                srcHeight = height,
                previewWidth = width,
                previewHeight = height
            )
        }
        assertTrue(error.message?.contains("downscale required") == true)
    }

    @Test
    fun `export mode allows oversize bitmap`() {
        val limit = S7Indexer.MAX_PREVIEW_PIXELS
        val width = 2000
        val height = ((limit / width.toLong()) + 1).toInt()

        S7Indexer.ensurePreviewBounds(
            mode = S7Indexer.Mode.EXPORT,
            srcWidth = width,
            srcHeight = height,
            previewWidth = width,
            previewHeight = height
        )
    }
}
