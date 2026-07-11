package com.newsradar.app.weather

/**
 * Weather "providers" the user can pick between (single-select in Settings).
 *
 * NOTE: BBC has no official public weather API — its endpoint is undocumented and
 * unstable, so we do NOT use it. Instead we use Open-Meteo (free, no API key) and
 * let the user choose the underlying forecast MODEL. The default is the UK Met
 * Office model, which is the same official data BBC Weather is based on. This app is
 * UK-only, so all options are UK/Europe models.
 */
enum class WeatherProvider(
    val id: String,
    val label: String,
    /** Open-Meteo `models` parameter value; null = Open-Meteo "best match". */
    val model: String?
) {
    MET_OFFICE("met_office", "Met Office (UK)", "ukmo_seamless"),
    ECMWF("ecmwf", "ECMWF (Europe)", "ecmwf_ifs025"),
    BEST_MATCH("best_match", "Best match (auto)", null);

    companion object {
        val DEFAULT = MET_OFFICE
        fun byId(id: String): WeatherProvider =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
