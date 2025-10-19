package com.handmadeapp

import android.app.Application
import com.handmadeapp.diagnostics.DiagnosticsManager

/**
 * Глобальная инициализация приложения:
 *  - старт сессии диагностики (diag/session-<ts>)
 *  - (при желании) можно здесь настраивать уровень логов и пр.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsManager.startSession(this)
    }
}
