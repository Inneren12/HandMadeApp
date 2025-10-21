package com.appforcross.editor.palette

import android.os.Looper
import com.handmadeapp.logging.Logger

internal object S7ThreadGuard {
    private const val CATEGORY = "PALETTE"

    fun assertBackground(entryPoint: String) {
        val mainLooper = Looper.getMainLooper()
        if (mainLooper != null && Thread.currentThread() === mainLooper.thread) {
            Logger.fatal(
                CATEGORY,
                "s7.thread.violation",
                mapOf(
                    "entry_point" to entryPoint,
                    "thread" to Thread.currentThread().name
                )
            )
        }
    }
}
