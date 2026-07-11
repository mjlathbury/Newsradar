package com.newsradar.app.weather

/** Current + daily forecast, normalised for the UI. */
data class WeatherData(
    val locationName: String,
    val current: CurrentWeather,
    val daily: List<DailyForecast>,
    /** Hourly breakdown keyed by epoch day (for tapping a day to drill in). */
    val hourlyByDay: Map<Long, List<HourlyPoint>> = emptyMap()
)

/** A single hour of the forecast. */
data class HourlyPoint(
    val hour: Int,          // 0-23
    val tempC: Double,
    val code: Int,
    val emoji: String,
    val precipProb: Int
)

data class CurrentWeather(
    val tempC: Double,
    val feelsLikeC: Double,
    val code: Int,
    val description: String,
    val emoji: String,
    val windKph: Double,
    val humidity: Int
)

data class DailyForecast(
    val epochDay: Long,
    val dayLabel: String,   // e.g. "Mon 14 Jul"
    val minC: Double,
    val maxC: Double,
    val code: Int,
    val description: String,
    val emoji: String,
    val precipProb: Int,
    val sunrise: String = "",   // ISO local time, e.g. "2026-07-11T05:03"
    val sunset: String = ""     // ISO local time
)

/** WMO weather interpretation codes -> text + emoji. */
object WmoCodes {
    fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "Clear sky" to "☀️"
        1 -> "Mainly clear" to "🌤️"
        2 -> "Partly cloudy" to "⛅"
        3 -> "Overcast" to "☁️"
        45, 48 -> "Fog" to "🌫️"
        51, 53, 55 -> "Drizzle" to "🌦️"
        56, 57 -> "Freezing drizzle" to "🌧️"
        61, 63, 65 -> "Rain" to "🌧️"
        66, 67 -> "Freezing rain" to "🌧️"
        71, 73, 75 -> "Snow" to "❄️"
        77 -> "Snow grains" to "❄️"
        80, 81, 82 -> "Rain showers" to "🌦️"
        85, 86 -> "Snow showers" to "🌨️"
        95 -> "Thunderstorm" to "⛈️"
        96, 99 -> "Thunderstorm with hail" to "⛈️"
        else -> "—" to "🌡️"
    }
}
