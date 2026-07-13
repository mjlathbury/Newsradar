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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.newsradar.app.CrashLogger
import com.newsradar.app.prefs.ReaderFont
import com.newsradar.app.prefs.ReaderSize

/** Which view the reader overlay is showing. */
enum class ReaderMode { WEB, READER }

/**
 * In-app reader overlay. The top bar (outlet + headline + Read-on-Web + close) is
 * always visible and never scrolls away. Closing returns to the feed. The device
 * Back button also closes it (handled by the caller via BackHandler).
 *
 * @param url          the article URL (for the WebView mode and Read-on-Web).
 * @param title        headline shown in the top bar.
 * @param outlet       source name shown in the top bar (uppercased).
 * @param mode         WEB shows the page in a WebView; READER shows clean text.
 * @param body         paragraph-structured reader text ("\n\n" blocks, "## "
 *                     subheadings, "• " bullets). Null while loading / on error.
 * @param loading      true while the reader body is being fetched.
 * @param error        true if extraction was too thin (offer Read on Web).
 * @param heroUrl      optional lead image shown at the top of the reader.
 * @param font         reader font family preference.
 * @param size         reader text size preference.
 * @param onReadWeb    open the article in a Chrome Custom Tab.
 * @param onClose      dismiss the overlay.
 */
@Composable
fun ReaderOverlay(
    url: String,
    title: String,
    outlet: String,
    mode: ReaderMode,
    body: String? = null,
    loading: Boolean = false,
    error: Boolean = false,
    heroUrl: String? = null,
    font: ReaderFont = ReaderFont.SERIF,
    size: ReaderSize = ReaderSize.M,
    onReadWeb: () -> Unit = {},
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
                // Read on Web (Chrome Custom Tab) — always available.
                IconButton(onClick = onReadWeb, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = "Read on web",
                        tint = MaterialTheme.colorScheme.onSurface
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
                ReaderMode.READER -> ArticleReader(
                    title = title,
                    body = body,
                    loading = loading,
                    error = error,
                    heroUrl = heroUrl,
                    font = font,
                    size = size,
                    onReadWeb = onReadWeb
                )
            }
        }
    }
}

/** Map the font preference to a Compose FontFamily. */
private fun ReaderFont.toFamily(): FontFamily = when (this) {
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.SANS -> FontFamily.SansSerif
    ReaderFont.MONO -> FontFamily.Monospace
    ReaderFont.SYSTEM -> FontFamily.Default
}

/** Base body text size (sp) for each reader size step. */
private fun ReaderSize.baseSp(): Int = when (this) {
    ReaderSize.S -> 16
    ReaderSize.M -> 18
    ReaderSize.L -> 21
    ReaderSize.XL -> 24
}

/**
 * Clean, selectable article reader. Renders a hero image (if any), the headline,
 * then paragraph blocks — "## " lines as subheadings, "• " lines as bullets,
 * everything else as body paragraphs. Line height is 1.5x for comfortable reading.
 * On error / empty, shows a prominent "Read on Web" button.
 */
@Composable
private fun ArticleReader(
    title: String,
    body: String?,
    loading: Boolean,
    error: Boolean,
    heroUrl: String?,
    font: ReaderFont,
    size: ReaderSize,
    onReadWeb: () -> Unit
) {
    val scroll = rememberScrollState()
    val family = font.toFamily()
    val base = size.baseSp()
    val bodySize: TextUnit = base.sp
    val bodyLineHeight: TextUnit = (base * 1.5f).sp
    val headingSize: TextUnit = (base + 4).sp
    val titleSize: TextUnit = (base + 8).sp

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (heroUrl != null) {
            var heroFailed by remember(heroUrl) { mutableStateOf(false) }
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(heroUrl)
                    .size(Size(1080, 1080))
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clip(RoundedCornerShape(10.dp)),
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) {
                        heroFailed = true
                    }
                }
            )
            if (heroFailed) {
                // Visible fallback so the user isn't staring at an empty grey box.
                Text(
                    "Image unavailable",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            title,
            fontFamily = family,
            fontSize = titleSize,
            lineHeight = (base * 1.4f + 8).sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))

            error || body.isNullOrBlank() -> {
                Text(
                    "Couldn't extract a clean read from this article.",
                    fontFamily = family,
                    fontSize = bodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onReadWeb) {
                    Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Read on Web")
                }
            }

            else -> SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (block in body.split("\n\n")) {
                        val t = block.trim()
                        if (t.isEmpty()) continue
                        when {
                            t.startsWith("## ") -> Text(
                                t.removePrefix("## "),
                                fontFamily = family,
                                fontSize = headingSize,
                                lineHeight = (headingSize.value * 1.4f).sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            t.startsWith("• ") -> Row {
                                Text(
                                    "•  ",
                                    fontFamily = family,
                                    fontSize = bodySize,
                                    lineHeight = bodyLineHeight,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    t.removePrefix("• "),
                                    fontFamily = family,
                                    fontSize = bodySize,
                                    lineHeight = bodyLineHeight,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            else -> Text(
                                t,
                                fontFamily = family,
                                fontSize = bodySize,
                                lineHeight = bodyLineHeight,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
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
            update = { webView ->
                // Reload if the composable is recomposed with a different URL.
                if (webView.url != url) {
                    runCatching { webView.loadUrl(url) }
                }
            },
            onRelease = { it.destroy() }
        )
        if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}
