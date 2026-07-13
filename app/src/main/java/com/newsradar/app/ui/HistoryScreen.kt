package com.newsradar.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newsradar.app.data.Article
import com.newsradar.app.data.ReadHistory
import com.newsradar.app.rss.ArticleFetcher
import com.newsradar.app.util.UrlUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Offline, keyword-searchable list of the last 100 read articles.
 * A search box filters by substring (LIKE) over title + summary; tapping a row
 * re-opens that article in the reader (reconstructed from the stored snapshot,
 * since the live [Article] row may have been pruned).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: MainViewModel, onBack: () -> Unit) {
    val history by vm.history.collectAsState()
    val query by vm.historyQuery.collectAsState()
    val readers by vm.readers.collectAsState()
    val readerFont by vm.readerFont.collectAsState()
    val readerSize by vm.readerSize.collectAsState()

    // --- Reader overlay state (mirrors FeedScreen so taps open the article) ---
    var readerOpen by remember { mutableStateOf(false) }
    var readerArticle by remember { mutableStateOf<Article?>(null) }
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

    fun openArticle(a: Article, mode: ReaderMode) {
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
        if (mode == ReaderMode.READER) vm.requestArticle(a)
    }

    BackHandler(enabled = readerOpen) { readerOpen = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Read History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.setHistoryQuery(it) },
                label = { Text("Search read articles") },
                placeholder = { Text("Title or summary…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )

            if (history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank())
                            "No read articles yet. Open a story and it'll show up here."
                        else "No matches for \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)
                ) {
                    items(history, key = { it.id }) { h ->
                        HistoryRow(
                            entry = h,
                            onClick = { openArticle(historyToArticle(h), ReaderMode.READER) }
                        )
                    }
                }
            }
        }

        if (readerOpen) {
            val needRecovery = readerArticle?.let { a ->
                a.imageUrl.isNullOrBlank() || UrlUtils.isSignedImageUrl(a.imageUrl)
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
}

/** Reconstruct a minimal [Article] from a stored [ReadHistory] snapshot so the
 *  reader can open it even if the live feed row was pruned (offline-safe). */
private fun historyToArticle(h: ReadHistory): Article = Article(
    id = h.articleId,
    title = h.title,
    summary = h.summary,
    link = h.link,
    imageUrl = h.imageUrl,
    outletId = "",
    outletName = h.outletName,
    publishedAt = h.readAt,
    fetchedAt = h.readAt
)

@Composable
private fun HistoryRow(entry: ReadHistory, onClick: () -> Unit) {
    val formatter = remember {
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
    }
    val whenRead = remember(entry.readAt) {
        Instant.ofEpochMilli(entry.readAt)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            entry.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            entry.outletName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (entry.summary.isNotBlank()) {
            Text(
                entry.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
        Text(
            whenRead,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
