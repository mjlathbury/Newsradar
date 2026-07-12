package com.newsradar.app.rss

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * On-demand fetcher for an article's full text. Only invoked when the user taps
 * "Brief Summary" — never during the background RSS refresh. Uses jsoup to strip
 * the HTML down to readable paragraphs, removing ads, related-article modules,
 * newsletter/promo blocks and footers so they never end up in the summary.
 */
object ArticleFetcher {

    // Selectors for junk we explicitly drop before extracting text.
    private val JUNK_SELECTORS = arrayOf(
        "script", "style", "noscript", "iframe", "aside",
        "[class*=ad]", "[class*=ads]", "[class*=advert]", "[id*=ad-]", "[id*=ads]",
        "[class*=promo]", "[class*=newsletter]", "[class*=subscribe]",
        "[class*=related]", "[class*=recommended]", "[class*=more-story]",
        "[class*=story-list]", "[class*=carousel]", "[class*=gallery]",
        "[class*=social]", "[class*=share]", "[class*=meta]", "[class*=byline]",
        "[class*=cookie]", "[class*=banner]", "[class*=newsletter-signup]",
        "[class*=taboola]", "[class*=outbrain]", "[class*=zn-player]",
        "figure figcaption", ".caption", ".credit"
    )

    /** Returns the main article text, or null if it can't be read.
     *  @param context needed only as a last-resort fallback: if Jsoup + JSON-LD
     *  yield too little text (JS-rendered shells like Daily Mail / Mirror), we
     *  render the page in a headless WebView and read document.body.innerText. */
    suspend fun fetchText(link: String, context: Context? = null): String? = withContext(Dispatchers.IO) {
        // Clean the link first: Daily Mail RSS serves tracking-wrapped URLs that
        // 410 (Gone). Strip query params and normalise to the .co.uk article host.
        val cleanLink = link
            .substringBefore("?")
            .replace("dailymail.com", "dailymail.co.uk")
            .replace("https://www.dailymail.co.uk/news/article-1490", "https://www.dailymail.co.uk/news")
        try {
            val doc = Jsoup.connect(cleanLink)
                .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-GB,en;q=0.9")
                // Send consent-acceptance cookies so EU/UK GDPR walls serve the real
                // article instead of a 302 to a consent interstitial (which would
                // otherwise be scraped as boilerplate).
                .header("Cookie", "CONSENT=YES+cb; SOCS=CAESNQgDEIT; cookieConsent=accepted; gdpr_consent=1")
                .followRedirects(true)
                .timeout(30000)
                .maxBodySize(0)
                .get()

            // Strip ad / promo / related / footer noise first.
            for (sel in JUNK_SELECTORS) doc.select(sel).remove()

            // Prefer a real article container; fall back to <p> across the page, then
            // to block-level text inside article containers (live blogs often render
            // entries in <div>s rather than <p>), then to JSON-LD articleBody (many
            // JS-rendered sites embed the full text there), then to the meta desc.
            val candidates = doc.select(
                "article p, .article-body p, .story-body p, .article__body p, " +
                    ".js-article-body p, main p, .content p, p, " +
                    "article div, .article-body div, .story-body div, .js-article-body div, main div"
            )
            // JSON-LD blocks often hold the full article body on JS-rendered pages
            // (Daily Mail / Mirror mobile shells return almost no <p> text). Parse
            // the JSON properly (not via regex) and search recursively for the
            // articleBody / description field — it may be nested in @graph.
            val jsonLd = doc.select("script[type=application/ld+json]").mapNotNull { el ->
                try {
                    extractArticleText(el.`data`())
                } catch (_: Exception) { null }
            }
            val metaDesc = doc.select("meta[property=og:description], meta[name=description]")
                .firstOrNull()?.attr("content")?.takeIf { it.isNotBlank() }
            val text = (candidates.map { it.text().trim() } + jsonLd + listOfNotNull(metaDesc))
                // drop nav/boilerplate one-liners; allow longer blocks (up to 600)
                // so full sentences aren't discarded — keeps summaries a proper read.
                .filter { it.length in 40..600 }
                .filter { !it.contains("Cookie Policy", ignoreCase = true) }
                .filter { !it.contains("Subscribe to", ignoreCase = true) }
                .filter { !it.contains("Sign up to", ignoreCase = true) }
                .filter { !it.contains("More from", ignoreCase = true) }
                .filter { !it.contains("©", ignoreCase = true) }
                .filter { !it.contains("continue reading", ignoreCase = true) }
                .filter { !it.contains("click to continue", ignoreCase = true) }
                // Consent / cookie walls (Google JCP, "Exco Player", etc.) — these are
                // scraped when the page 302s to a GDPR interstitial instead of the
                // article. Drop them so they never end up in the summary.
                .filter { !it.contains("cookie", ignoreCase = true) }
                .filter { !it.contains("consent", ignoreCase = true) }
                .filter { !it.contains("privacy policy", ignoreCase = true) }
                .filter { !it.contains("privacy notice", ignoreCase = true) }
                .filter { !it.contains("award-winning daily news", ignoreCase = true) }
                .filter { !it.contains("i would like to be emailed", ignoreCase = true) }
                .filter { !it.contains("offers", ignoreCase = true) }
                .filter { !it.contains("event and updates", ignoreCase = true) }
                .filter { !it.contains("newsletter", ignoreCase = true) }
                .filter { !it.contains("sign in", ignoreCase = true) }
                .filter { !it.contains("Allow and Continue", ignoreCase = true) }
                .filter { !it.contains("Custom Search", ignoreCase = true) }
                .filter { !it.contains("provided by", ignoreCase = true) }
                .distinct()
                .joinToString("\n\n")
            // If what we got back is essentially just a consent wall (no real article
            // body), treat the fetch as failed so we fall back to the RSS blurb, and
            // log it so the on-device failure is visible in Settings → Debug.
            val isConsentWall = text.contains("we need your consent", ignoreCase = true)
                || text.contains("may use cookies", ignoreCase = true)
                || text.contains("your consent", ignoreCase = true)
            if (text.isBlank() || isConsentWall) {
                // Dump a snippet of what the device actually received so we can see
                // whether it's a consent shell, a JS page, or a redirect stub.
                val snippet = doc.body().text().take(200).replace("\n", " ")
                com.newsradar.app.CrashLogger.record(
                    RuntimeException(
                        "ArticleFetcher: no body for $cleanLink (len=${text.length}). " +
                            "page-snippet=[$snippet]"
                    )
                )
                // Last resort: render in a headless WebView (handles JS shells).
                webViewFallback(cleanLink, context)
            } else if (text.length < 300) {
                // Too short to be the real article (a stub / nav shell). Try the
                // WebView before falling back to the RSS blurb.
                com.newsradar.app.CrashLogger.record(
                    RuntimeException("ArticleFetcher: suspiciously short body for $cleanLink (len=${text.length})")
                )
                webViewFallback(cleanLink, context) ?: text.take(8000)
            } else {
                text.take(8000)
            }
        } catch (e: Exception) {
            // Surface the real failure (e.g. 410 on Daily Mail tracking links, SSL,
            // timeout) so it can be diagnosed from Settings → Debug instead of failing
            // silently and falling back to a too-short blurb.
            com.newsradar.app.CrashLogger.record(
                RuntimeException("ArticleFetcher.fetchText failed for $cleanLink", e)
            )
            null
        }
    }

