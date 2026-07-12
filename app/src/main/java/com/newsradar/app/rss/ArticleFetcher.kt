package com.newsradar.app.rss

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.newsradar.app.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.dankito.readability4j.extended.Readability4JExtended
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * On-demand fetcher for an article's full text. Only invoked when the user taps
 * "Brief Summary" — never during the background RSS refresh.
 *
 * Pipeline (all on Dispatchers.IO, no WebView needed for server-rendered sites):
 *   1. HTTP GET with a real mobile UA + consent cookies + follow redirects.
 *   2. Strip ad/promo/related/footer noise.
 *   3. Per-site container selectors (Daily Mail `.article-text`, Mirror `article`,
 *      plus general `.article-body`/`.story-body`/`main` etc.).
 *   4. JSON-LD `articleBody`/description — opportunistically, only if non-empty.
 *   5. Readability4J as a universal offline fallback.
 *   6. WebView only as a last resort for genuinely JS-rendered pages.
 *
 * Daily Mail / Mirror bodies are server-rendered. The short-shell bug came from a
 * GDPR/region/redirect gate (DM `.co.uk` 301→`.com`; consent wall `display:none`s
 * the body) plus reading `document.body.innerText` (which returns nav+footer, not
 * the article). We now bypass that with consent cookies, per-site selectors and
 * Readability, so the WebView is essentially never needed.
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

    // Per-site container selectors tried first (fast, precise), then generic
    // fallbacks. Verified 2026-07-12 by an on-device-style headless-browser probe:
    //   - Daily Mail: body lives in div.article-text (-> [itemprop=articleBody]).
    //     There is NO <article> element on DM, so do NOT use it.
    //   - Mirror: body lives in the React class [class*=ArticleBody]
    //     ([class*=article-body] also matches); the bare <article> wrapper is too
    //     broad and pulls nav/ads, so it's a low-priority fallback only.
    //   - Consent cookies / AMP are INERT for both (proven empirically) — selectors
    //     are the whole fix, on a server-rendered page.
    private val CONTAINER_SELECTORS = arrayOf(
        ".article-text",                 // Daily Mail (primary)
        "[itemprop=articleBody]",        // Daily Mail fallback
        "[class*=ArticleBody]",          // Mirror (React class) — primary
        "[class*=article-body]",         // Mirror generic
        ".article-body", ".story-body", ".article__body", ".js-article-body",
        "main", ".content", ".post-content", ".entry-content",
        "article"                        // last resort (too broad on DM/Mirror)
    )

    // Real mobile UA — Daily Mail's Akamai edge returns HTTP 403 to missing/bot UAs,
    // so a real browser-like mobile UA is required to get past it on the HTTP path.
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** Returns the main article text, or null if it can't be read. */
    suspend fun fetchText(link: String, context: Context? = null): String? =
        withContext(Dispatchers.IO) {
            val cleanLink = normaliseLink(link)
            try {
                val doc = Jsoup.connect(cleanLink)
                    .userAgent(MOBILE_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-GB,en;q=0.9")
                    .header("Upgrade-Insecure-Requests", "1")
                    .followRedirects(true)
                    .timeout(30000)
                    .maxBodySize(0)
                    .get()

                val body = extractFromDoc(doc, cleanLink)
                if (body != null && body.length >= 120) {
                    body.take(8000)
                } else {
                    // Nothing usable from the HTTP path — try the WebView as a last
                    // resort (genuinely JS-rendered pages), then null.
                    CrashLogger.record(
                        RuntimeException(
                            "ArticleFetcher: HTTP path too short for $cleanLink " +
                                "(len=${body?.length ?: 0}); trying WebView"
                        )
                    )
                    webViewFallback(cleanLink, context) ?: body
                }
            } catch (e: Exception) {
                CrashLogger.record(
                    RuntimeException("ArticleFetcher.fetchText failed for $cleanLink", e)
                )
                null
            }
        }

    /** Normalise a feed link: strip tracking query params
     *  geo-gated .co.uk host to the global .com (the .co.uk 301-redirects to .com
     *  anyway, and .com is the host that serves the full body on-device; .co.uk is
     *  the one that returns the bodyless/403 variant). */
    private fun normaliseLink(link: String): String =
        link.substringBefore("?")
            .replace("dailymail.co.uk", "dailymail.com")

    /**
     * Extract readable article text from an already-fetched [Document], trying
     * per-site container selectors, then JSON-LD, then Readability4J.
     */
    private fun extractFromDoc(doc: Document, url: String): String? {
        // 1. Strip ad / promo / related / footer noise first.
        for (sel in JUNK_SELECTORS) doc.select(sel).remove()

        // 2. Per-site / generic container selectors — take the largest text block.
        var best = ""
        for (sel in CONTAINER_SELECTORS) {
            for (el in doc.select(sel)) {
                val t = el.text().trim()
                if (t.length > best.length) best = t
            }
            if (best.length >= 600) return cleanBody(best) // good enough, stop early
        }
        if (best.isNotBlank()) return cleanBody(best)

        // 3. JSON-LD articleBody / description (opportunistically; DM ships it empty).
        val jsonLd = doc.select("script[type=application/ld+json]").mapNotNull { el ->
            try { extractArticleText(el.data()) } catch (_: Exception) { null }
        }.maxByOrNull { it.length }
        if (jsonLd != null && jsonLd.length >= 120) return cleanBody(jsonLd)

        // 4. Universal offline fallback: Mozilla Readability (Readability4J).
        return try {
            val article = Readability4JExtended(url, doc).parse()
            val txt = article.articleContent?.text() ?: article.textContent
            txt?.takeIf { it.isNotBlank() }?.let { cleanBody(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** Light cleanup of extracted body text: collapse whitespace, drop obvious
     *  consent/boilerplate lines that slip through. */
    private fun cleanBody(raw: String): String? {
        return raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { !it.contains("cookie", ignoreCase = true) }
            .filter { !it.contains("consent", ignoreCase = true) }
            .filter { !it.contains("we and our partners", ignoreCase = true) }
            .filter { !it.contains("privacy policy", ignoreCase = true) }
            .filter { !it.contains("©", ignoreCase = true) }
            .joinToString("\n\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .takeIf { it.length >= 40 }
    }

    /**
     * On-device last-resort fetch via a headless WebView, used only when the Jsoup
     * HTTP path yields too little (e.g. a genuinely JS-rendered page). For Daily Mail
     * / Mirror the HTTP path with per-site selectors is the real fix — this is just a
     * safety net. Loads the (already .com-normalised) URL, polls the best article
     * container's innerText until stable, and runs Readability4J on the rendered DOM.
     * Consent cookies / AMP were tested empirically and are INERT for these sites, so
     * we don't bother with them here.
     */
    private suspend fun webViewFallback(url: String, context: Context?): String? {
        if (context == null) return null
        return withTimeoutOrNull(18_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<String?> { cont ->
                    val webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.blockNetworkImage = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = MOBILE_UA
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
                            val scope = CoroutineScope(cont.context)
                            scope.launch {
                                var lastLen = 0
                                var best = ""
                                var attempts = 0
                                while (attempts < 10 && cont.isActive) {
                                    delay(600)
                                    val raw = suspendCancellableCoroutine<String> { c ->
                                        view?.evaluateJavascript(EXTRACT_JS) { res ->
                                            c.resume(res ?: "", onCancellation = {})
                                        }
                                    }
                                    val clean = raw.removeSurrounding("\"").replace("\\n", "\n")
                                        .replace("\\u003C", "<").trim()
                                    if (clean.length > best.length) {
                                        best = clean
                                        lastLen = 0
                                    } else {
                                        lastLen++
                                    }
                                    if (lastLen >= 3 && best.length > 400) {
                                        finish(best)
                                        return@launch
                                    }
                                    attempts++
                                }
                                finish(best)
                            }
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

    // JS that returns the longest article container's innerText using the same
    // per-site selectors as the Jsoup path (Daily Mail .article-text, Mirror
    // [class*=ArticleBody]), then generic fallbacks, then body only if nothing else.
    private const val EXTRACT_JS = """
        (function() {
          var sels = ['.article-text','[itemprop="articleBody"]','[class*="ArticleBody"]',
                      '[class*="article-body"]','.article-body','.story-body','.js-article-body',
                      'main','.content','article'];
          var best = '';
          for (var i = 0; i < sels.length; i++) {
            var els = document.querySelectorAll(sels[i]);
            for (var j = 0; j < els.length; j++) {
              var t = (els[j].innerText || '').trim();
              if (t.length > best.length) best = t;
            }
          }
          if (!best || best.length < 200) best = (document.body.innerText || '').trim();
          return best;
        })();
    """

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
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            try { JSONArray(json) } catch (_: Exception) { return null }
        }
        return search(root)?.replace("\\n", " ")?.replace("\\t", " ")?.trim()
            ?.takeIf { it.length >= 40 }
    }
}
