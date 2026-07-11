package com.newsradar.app.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Open-Meteo client. Free, no API key.
 * - Geocoding API resolves a typed town name -> lat/lon.
 * - Forecast API returns current + 7-day daily forecast for the chosen model.
 */
class OpenMeteoClient {

    data class Geo(val name: String, val lat: Double, val lon: Double)

    suspend fun geocode(town: String): Geo? = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(town.trim(), "UTF-8")
            val url = "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=$q&count=1&language=en&format=json"
            val json = getJson(url) ?: return@withContext null
            val results = json.optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null
            val r = results.getJSONObject(0)
            val country = r.optString("country", "")
            val admin = r.optString("admin1", "")
            val display = listOf(r.optString("name"), admin, country)
                .filter { it.isNotBlank() }.joinToString(", ")
            Geo(display, r.getDouble("latitude"), r.getDouble("longitude"))
        } catch (e: Exception) {
            null
        }
    }

    suspend fun forecast(geo: Geo, provider: WeatherProvider): WeatherData? =
        withContext(Dispatchers.IO) {
          try {
            val modelParam = provider.model?.let { "&models=$it" } ?: ""
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${geo.lat}&longitude=${geo.lon}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m," +
                "weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max" +
                "&timezone=auto&forecast_days=7&wind_speed_unit=kmh" + modelParam
            val json = getJson(url) ?: return@withContext null

            val cur = json.optJSONObject("current") ?: return@withContext null
            val curCode = cur.optInt("weather_code")
            val (curDesc, curEmoji) = WmoCodes.describe(curCode)
            val current = CurrentWeather(
                tempC = cur.optDouble("temperature_2m"),
                feelsLikeC = cur.optDouble("apparent_temperature"),
                code = curCode,
                description = curDesc,
                emoji = curEmoji,
                windKph = cur.optDouble("wind_speed_10m"),
                humidity = cur.optInt("relative_humidity_2m")
            )

            val daily = mutableListOf<DailyForecast>()
            val d = json.optJSONObject("daily")
            if (d != null) {
                val times = d.getJSONArray("time")
                val codes = d.getJSONArray("weather_code")
                val maxT = d.getJSONArray("temperature_2m_max")
                val minT = d.getJSONArray("temperature_2m_min")
                val pop = d.optJSONArray("precipitation_probability_max")
                for (i in 0 until times.length()) {
                    val date = LocalDate.parse(times.getString(i))
                    val code = codes.getInt(i)
                    val (desc, emoji) = WmoCodes.describe(code)
                    daily.add(
                        DailyForecast(
                            epochDay = date.toEpochDay(),
                            dayLabel = date.dayOfWeek
                                .getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            minC = minT.getDouble(i),
                            maxC = maxT.getDouble(i),
                            code = code,
                            description = desc,
                            emoji = emoji,
                            precipProb = pop?.optInt(i) ?: 0
                        )
                    )
                }
            }
            WeatherData(locationName = geo.name, current = current, daily = daily)
          } catch (e: Exception) {
            null
          }
        }

    private fun getJson(urlStr: String): JSONObject? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
            }
            conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } catch (e: Exception) {
            null
        }
    }
}
