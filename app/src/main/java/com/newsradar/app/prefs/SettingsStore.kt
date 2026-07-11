package com.newsradar.app.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Theme mode selection. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Available colour palettes the user can cycle through. */
enum class ColorScheme { BLUE, TEAL, PURPLE, SUNSET, FOREST, MONO }

class SettingsStore(private val context: Context) {

    private val THEME = stringPreferencesKey("theme_mode")
    private val SCHEME = stringPreferencesKey("color_scheme")
    private val FETCH_HOUR = intPreferencesKey("fetch_hour")
    private val USER_NAME = stringPreferencesKey("user_name")
    private val TOWN = stringPreferencesKey("weather_town")
    private val WEATHER_PROVIDER = stringPreferencesKey("weather_provider")
    private val WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        ThemeMode.valueOf(it[THEME] ?: ThemeMode.SYSTEM.name)
    }
    val colorScheme: Flow<ColorScheme> = context.dataStore.data.map {
        ColorScheme.valueOf(it[SCHEME] ?: ColorScheme.BLUE.name)
    }
    val fetchHour: Flow<Int> = context.dataStore.data.map { it[FETCH_HOUR] ?: 7 }

    val userName: Flow<String> = context.dataStore.data.map { it[USER_NAME] ?: "" }
    val town: Flow<String> = context.dataStore.data.map { it[TOWN] ?: "" }
    /** Weather provider id; defaults to Met Office (UK). */
    val weatherProviderId: Flow<String> =
        context.dataStore.data.map { it[WEATHER_PROVIDER] ?: "met_office" }
    val weatherEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[WEATHER_ENABLED] ?: true }

    suspend fun setThemeMode(mode: ThemeMode) =
        context.dataStore.edit { it[THEME] = mode.name }

    suspend fun setColorScheme(scheme: ColorScheme) =
        context.dataStore.edit { it[SCHEME] = scheme.name }

    suspend fun setFetchHour(hour: Int) =
        context.dataStore.edit { it[FETCH_HOUR] = hour }

    suspend fun setUserName(name: String) =
        context.dataStore.edit { it[USER_NAME] = name }

    suspend fun setTown(town: String) =
        context.dataStore.edit { it[TOWN] = town }

    suspend fun setWeatherProvider(id: String) =
        context.dataStore.edit { it[WEATHER_PROVIDER] = id }

    suspend fun setWeatherEnabled(enabled: Boolean) =
        context.dataStore.edit { it[WEATHER_ENABLED] = enabled }
}
