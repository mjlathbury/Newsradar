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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var readerArticle by remember { mutableStateOf<com.newsradar.app.data.Article?>(null) }
    var readerMode by remember { mutableStateOf(ReaderMode.WEB) }

    // Device Back closes the reader overlay instead of exiting.
    androidx.activity.compose.BackHandler(enabled = readerArticle != null) {
        readerArticle = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NewsRadar") },
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
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Weather bar pinned at the very top (below the app bar).
            WeatherBar(state = weather, showSun = showSun)

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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        items(state.articles, key = { it.id }) { article ->
                            ArticleCard(
                                article = article,
                                reasons = state.reasons[article.id].orEmpty(),
                                showImages = showImages,
                                summary = summaries[article.id],
                                onSummary = {
                                    readerArticle = article
                                    readerMode = ReaderMode.SUMMARY
                                },
                                ratingDisplay = ratingDisplay,
                                onOpen = {
                                    readerArticle = article
                                    readerMode = ReaderMode.WEB
                                },
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

            if (readerArticle != null) {
                ReaderOverlay(
                    article = readerArticle!!,
                    mode = readerMode,
                    summaryText = summaries[readerArticle!!.id]?.text,
                    summaryLoading = summaries[readerArticle!!.id]?.loading == true,
                    summaryError = summaries[readerArticle!!.id]?.error == true,
                    onRequestSummary = { vm.requestSummary(readerArticle!!) },
                    onSwitchToWeb = { readerMode = ReaderMode.WEB },
                    onClose = { readerArticle = null }
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
