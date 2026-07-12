package com.newsradar.app.ui

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Which view the reader overlay is showing. */
enum class ReaderMode { WEB, SUMMARY }

/**
 * In-app reader overlay. The top bar (outlet + headline + close) is always
 * visible — the ✕ (close) button top-right never scrolls away. Closing returns
 * to the feed. The device Back button also closes it (handled by the caller via
 * BackHandler).
 *
 * @param url           the article URL to load in the WebView.
 * @param title         headline shown in the top bar.
 * @param outlet        source name shown in the top bar (uppercased).
 * @param mode          WEB shows the page in a WebView; SUMMARY shows the on-device
 *                      60s overview (passed in [summaryText]).
 * @param summaryText   pre-fetched summary text (null while loading / on error).
 * @param summaryLoading true while the summary is being generated.
 * @param summaryError  true if summary generation failed.
 * @param onClose       dismissed the overlay.
 */
@Composable
fun ReaderOverlay(
    url: String,
    title: String,
    outlet: String,
    mode: ReaderMode,
    summaryText: String? = null,
    summaryLoading: Boolean = false,
    summaryError: Boolean = false,
    onClose: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // ---- Sticky top bar (always visible) ----
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        outlet.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close reader",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }

        // ---- Body (below the sticky bar) ----
        Box(Modifier.fillMaxSize().padding(top = 57.dp)) {
            when (mode) {
                ReaderMode.WEB -> WebReader(url)
                ReaderMode.SUMMARY -> SummaryReader(
                    summaryText = summaryText,
                    loading = summaryLoading,
                    error = summaryError
                )
            }
        }
    }
}

/** WebView that loads the article URL with a progress spinner. Navigation stays
 *  inside the overlay (no external browser). */
@Composable
private fun WebReader(url: String) {
    var loading by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                        }
                        // Keep navigation inside the overlay (don't spawn external browser).
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = false
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                    }
                    try {
                        loadUrl(url)
                    } catch (e: Exception) {
                        // A failed load must not crash the app — leave the spinner off.
                        loading = false
                    }
                }
            },
            onRelease = { it.destroy() }
        )
        if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/** Scrollable brief summary text. */
@Composable
private fun SummaryReader(
    summaryText: String?,
    loading: Boolean,
    error: Boolean
) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Brief summary",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.tertiary
        )
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            error -> Text(
                "Couldn't build the summary from this article. Close and try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            summaryText != null -> Text(
                summaryText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            else -> Text(
                "Summary unavailable for this article.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
