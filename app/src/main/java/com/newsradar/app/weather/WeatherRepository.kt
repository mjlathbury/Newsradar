package com.newsradar.app.weather

/**
 * Thin repository over OpenMeteoClient. Caches the last geocode so we don't
 * re-resolve the town on every forecast refresh.
 */
class WeatherRepository {

    private val client = OpenMeteoClient()
    private var cachedTown: String? = null
    private var cachedGeo: OpenMeteoClient.Geo? = null

    sealed class Result {
        data class Success(val data: WeatherData) : Result()
        object NoTown : Result()
        object TownNotFound : Result()
        object NetworkError : Result()
    }

    suspend fun load(town: String, provider: WeatherProvider): Result {
        if (town.isBlank()) return Result.NoTown

        val geo = if (town == cachedTown && cachedGeo != null) {
            cachedGeo!!
        } else {
            val g = client.geocode(town) ?: return Result.TownNotFound
            cachedTown = town
            cachedGeo = g
            g
        }

        val data = client.forecast(geo, provider) ?: return Result.NetworkError
        return Result.Success(data)
    }
}
