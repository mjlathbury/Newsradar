package com.newsradar.app.ui

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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
 * always visible — the ✕ (close) button top-right never scrolls away. Closing
 * returns to the feed. The device Back button also closes it (handled by the
 * caller via BackHandler, or here if used standalone).
 *
 * @param url        the URL to load (article link or external video page).
 * @param title      headline / programme name shown in the top bar.
 * @param outlet     source name shown in the top bar (uppercased).
 * @param mode       WEB shows the page in a WebView; SUMMARY shows the on-device
 *                   60s overview (passed in [summaryText]).
 * @param summaryText pre-fetched summary text (null while loading / on error).
 * @param summaryLoading true while the summary is being generated.
 * @param summaryError true if summary generation failed.
 * @param onClose     dismissed the overlay.
 */
@Composable
fun ReaderOverlay(
    url: String,
    title: String,
    outlet: String,
    mode: ReaderMode,
    videoMode: Boolean = false,
    summaryText: String? = null,
    summaryLoading: Boolean = false,
    summaryError: Boolean = false,
    onClose: () -> Unit
) {
    // Mute state for video windows (starts muted so autoplay is allowed).
    var muted by remember { mutableStateOf(true) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

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
                if (videoMode) {
                    // Flashing amber button while muted; green when audio is on.
                    val flash = rememberInfiniteTransition()
                    val alpha by flash.animateFloat(
                        initialValue = 1f, targetValue = 0.35f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse)
                    )
                    IconButton(
                        onClick = {
                            // For YouTube embeds, muting requires reloading the iframe
                            // with the mute query param — evaluateJavascript can't reach
                            // the cross-origin player's <video>. Reload with flipped param.
                            muted = !muted
                            val sep = if (url.contains("?")) "&" else "?"
                            webViewRef.value?.loadUrl("$url${sep}mute=${if (muted) 1 else 0}")
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (muted) MaterialTheme.colorScheme.errorContainer.copy(alpha = alpha)
                                else MaterialTheme.colorScheme.primaryContainer
                            )
                    ) {
                        Icon(
                            if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = if (muted) "Unmute" else "Mute",
                            tint = if (muted) MaterialTheme.colorScheme.onErrorContainer
                                   else MaterialTheme.colorScheme.primary
                        )
                    }
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
                ReaderMode.WEB -> WebReader(url, videoMode, webViewRef)
                ReaderMode.SUMMARY -> SummaryReader(
                    summaryText = summaryText,
                    loading = summaryLoading,
                    error = summaryError
                )
            }
        }
    }
}

/** WebView that loads the URL with a progress spinner. For [videoMode] it allows
 *  autoplay without a user gesture (so muted autoplay works) and exposes the
 *  WebView via [webViewRef] so the mute toggle can adjust volume. */
@Composable
private fun WebReader(
    url: String,
    videoMode: Boolean = false,
    webViewRef: androidx.compose.runtime.MutableState<WebView?>? = null
) {
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
                    webViewRef?.value = this
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
                        // Let video autoplay (muted) without a tap.
                        mediaPlaybackRequiresUserGesture = !videoMode
                    }
                    try {
                        // YouTube embeds: append autoplay + initial mute so the stream
                        // starts playing silently (sound-on autoplay is blocked).
                        val sep = if (url.contains("?")) "&" else "?"
                        val finalUrl = if (videoMode) "$url${sep}autoplay=1&mute=1" else url
                        loadUrl(finalUrl)
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
