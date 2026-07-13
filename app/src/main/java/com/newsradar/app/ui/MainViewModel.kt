package com.newsradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newsradar.app.rss.ArticleFetcher
import com.newsradar.app.data.Article
import com.newsradar.app.data.NewsRepository
import com.newsradar.app.data.Rating
import com.newsradar.app.data.ReadHistory
import com.newsradar.app.prefs.ColorScheme
import com.newsradar.app.prefs.RatingDisplay
import com.newsradar.app.prefs.ReaderFont
import com.newsradar.app.prefs.ReaderSize
import com.newsradar.app.prefs.SettingsStore
import com.newsradar.app.prefs.ThemeMode
import com.newsradar.app.weather.WeatherData
import com.newsradar.app.weather.WeatherProvider
import com.newsradar.app.weather.WeatherRepository
import com.newsradar.app.util.UrlUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
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

    /** State of an on-demand reader body: idle, loading, done (text), or too-thin. */
    data class ReaderState(
        val loading: Boolean = false,
        /** Paragraph-structured body ("\n\n" blocks, "## " subheadings, "• " bullets). */
        val body: String? = null,
        /** True when extraction was too thin — the UI offers "Read on Web" (CCT). */
        val error: Boolean = false,
        /** Consecutive failed fetches for this article, capped so a dead link
         *  (e.g. a 410 tracking URL) doesn't spam fetchText on every open. */
        val failedAttempts: Int = 0
    )

    /** Max fetch retries per article per session before we stop trying. */
    private val MAX_READER_RETRIES = 3

    /** Minimum body length to consider a reader extraction successful. */
    private val MIN_READER_LEN = 300

    private val _readers = MutableStateFlow<Map<String, ReaderState>>(emptyMap())
    val readers: StateFlow<Map<String, ReaderState>> = _readers.asStateFlow()

    /** Fetch (or load cached) the full article body for the reader, on demand. */
    fun requestArticle(article: Article) {
        val existing = _readers.value[article.id]
        // Skip if already loading, already have a body, or retried enough on a dead link.
        if (existing?.loading == true ||
            existing?.body != null ||
            (existing?.failedAttempts ?: 0) >= MAX_READER_RETRIES
        ) return

        // Offline cache hit: show the previously-extracted body immediately.
        val cached = article.articleBody
        if (!cached.isNullOrBlank()) {
            _readers.update { it + (article.id to ReaderState(body = cached)) }
            return
        }

        viewModelScope.launch {
            _readers.update { it + (article.id to ReaderState(loading = true)) }
            // fetchText is main-safe (switches to Dispatchers.IO internally) and
            // catches its own network exceptions, returning null on failure.
            val fetched = ArticleFetcher.fetchText(article.link, getApplication())
            val result = if (fetched != null && fetched.length >= MIN_READER_LEN) {
                // Cache for offline reads next time.
                repo.cacheArticleBody(article.id, fetched)
                // Implicit training: opening an article and reading it (without
                // explicitly rating) counts as a neutral "amber" signal, so the
                // model learns from reads even when the user skips the buttons.
                // Only fires once, for articles that are still unrated.
                if (article.rating == Rating.NONE.name) {
                    repo.rate(article.copy(rating = Rating.AMBER.name), Rating.AMBER)
                }
                ReaderState(body = fetched)
            } else {
                val attempts = (existing?.failedAttempts ?: 0) + 1
                ReaderState(error = true, failedAttempts = attempts)
            }
            _readers.update { it + (article.id to result) }
        }
    }

    private val _weather = MutableStateFlow(WeatherUiState())
    val weather: StateFlow<WeatherUiState> = _weather.asStateFlow()

    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val colorScheme: StateFlow<ColorScheme> =
        settings.colorScheme.stateIn(viewModelScope, SharingStarted.Eagerly, ColorScheme.TEAL)

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

    val readerFont: StateFlow<ReaderFont> =
        settings.readerFont.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderFont.SERIF)
    val readerSize: StateFlow<ReaderSize> =
        settings.readerSize.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSize.M)

    val outletStates = repo.observeOutletStates()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Read history ----
    private val _historyQuery = MutableStateFlow("")
    val historyQuery: StateFlow<String> = _historyQuery.asStateFlow()

    /** Read history, re-queried live as [historyQuery] changes (LIKE search). */
    val history: StateFlow<List<ReadHistory>> = _historyQuery
        .flatMapLatest { q -> repo.getHistory(q) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setHistoryQuery(q: String) { _historyQuery.value = q }

    /** Persist a read article to history (deduped by id, pruned to 100). */
    fun recordRead(article: Article) {
        viewModelScope.launch { repo.recordRead(article) }
    }

    /** Last successful feed fetch time, used to throttle onResume auto-refresh. */
    private var lastFetchTime = 0L

    /** Single-flight guard so rapid toggle taps don't fire concurrent syncs. */
    private var refreshJob: Job? = null
    /** Skip the first onResume auto-refresh: init() already kicks off a sync, and
     *  letting onResume fire a second one just cancels the first (spurious
     *  JobCancellationException logged as a fake "feed fetch failed"). */
    private var firstResume = true

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
    /** Toggle to re-enable the welcome popup later. Disabled for now. */
    private val GREETING_ENABLED = false

    fun showGreeting() {
        if (!GREETING_ENABLED) return
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
                recoverHeroImages(first)
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
            recoverHeroImages(more)
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
                recoverHeroImages(first)
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
     *  (>15 min old) so we don't hammer RSS endpoints on every resume. The first
     *  onResume right after init() is skipped — init already started a sync, and
     *  firing a second one would cancel the first (noise + fake failure logs). */
    fun refreshIfStale() {
        if (firstResume) { firstResume = false; return }
        if (System.currentTimeMillis() - lastFetchTime > 15 * 60 * 1000) refreshNow()
    }

    fun rate(article: Article, rating: Rating) {
        viewModelScope.launch {
            val prev = Rating.entries.firstOrNull { it.name == article.rating } ?: Rating.NONE
            // Failsafe: tapping the rating the article already has reverts it back to
            // unrated (clears the learning), so any mis-tap can be undone instantly.
            val newRating = if (prev == rating) Rating.NONE else rating
            if (newRating == Rating.NONE) {
                repo.unrate(article, prev)
            } else {
                repo.rate(article, newRating)
            }
            val updated = if (newRating == Rating.RED) {
                // RED hides from the current feed view; re-rating later brings it back.
                _feed.value.articles.filterNot { it.id == article.id }
            } else {
                _feed.value.articles.map {
                    if (it.id == article.id) it.copy(rating = newRating.name) else it
                }
            }
            _feed.value = _feed.value.copy(articles = updated)
        }
    }

    /**
     * Guardian's RSS image URLs are UA-pinned signed (?s=) and 401 through Coil.
     * Recover a working hero by scraping the article page's unsigned og:image,
     * then persist + patch the in-memory list so the feed CARD shows it right
     * away (no need to open the reader first). Blank-image items get the same
     * treatment. Runs concurrently, fire-and-forget per article.
     */
    private fun recoverHeroImages(articles: List<Article>) {
        articles.forEach { a ->
            val needs = a.imageUrl.isNullOrBlank() || UrlUtils.isSignedImageUrl(a.imageUrl)
            if (needs) {
                viewModelScope.launch {
                    val og = ArticleFetcher.fetchHeroImage(a.link) ?: return@launch
                    repo.updateArticleImage(a.id, og)
                    _feed.value = _feed.value.copy(
                        articles = _feed.value.articles.map {
                            if (it.id == a.id) it.copy(imageUrl = og) else it
                        }
                    )
                }
            }
        }
    }

    /** Persist a corrected hero image (e.g. Guardian's UA-pinned signed feed URL
     *  replaced by the page's unsigned og:image) back to Room for instant+offline,
     *  and patch the in-memory feed item so the card repaints without a reload. */
    fun updateArticleImage(id: String, imageUrl: String) {
        viewModelScope.launch {
            repo.updateArticleImage(id, imageUrl)
            _feed.value = _feed.value.copy(
                articles = _feed.value.articles.map {
                    if (it.id == id) it.copy(imageUrl = imageUrl) else it
                }
            )
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

    /** Persist the user's read-quality rating for a provider (GREEN/AMBER/RED/""). */
    fun setOutletReadQuality(id: String, quality: String) = viewModelScope.launch {
        repo.setOutletReadQuality(id, quality)
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
    fun setReaderFont(f: ReaderFont) = viewModelScope.launch { settings.setReaderFont(f) }
    fun setReaderSize(s: ReaderSize) = viewModelScope.launch { settings.setReaderSize(s) }
}
