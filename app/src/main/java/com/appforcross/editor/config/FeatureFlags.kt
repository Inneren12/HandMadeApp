package com.appforcross.editor.config

import com.handmadeapp.logging.Logger

object FeatureFlags {
    const val S7_SAMPLING = true
    const val S7_OVERLAY = true
    const val S7_INIT = true
    const val S7_INIT_FALLBACKS = true
    const val S7_GREEDY = true

    @Volatile
    private var logged = false

    fun logFlagsOnce() {
        if (logged) return
        synchronized(this) {
            if (logged) return
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_SAMPLING", "enabled" to S7_SAMPLING))
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_OVERLAY", "enabled" to S7_OVERLAY))
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_INIT", "enabled" to S7_INIT))
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_INIT_FALLBACKS", "enabled" to S7_INIT_FALLBACKS))
            Logger.i("FEATURE", "flag", mapOf("name" to "S7_GREEDY", "enabled" to S7_GREEDY))
            logged = true
        }
    }

    fun logGreedyFlag() {
        Logger.i("FEATURE", "flag", mapOf("name" to "S7_GREEDY", "enabled" to S7_GREEDY))
    }
}
