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

/** How rating controls are shown on each article card. */
enum class RatingDisplay { FULL, COLOUR, NONE }

/** Available colour palettes the user can cycle through. */
enum class ColorScheme { BLUE, TEAL, PURPLE, SUNSET, FOREST, MONO }

/** Reader typography: font family and text size. */
enum class ReaderFont { SYSTEM, SERIF, SANS, MONO }
enum class ReaderSize { S, M, L, XL }

class SettingsStore(private val context: Context) {

    private val THEME = stringPreferencesKey("theme_mode")
    private val SCHEME = stringPreferencesKey("color_scheme")
    private val FETCH_HOUR = intPreferencesKey("fetch_hour")
    private val USER_NAME = stringPreferencesKey("user_name")
    private val TOWN = stringPreferencesKey("weather_town")
    private val WEATHER_PROVIDER = stringPreferencesKey("weather_provider")
    private val WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
    private val SHOW_IMAGES = booleanPreferencesKey("show_images")
    private val RATING_DISPLAY = stringPreferencesKey("rating_display")
    private val SEED_INTERESTS = stringPreferencesKey("seed_interests")
    private val DISLIKE_INTERESTS = stringPreferencesKey("dislike_interests")
    private val SHOW_DATE_BAR = booleanPreferencesKey("show_date_bar")
    private val SHOW_SUN = booleanPreferencesKey("show_sun")
    private val READER_FONT = stringPreferencesKey("reader_font")
    private val READER_SIZE = stringPreferencesKey("reader_size")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        ThemeMode.valueOf(it[THEME] ?: ThemeMode.SYSTEM.name)
    }
    val colorScheme: Flow<ColorScheme> = context.dataStore.data.map {
        ColorScheme.valueOf(it[SCHEME] ?: ColorScheme.TEAL.name)
    }
    val fetchHour: Flow<Int> = context.dataStore.data.map { it[FETCH_HOUR] ?: 7 }

    val userName: Flow<String> = context.dataStore.data.map { it[USER_NAME] ?: "" }
    val town: Flow<String> = context.dataStore.data.map { it[TOWN] ?: "" }
    /** Weather provider id; defaults to Met Office (UK). */
    val weatherProviderId: Flow<String> =
        context.dataStore.data.map { it[WEATHER_PROVIDER] ?: "met_office" }
    val weatherEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[WEATHER_ENABLED] ?: true }
    val showImages: Flow<Boolean> =
        context.dataStore.data.map { it[SHOW_IMAGES] ?: true }
    /** How rating controls appear on cards. */
    val ratingDisplay: Flow<RatingDisplay> =
        context.dataStore.data.map {
            try { RatingDisplay.valueOf(it[RATING_DISPLAY] ?: RatingDisplay.FULL.name) }
            catch (e: Exception) { RatingDisplay.FULL }
        }
    /** Seed keyword interests (comma/space separated) used to bias the early feed. */
    val seedInterests: Flow<List<String>> =
        context.dataStore.data.map {
            it[SEED_INTERESTS].orEmpty()
                .split(Regex("[,\\n]")).map { w -> w.trim() }.filter { it.isNotBlank() }
        }
    /** Disliked keyword interests — down-weighted, shown rarely but not hidden. */
    val dislikeInterests: Flow<List<String>> =
        context.dataStore.data.map {
            it[DISLIKE_INTERESTS].orEmpty()
                .split(Regex("[,\\n]")).map { w -> w.trim() }.filter { it.isNotBlank() }
        }
    /** Show a persistent date bar (Day, Date Month Year) at the top of the feed. */
    val showDateBar: Flow<Boolean> =
        context.dataStore.data.map { it[SHOW_DATE_BAR] ?: true }
    /** Show sunrise/sunset under the expanded weather. */
    val showSun: Flow<Boolean> =
        context.dataStore.data.map { it[SHOW_SUN] ?: true }

    /** Reader font family; defaults to Serif for comfortable long-form reading. */
    val readerFont: Flow<ReaderFont> =
        context.dataStore.data.map {
            try { ReaderFont.valueOf(it[READER_FONT] ?: ReaderFont.SERIF.name) }
            catch (e: Exception) { ReaderFont.SERIF }
        }
    /** Reader text size; defaults to Medium. */
    val readerSize: Flow<ReaderSize> =
        context.dataStore.data.map {
            try { ReaderSize.valueOf(it[READER_SIZE] ?: ReaderSize.M.name) }
            catch (e: Exception) { ReaderSize.M }
        }

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

    suspend fun setShowImages(enabled: Boolean) =
        context.dataStore.edit { it[SHOW_IMAGES] = enabled }

    suspend fun setRatingDisplay(mode: RatingDisplay) =
        context.dataStore.edit { it[RATING_DISPLAY] = mode.name }

    suspend fun setSeedInterests(words: List<String>) =
        context.dataStore.edit { it[SEED_INTERESTS] = words.joinToString(",") }

    suspend fun setDislikeInterests(words: List<String>) =
        context.dataStore.edit { it[DISLIKE_INTERESTS] = words.joinToString(",") }

    suspend fun setShowDateBar(enabled: Boolean) =
        context.dataStore.edit { it[SHOW_DATE_BAR] = enabled }

    suspend fun setShowSun(enabled: Boolean) =
        context.dataStore.edit { it[SHOW_SUN] = enabled }

    suspend fun setReaderFont(f: ReaderFont) =
        context.dataStore.edit { it[READER_FONT] = f.name }

    suspend fun setReaderSize(s: ReaderSize) =
        context.dataStore.edit { it[READER_SIZE] = s.name }
}
