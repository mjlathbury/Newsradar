package com.newsradar.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.newsradar.app.rss.ArticleFetcher
import com.newsradar.app.util.UrlUtils
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.newsradar.app.data.Rating
import com.newsradar.app.ui.ReaderOverlay
import com.newsradar.app.ui.ReaderMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val state by vm.feed.collectAsState()
    val greeting by vm.greeting.collectAsState()
    val showImages by vm.showImages.collectAsState()
    val readers by vm.readers.collectAsState()
    val readerFont by vm.readerFont.collectAsState()
    val readerSize by vm.readerSize.collectAsState()
    val ratingDisplay by vm.ratingDisplay.collectAsState()
    val weather by vm.weather.collectAsState()
    val showDateBar by vm.showDateBar.collectAsState()
    val showSun by vm.showSun.collectAsState()

    // In-app reader overlay state.
    var readerOpen by remember { mutableStateOf(false) }
    var readerArticle by remember { mutableStateOf<com.newsradar.app.data.Article?>(null) }
    // Hero image captured at open time from the (known-good) feed item, so the body
    // fetch can never clobber it with a parsed-null imageUrl.
    var readerImageUrl by remember { mutableStateOf<String?>(null) }
    var readerUrl by remember { mutableStateOf("") }
    var readerTitle by remember { mutableStateOf("") }
    var readerOutlet by remember { mutableStateOf("") }
    var readerMode by remember { mutableStateOf(ReaderMode.READER) }
    val context = LocalContext.current

    fun openCustomTab(url: String) {
        runCatching {
            androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                .launchUrl(context, android.net.Uri.parse(url))
        }
    }

    fun openArticle(a: com.newsradar.app.data.Article, mode: ReaderMode) {
        vm.recordRead(a)
        // Known consent-gated outlets extract to a stub — skip the broken reader and
        // open the real page in a Chrome Custom Tab instead.
        if (mode == ReaderMode.READER && com.newsradar.app.data.Outlets.isGated(a.outletId)) {
            openCustomTab(a.link)
            return
        }
        readerArticle = a
        readerImageUrl = a.imageUrl
        readerUrl = a.link
        readerTitle = a.title
        readerOutlet = a.outletName
        readerMode = mode
        readerOpen = true
        // Reader body fetches (or loads from cache) as soon as the window opens.
        if (mode == ReaderMode.READER) vm.requestArticle(a)
    }

    // Device Back closes the reader overlay instead of exiting.
    androidx.activity.compose.BackHandler(enabled = readerOpen) {
        readerOpen = false
    }

    var menuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NewsRadar #${com.newsradar.app.BuildInfo.BUILD_NUMBER}") },
                navigationIcon = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshNow() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh now")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {
                if (showDateBar) {
                    val now = java.time.LocalDate.now()
                    Text(
                        now.format(
                            java.time.format.DateTimeFormatter
                                .ofPattern("EEEE, d MMMM yyyy", java.util.Locale.getDefault())
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                // Weather bar below the date bar (hidden entirely when disabled in Settings).
                WeatherBar(state = weather, showSun = showSun)

                Box(Modifier.fillMaxSize()) {
                when {
                    state.loading && state.articles.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.articles.isEmpty() -> EmptyState(
                        refreshing = state.refreshing,
                        error = state.error,
                        onRefresh = { vm.refreshNow() }
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        items(state.articles, key = { it.id }) { article ->
                            ArticleCard(
                                article = article,
                                reasons = state.reasons[article.id].orEmpty(),
                                showImages = showImages,
                                onRead = { openArticle(article, ReaderMode.READER) },
                                ratingDisplay = ratingDisplay,
                                onOpen = { openArticle(article, ReaderMode.WEB) },
                                onRate = { rating: Rating -> vm.rate(article, rating) }
                            )
                        }
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (state.canLoadMore) {
                                    CircularProgressIndicator(strokeWidth = 2.dp)
                                } else {
                                    Text(
                                        "You're all caught up.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Infinite scroll: trigger loadMore when the last visible item is
                // within 5 of the end. Effect restarts when load state changes so
                // it always reads the freshest canLoadMore/loading.
                LaunchedEffect(listState, state.canLoadMore, state.loading) {
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val total = info.totalItemsCount
                        last to total
                    }.collect { (last, total) ->
                        if (state.canLoadMore && !state.loading && total - last <= 5) {
                            vm.loadMore()
                        }
                    }
                }
                if (state.refreshing) {
                    CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                }
            }
            }

            // Hamburger dropdown (anchored top-left). Home = scroll feed to top.
            // wrapContentSize so the anchor Box doesn't cover the feed and block touches.
            Box(Modifier.wrapContentSize(Alignment.TopStart)) {
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Home") },
                        onClick = {
                            menuExpanded = false
                            scope.launch { listState.scrollToItem(0) }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("History") },
                        onClick = {
                            menuExpanded = false
                            onOpenHistory()
                        }
                    )
                }
            }

            if (readerOpen) {
                // Recover a hero when the feed image is missing OR is a known-bad
                // signed URL (Guardian's UA-pinned ?s= 401s through Coil). Fetch the
                // page's unsigned og:image, persist it back to Room (so the feed card
                // and future opens get it instantly + offline), and show it now.
                val needRecovery = readerArticle?.let { a ->
                    a.imageUrl.isNullOrBlank() ||
                        UrlUtils.isSignedImageUrl(a.imageUrl)
                } ?: false
                if (needRecovery) {
                    LaunchedEffect(readerArticle?.id) {
                        readerArticle?.let { a ->
                            ArticleFetcher.fetchHeroImage(a.link)?.let { og ->
                                readerImageUrl = og
                                vm.updateArticleImage(a.id, og)
                            }
                        }
                    }
                }
                ReaderOverlay(
                    url = readerUrl,
                    title = readerTitle,
                    outlet = readerOutlet,
                    mode = readerMode,
                    body = readerArticle?.let { readers[it.id]?.body ?: it.articleBody },
                    loading = readerArticle?.let { readers[it.id]?.loading == true } == true,
                    error = readerArticle?.let { readers[it.id]?.error == true } == true,
                    heroUrl = readerImageUrl ?: readerArticle?.imageUrl,
                    font = readerFont,
                    size = readerSize,
                    onReadWeb = { readerArticle?.let { openCustomTab(it.link) } },
                    onClose = { readerOpen = false }
                )
            }
        }

        // Welcome popup (time-of-day greeting + name + date) — shown whenever the
        // app is opened (driven from MainActivity.onResume via vm.showGreeting()).
        GreetingDialog(state = greeting, onDismiss = vm::dismissGreeting)
    }
}

@Composable
private fun EmptyState(refreshing: Boolean, error: String?, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            error ?: "No stories yet. Pull the latest UK headlines to get started.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.padding(top = 16.dp)) {
            if (refreshing) CircularProgressIndicator()
            else OutlinedButton(onClick = onRefresh) { Text("Fetch today's news") }
        }
    }
}
