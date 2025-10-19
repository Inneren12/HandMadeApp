package com.appforcross.editor.diagnostics

// Thin wrapper to keep backward imports working in modules that expect
// com.appforcross.editor.diagnostics.DiagnosticsManager.
// Delegates to com.handmadeapp.diagnostics.DiagnosticsManager.

import android.content.Context
import java.io.File

object DiagnosticsManager {
    @JvmStatic
    fun currentSessionDir(ctx: Context): File? =
        com.handmadeapp.diagnostics.DiagnosticsManager.currentSessionDir(ctx)
}