    /**
     * Last-resort fetch: render the page in a headless WebView and read
     * document.body.innerText. Needed for JS-rendered shells (Daily Mail / Mirror)
     * where Jsoup + JSON-LD return almost nothing. WebView must run on the Main
     * thread, so we hop dispatchers and wrap it in a cancellable, timeout-guarded
     * coroutine. Returns cleaned text, or null on any failure/timeout.
     */
    private suspend fun webViewFallback(url: String, context: Context?): String? {
        if (context == null) return null
        return withTimeoutOrNull(14_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<String?> { cont ->
                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.blockNetworkImage = true
                        settings.domStorageEnabled = true
                    }
                    var done = false
                    fun finish(text: String?) {
                        if (done) return
                        done = true
                        if (cont.isActive) cont.resume(text, onCancellation = {})
                        webView.destroy()
                    }
                    cont.invokeOnCancellation { webView.destroy() }
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            super.onPageFinished(view, loadedUrl)
                            // Some outlets (Daily Mail) inject the article body via
                            // deferred JS *after* onPageFinished, so capturing
                            // innerText immediately yields only the nav shell.
                            // Wait a beat for the real content to render, then grab it.
                            view?.postDelayed({
                                view.evaluateJavascript("document.body.innerText") { result ->
                                    val clean = result?.removeSurrounding("\"")
                                        ?.replace("\\n", "\n")
                                        ?.replace("\\u003C", "<")
                                        ?.trim()
                                    finish(clean)
                                }
                            }, 2500)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            finish(null)
                        }
                    }
                    webView.loadUrl(url)
                }
            }
        }?.takeIf { it.length >= 40 }
    }

    /**
     * Pull the article text out of a JSON-LD block. Searches recursively for
     * `articleBody` (preferred) or `description`, since the field may be nested
     * inside an `@graph` array rather than at the top level. A depth cap prevents
     * StackOverflow from self-referential ad-tech JSON.
     */
    private fun extractArticleText(json: String, depth: Int = 0): String? {
        if (depth > 12) return null
        fun search(node: Any?): String? {
            return when (node) {
                is JSONObject -> {
                    node.optString("articleBody", "").takeIf { it.isNotBlank() }
                        ?: node.optString("description", "").takeIf { it.isNotBlank() }
                        ?: node.optString("article", "").takeIf { it.isNotBlank() }
                        ?: node.optJSONArray("@graph")?.let { g ->
                            (0 until g.length()).firstNotNullOfOrNull { search(g.opt(it)) }
                        }
                        ?: node.keys().asSequence().firstNotNullOfOrNull { search(node.opt(it)) }
                }
                is JSONArray -> {
                    (0 until node.length()).firstNotNullOfOrNull { search(node.opt(it)) }
                }
                else -> null
            }
        }
        // The script body may be a top-level object OR a JSONArray (e.g. a bare
        // @graph list). Handle both so we don't discard a valid block.
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            try { JSONArray(json) } catch (_: Exception) { return null }
        }
        return search(root)?.replace("\\n", " ")?.replace("\\t", " ")?.trim()
            ?.takeIf { it.length >= 40 }
    }
}
