package com.newsradar.app.ui

import android.content.Intent
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
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import com.newsradar.app.data.Rating
import com.newsradar.app.ui.ReaderOverlay
import com.newsradar.app.ui.ReaderMode

/**
 * In-app live video via each outlet's official YouTube channel, played in the
 * YouTube IFrame *embed* player (clean fullscreen, no site chrome). Using
 * `embed/live_stream?channel=ID` resolves to that channel's current live broadcast
 * and renders as a proper fullscreen player. Verified reachable (HTTP 200) for
 * BBC / Sky / LBC.
 */
private val VIDEO_SOURCES = listOf(
    VideoSource("BBC News", "https://www.youtube.com/embed/live_stream?channel=UC16niRr50-MSBwiO3YDb3RA&autoplay=1&mute=1"),
    VideoSource("Sky News", "https://www.youtube.com/embed/live_stream?channel=UCky1dE_V6Vml3HAvFcPXMIw&autoplay=1&mute=1")
)

private data class VideoSource(val name: String, val url: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val state by vm.feed.collectAsState()
    val greeting by vm.greeting.collectAsState()
    val showImages by vm.showImages.collectAsState()
    val summaries by vm.summaries.collectAsState()
    val ratingDisplay by vm.ratingDisplay.collectAsState()
    val weather by vm.weather.collectAsState()
    val showDateBar by vm.showDateBar.collectAsState()
    val showSun by vm.showSun.collectAsState()

    // In-app reader overlay state.
    var readerOpen by remember { mutableStateOf(false) }
    var readerArticle by remember { mutableStateOf<com.newsradar.app.data.Article?>(null) }
    var readerUrl by remember { mutableStateOf("") }
    var readerTitle by remember { mutableStateOf("") }
    var readerOutlet by remember { mutableStateOf("") }
    var readerMode by remember { mutableStateOf(ReaderMode.WEB) }
    var readerVideo by remember { mutableStateOf(false) }

    val activity = (LocalContext.current as? android.app.Activity)

    fun openArticle(a: com.newsradar.app.data.Article, mode: ReaderMode) {
        readerArticle = a
        readerUrl = a.link
        readerTitle = a.title
        readerOutlet = a.outletName
        readerMode = mode
        readerVideo = false
        readerOpen = true
        // Brief Summary auto-generates as soon as the window opens.
        if (mode == ReaderMode.SUMMARY) vm.requestSummary(a)
    }

    fun openVideo(name: String, url: String) {
        readerArticle = null
        readerUrl = url
        readerTitle = name
        readerOutlet = name
        readerMode = ReaderMode.WEB
        readerVideo = true
        readerOpen = true
        // Landscape is applied in a LaunchedEffect (after composition) to avoid
        // colliding with WebView init, which could crash the activity.
    }

    // Force landscape only while a video window is open; restore previous on close.
    androidx.compose.runtime.LaunchedEffect(readerOpen, readerVideo) {
        if (readerOpen && readerVideo) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
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
                                summary = summaries[article.id],
                                onSummary = { openArticle(article, ReaderMode.SUMMARY) },
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
                                    Button(onClick = { vm.loadMore() }) { Text("Load more articles") }
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
                    HorizontalDivider()
                    Text(
                        "Video",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                    VIDEO_SOURCES.forEach { src ->
                        DropdownMenuItem(
                            text = { Text(src.name) },
                            onClick = {
                                menuExpanded = false
                                openVideo(src.name, src.url)
                            }
                        )
                    }
                }
            }

            if (readerOpen) {
                ReaderOverlay(
                    url = readerUrl,
                    title = readerTitle,
                    outlet = readerOutlet,
                    mode = readerMode,
                    videoMode = readerVideo,
                    summaryText = readerArticle?.let { summaries[it.id]?.text ?: it.summary },
                    summaryLoading = readerArticle?.let { summaries[it.id]?.loading == true } == true,
                    summaryError = readerArticle?.let { summaries[it.id]?.error == true } == true,
                    onClose = {
                        readerOpen = false
                        if (readerVideo) {
                            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                )
            }
        }
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
