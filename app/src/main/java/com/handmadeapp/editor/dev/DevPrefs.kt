package com.handmadeapp.editor.dev

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object DevPrefs {
    private const val DS_NAME = "dev_prefs"
    private val Context.dataStore by preferencesDataStore(name = DS_NAME)

    private object Keys {
        val DEBUG = booleanPreferencesKey("dev.debug")
    }

    fun isDebug(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[Keys.DEBUG] ?: false }

    suspend fun setDebug(ctx: Context, value: Boolean) {
        ctx.dataStore.edit { prefs ->
            prefs[Keys.DEBUG] = value
        }
    }
}