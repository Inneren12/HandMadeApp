package com.appforcross.editor.logging

// Compatibility shim to unify logging across modules importing com.appforcross.editor.logging.Logger.
// Delegates to com.handmadeapp.logging.Logger (structured JSONL) if present.

import com.handmadeapp.logging.Logger as BaseLogger

object Logger {
    @JvmStatic
    fun d(cat: String, msg: String, attrs: Map<String, Any?> = emptyMap()) {
        BaseLogger.d(cat, msg, attrs)
    }

    @JvmStatic
    fun i(cat: String, msg: String, attrs: Map<String, Any?> = emptyMap()) {
        BaseLogger.i(cat, msg, attrs)
    }

    @JvmStatic
    fun w(cat: String, msg: String, attrs: Map<String, Any?> = emptyMap()) {
        BaseLogger.w(cat, msg, attrs)
    }

    @JvmStatic
    fun e(
        cat: String,
        msg: String,
        attrs: Map<String, Any?> = emptyMap(),
        err: Throwable? = null
    ) {
        // BaseLogger.e signature: e(cat, msg, data, req, tile, err)
        BaseLogger.e(cat, msg, attrs, null, null, err)
    }
}
