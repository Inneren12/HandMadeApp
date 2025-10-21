package com.handmadeapp

import android.app.Application
import com.appforcross.editor.config.FeatureFlags
import com.handmadeapp.diagnostics.DiagnosticsManager

/**
 * Глобальная инициализация приложения:
 *  - старт сессии диагностики (diag/session-<ts>)
 *  - (при желании) можно здесь настраивать уровень логов и пр.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FeatureFlags.init(this)
        DiagnosticsManager.startSession(this)
    }
}
