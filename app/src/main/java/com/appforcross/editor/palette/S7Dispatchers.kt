package com.appforcross.editor.palette

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Shared coroutine dispatchers for S7 preview and export work.
 *
 * Preview work (ImportActivity live flows, overlays, etc.) is restricted to a single
 * background thread to avoid flooding limited devices. Export pathways are allowed to
 * use a slightly wider pool, but still capped explicitly.
 */
object S7Dispatchers {
    private const val EXPORT_PARALLELISM_DEFAULT = 4

    /**
     * Dispatcher for S7 preview work. Limited to a single thread so only one heavy
     * preview pipeline runs at a time.
     */
    val preview: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Dispatcher for S7 export tasks. Parallelism is still explicitly capped to
     * avoid overloading the device while allowing multiple IO/heavy tasks.
     */
    val export: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(EXPORT_PARALLELISM_DEFAULT)

    /**
     * Returns a dispatcher with the requested export parallelism limit. Falls back
     * to [preview] when only a single worker is desired and reuses [export] for the
     * default width.
     */
    fun export(parallelism: Int): CoroutineDispatcher {
        require(parallelism >= 1) { "Parallelism must be at least 1" }
        return when (parallelism) {
            1 -> preview
            EXPORT_PARALLELISM_DEFAULT -> export
            else -> Dispatchers.Default.limitedParallelism(parallelism)
        }
    }
}
