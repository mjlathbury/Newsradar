package com.newsradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newsradar.app.engine.Summarizer
import com.newsradar.app.rss.ArticleFetcher
import com.newsradar.app.data.Article
import com.newsradar.app.data.NewsRepository
import com.newsradar.app.data.Rating
import com.newsradar.app.prefs.ColorScheme
import com.newsradar.app.prefs.RatingDisplay
import com.newsradar.app.prefs.SettingsStore
import com.newsradar.app.prefs.ThemeMode
import com.newsradar.app.weather.WeatherData
import com.newsradar.app.weather.WeatherProvider
import com.newsradar.app.weather.WeatherRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

data class FeedUiState(
    val articles: List<Article> = emptyList(),
    val reasons: Map<String, List<String>> = emptyMap(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val page: Int = 0,
    val canLoadMore: Boolean = true,
    val error: String? = null
)

data class GreetingState(
    val show: Boolean = false,
    val greeting: String = "",
    val dateLine: String = ""
)

data class WeatherUiState(
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val data: WeatherData? = null,
    val message: String? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NewsRepository.get(app)
    private val settings = SettingsStore(app)
    private val weatherRepo = WeatherRepository()

    private val _feed = MutableStateFlow(FeedUiState())
    val feed: StateFlow<FeedUiState> = _feed.asStateFlow()

    private val _greeting = MutableStateFlow(GreetingState())
    val greeting: StateFlow<GreetingState> = _greeting.asStateFlow()

    /** State of an on-demand 60s summary: idle, loading, done (text), or failed. */
    data class SummaryState(
        val loading: Boolean = false,
        val text: String? = null,
        val error: Boolean = false,
        /** True only when [text] came from a successful full-article fetch (not the
         *  RSS blurb fallback). A blurb fallback is never cached-sticky — it must
         *  keep retrying so a transient fetch failure doesn't stick a short blurb. */
        val fetched: Boolean = false,
        /** Consecutive failed fetches for this article. Capped so a permanently
         *  dead link (e.g. a 410 tracking URL) doesn't spam fetchText on every open. */
        val failedAttempts: Int = 0
    )

    /** Max fetch retries per article per session before we stop trying. */
    private val MAX_SUMMARY_RETRIES = 3

    private val _summaries = MutableStateFlow<Map<String, SummaryState>>(emptyMap())
    val summaries: StateFlow<Map<String, SummaryState>> = _summaries.asStateFlow()

    /** Fetch + summarize an article's body on demand (only when the user asks). */
    fun requestSummary(article: Article) {
        val existing = _summaries.value[article.id]
        // Skip if loading, or we already have a *fetched* summary, or we've already
        // retried enough times on a dead link (avoid spamming fetchText forever).
        if (existing?.loading == true ||
            (existing?.text != null && existing.fetched) ||
            existing?.failedAttempts ?: 0 >= MAX_SUMMARY_RETRIES
        ) return

        viewModelScope.launch {
            _summaries.update { it + (article.id to SummaryState(loading = true)) }
            // Always prefer the full article body when we can fetch it — the RSS
            // blurb is only a short lede, which yields a too-brief summary for
            // outlets like the Guardian/Mirror. Fall back to the blurb only if the
            // fetch fails (never show "Couldn't build" when we have text).
            val blurb = article.summary
            // fetchText is already main-safe (it switches to Dispatchers.IO
            // internally) and catches its own network exceptions, returning null
            // on failure — so no withContext/try-catch wrapper is needed here.
            val fetched = ArticleFetcher.fetchText(article.link, getApplication())
            val body = when {
                fetched != null && fetched.length > blurb.length -> fetched
                blurb.isNotBlank() -> blurb
                else -> fetched
            }
            val result = if (body != null && body.isNotBlank()) {
                val summary = withContext(Dispatchers.Default) { Summarizer.summarize(body) }
                // Mark as fetched ONLY when the text actually came from the article
                // body (not the blurb fallback), so failures keep retrying.
                SummaryState(text = summary, fetched = fetched != null && fetched.length > blurb.length)
            } else {
                // Fetch failed (or both empty): remember the attempt so a permanently
                // dead link stops retrying after MAX_SUMMARY_RETRIES.
                val attempts = (existing?.failedAttempts ?: 0) + 1
                SummaryState(error = true, failedAttempts = attempts)
            }
            _summaries.update { it + (article.id to result) }
        }
    }

    private val _weather = MutableStateFlow(WeatherUiState())
    val weather: StateFlow<WeatherUiState> = _weather.asStateFlow()

    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val colorScheme: StateFlow<ColorScheme> =
        settings.colorScheme.stateIn(viewModelScope, SharingStarted.Eagerly, ColorScheme.BLUE)

    private val _userName = MutableStateFlow("")
    val userName: StateFlow<String> = _userName.asStateFlow()
    private val _town = MutableStateFlow("")
    val town: StateFlow<String> = _town.asStateFlow()
    val weatherProviderId: StateFlow<String> =
        settings.weatherProviderId.stateIn(viewModelScope, SharingStarted.Eagerly, "met_office")
    val weatherEnabled: StateFlow<Boolean> =
        settings.weatherEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val showImages: StateFlow<Boolean> =
        settings.showImages.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val ratingDisplay: StateFlow<RatingDisplay> =
        settings.ratingDisplay.stateIn(viewModelScope, SharingStarted.Eagerly, RatingDisplay.FULL)
    val seedInterests: StateFlow<List<String>> =
        settings.seedInterests.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val dislikeInterests: StateFlow<List<String>> =
        settings.dislikeInterests.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val showDateBar: StateFlow<Boolean> =
        settings.showDateBar.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val showSun: StateFlow<Boolean> =
        settings.showSun.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val outletStates = repo.observeOutletStates()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Last successful feed fetch time, used to throttle onResume auto-refresh. */
    private var lastFetchTime = 0L

    /** Single-flight guard so rapid toggle taps don't fire concurrent syncs. */
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch { repo.ensureOutletStates() }
        viewModelScope.launch {
            _userName.value = settings.userName.first()
            _town.value = settings.town.first()
            loadWeather()
        }
        loadFirstPage()
        showGreeting()
    }

    // ---- Greeting ----
    private fun showGreeting() {
        viewModelScope.launch {
            val name = settings.userName.first()
            val now = LocalTime.now()
            val part = when (now.hour) {
                in 5..11 -> "Good Morning"
                in 12..17 -> "Good Afternoon"
                else -> "Good Evening"
            }
            val greet = if (name.isBlank()) part else "$part, $name"
            val today = LocalDate.now()
            val dow = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            val month = today.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
            val dateLine = "$dow, ${today.dayOfMonth} $month ${today.year}"
            _greeting.value = GreetingState(show = true, greeting = greet, dateLine = dateLine)
        }
    }

    fun dismissGreeting() {
        _greeting.value = _greeting.value.copy(show = false)
    }

    // ---- Weather ----
    fun loadWeather() {
        viewModelScope.launch {
            val enabled = settings.weatherEnabled.first()
            _weather.value = _weather.value.copy(enabled = enabled)
            if (!enabled) return@launch

            val townName = _town.value
            val provider = WeatherProvider.byId(settings.weatherProviderId.first())
            _weather.value = _weather.value.copy(loading = true, message = null)

            when (val r = weatherRepo.load(townName, provider)) {
                is WeatherRepository.Result.Success ->
                    _weather.value = WeatherUiState(enabled = true, data = r.data)
                WeatherRepository.Result.NoTown ->
                    _weather.value = WeatherUiState(enabled = true,
                        message = "Set your town in Settings to see weather.")
                WeatherRepository.Result.TownNotFound ->
                    _weather.value = WeatherUiState(enabled = true,
                        message = "Couldn't find that town. Check the spelling in Settings.")
                WeatherRepository.Result.NetworkError ->
                    _weather.value = WeatherUiState(enabled = true,
                        message = "Weather unavailable right now.")
            }
        }
    }

    // ---- Feed ----
    fun loadFirstPage() {
        viewModelScope.launch {
            _feed.value = _feed.value.copy(loading = true, page = 0)
            val first = repo.getFeedPage(0)
            if (first.isEmpty()) {
                refreshNow() // Auto-pull on first run so the feed is never empty.
            } else {
                _feed.value = FeedUiState(
                    articles = first,
                    reasons = buildReasons(first),
                    loading = false,
                    page = 0,
                    canLoadMore = first.size == 5
                )
            }
        }
    }

    fun loadMore() {
        val s = _feed.value
        if (s.loading || !s.canLoadMore) return
        viewModelScope.launch {
            val nextPage = s.page + 1
            val loaded = s.articles.map { it.id }.toSet()
            val more = repo.getFeedPage(nextPage, excludeIds = loaded)
            _feed.value = s.copy(
                articles = s.articles + more,
                reasons = s.reasons + buildReasons(more),
                page = nextPage,
                canLoadMore = more.size == 5
            )
        }
    }

    fun refreshNow() {
        // Cancel any in-flight sync so rapid toggle taps coalesce into one fetch.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _feed.value = _feed.value.copy(refreshing = true, error = null)
            try {
                repo.refresh()
                val first = repo.getFeedPage(0)
                _feed.value = FeedUiState(
                    articles = first,
                    reasons = buildReasons(first),
                    page = 0,
                    canLoadMore = first.size == 5
                )
                lastFetchTime = System.currentTimeMillis()
            } catch (e: Exception) {
                _feed.value = _feed.value.copy(
                    refreshing = false,
                    error = "Couldn't refresh. Check your connection."
                )
            }
            loadWeather()
        }
    }

    /** Called from MainActivity.onResume: only re-pull if the feed is stale
     *  (>15 min old) so we don't hammer RSS endpoints on every resume. */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastFetchTime > 15 * 60 * 1000) refreshNow()
    }

    fun rate(article: Article, rating: Rating) {
        viewModelScope.launch {
            repo.rate(article, rating)
            val updated = if (rating == Rating.RED) {
                _feed.value.articles.filterNot { it.id == article.id }
            } else {
                _feed.value.articles.map {
                    if (it.id == article.id) it.copy(rating = rating.name) else it
                }
            }
            _feed.value = _feed.value.copy(articles = updated)
        }
    }

    private suspend fun buildReasons(list: List<Article>): Map<String, List<String>> =
        list.associate { it.id to repo.reasonsFor(it) }

    // ---- Settings actions ----
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setScheme(scheme: ColorScheme) = viewModelScope.launch { settings.setColorScheme(scheme) }
    fun setOutletEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        repo.setOutletEnabled(id, enabled)
        // Re-fetch + re-query immediately so enabling a source pulls its articles
        // right away (don't wait for a manual pull — otherwise a freshly-enabled
        // outlet shows nothing until the user refreshes). Mirrors setSeedInterests.
        refreshNow()
    }

    fun setUserName(name: String) {
        _userName.value = name
        viewModelScope.launch { settings.setUserName(name) }
    }
    fun setTown(t: String) {
        _town.value = t
        viewModelScope.launch { settings.setTown(t) }
        loadWeather()
    }
    fun setWeatherProvider(id: String) = viewModelScope.launch {
        settings.setWeatherProvider(id)
        loadWeather()
    }
    fun setWeatherEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setWeatherEnabled(enabled)
        _weather.value = _weather.value.copy(enabled = enabled)
        if (enabled) loadWeather()
    }
    fun setShowImages(enabled: Boolean) = viewModelScope.launch {
        settings.setShowImages(enabled)
    }
    fun setRatingDisplay(mode: RatingDisplay) = viewModelScope.launch {
        settings.setRatingDisplay(mode)
    }
    fun setSeedInterests(words: List<String>) = viewModelScope.launch {
        settings.setSeedInterests(words)
        repo.applySeeds(words)
        // Re-rank the visible feed immediately so the new interests take effect
        // without the user having to manually refresh.
        val first = repo.getFeedPage(0)
        _feed.value = FeedUiState(
            articles = first,
            reasons = buildReasons(first),
            page = 0,
            canLoadMore = first.size == 5
        )
    }
    fun setDislikeInterests(words: List<String>) = viewModelScope.launch {
        settings.setDislikeInterests(words)
        repo.applyDislikes(words)
        // Re-rank so disliked topics sink to the bottom (shown rarely via exploration).
        val first = repo.getFeedPage(0)
        _feed.value = FeedUiState(
            articles = first,
            reasons = buildReasons(first),
            page = 0,
            canLoadMore = first.size == 5
        )
    }
    fun setShowDateBar(enabled: Boolean) = viewModelScope.launch {
        settings.setShowDateBar(enabled)
    }
    fun setShowSun(enabled: Boolean) = viewModelScope.launch {
        settings.setShowSun(enabled)
    }
}
