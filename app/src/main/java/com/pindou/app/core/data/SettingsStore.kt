package com.pindou.app.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 用户设置（默认参数、外观、默认参数） */
class SettingsStore(private val context: Context) {

    companion object {
        val GRID_W = intPreferencesKey("default_grid_w")
        val GRID_H = intPreferencesKey("default_grid_h")
        val PALETTE_BRAND = stringPreferencesKey("default_palette_brand")
        val THEME_MODE = stringPreferencesKey("theme_mode")       // system | light | dark
    }

    data class Snapshot(
        val defaultGridW: Int = 29,
        val defaultGridH: Int = 29,
        val defaultPaletteBrand: String = "MARD",
        val themeMode: String = "system",
    )

    val flow: Flow<Snapshot> = context.dataStore.data.map { p -> p.toSnapshot() }

    private fun Preferences.toSnapshot() = Snapshot(
        defaultGridW = this[GRID_W] ?: 29,
        defaultGridH = this[GRID_H] ?: 29,
        defaultPaletteBrand = this[PALETTE_BRAND] ?: "MARD",
        themeMode = this[THEME_MODE] ?: "system",
    )

    suspend fun setDefaultGrid(w: Int, h: Int) = context.dataStore.edit {
        it[GRID_W] = w
        it[GRID_H] = h
    }

    suspend fun setDefaultPalette(brand: String) = context.dataStore.edit { it[PALETTE_BRAND] = brand }

    suspend fun setThemeMode(mode: String) = context.dataStore.edit { it[THEME_MODE] = mode }

}