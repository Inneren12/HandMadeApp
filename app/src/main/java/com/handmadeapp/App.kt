package com.handmadeapp

import android.app.Application
import com.handmadeapp.logging.Logger
import com.handmadeapp.diagnostics.DiagnosticsManager
import com.handmadeapp.logging.LogLevel



class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val sess = DiagnosticsManager.startSession(this)
        Logger.init(
            sessionDir = sess.dir,
            sessionId = sess.id,
            minLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO,
            logcatDebugEnabled = BuildConfig.DEBUG
        )
        Logger.i("UI", "app.start", mapOf("version" to BuildConfig.VERSION_NAME, "debug" to BuildConfig.DEBUG))
    }
}