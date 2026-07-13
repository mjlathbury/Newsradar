package com.newsradar.app.rss

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.newsradar.app.CrashLogger
import com.newsradar.app.util.UrlUtils
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
        // Guardian + generic page-tail / promo blocks that leak past <main>
        "[class*=most-viewed]", "[class*=most-read]", "[class*=mostViewed]",
        "[class*=mostRead]", "[class*=related-content]", "[class*=related-stories]",
        "[class*=footer]", "[class*=Footer]", "[id*=footer]",
        "[class*=privacy]", "[class*=PrivacyManager]", "[class*=cookie-notice]",
        "[class*=site-message]", "[class*=contributions]", "[class*=signin]",
        // BBC: related-links + topic chips + "Follow … news" CTA sit inside <article>
        "div[data-component=links-block]", "div[data-component=tag-list-block]",
        "div[data-component=follow-block]", "p[id^=follow-]",
        // Independent: taboola/teads in-article promos + newsletter aside
        "[class*=teads]", "aside.newsletter-component",
        "figure figcaption", ".caption", ".credit"
    )

    // Per-site container selectors tried first (fast, precise), then generic
    // fallbacks. Verified 2026-07-12 by an on-device-style headless-browser probe:
    //   - Daily Mail: body lives in div.article-text (-> [itemprop=articleBody]).
    //     There is NO <article> element on DM, so do NOT use it.
    //   - Mirror: body lives in the React class [class*=ArticleBody]
    //     ([class*=article-body] also matches); the bare <article> wrapper is too
    //     broad and pulls nav/ads, so it's a low-priority fallback only.
    //   - Guardian: body lives in [data-gu-name=article-body]; the bare <main>
    //     wrapper also contains the "Most viewed" + privacy footer tail, so it
    //     MUST be a low-priority fallback, not an early match.
    //   - Consent cookies / AMP are INERT for DM/Mirror (proven empirically).
    private val CONTAINER_SELECTORS = arrayOf(
        ".article-text",                 // Daily Mail (primary)
        "[itemprop=articleBody]",        // Daily Mail fallback
        "[class*=ArticleBody]",          // Mirror (React class) — primary
        "[class*=article-body]",         // Mirror generic
        "[data-gu-name=article-body]",      // Guardian article body (precise)
        "div.article__content__inner",     // Metro article body (precise; bare <article> leaks promos)
        ".article-body", ".story-body", ".article__body", ".js-article-body",
        ".content", ".post-content", ".entry-content",
        "main", "article"               // last resort (too broad — pulls tail)
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

                val body = extractFromDoc(doc, cleanLink)?.let { sanitise(it) }
                if (body != null && body.length >= 120) {
                    CrashLogger.article(cleanLink, body.length, ok = true)
                    body.take(8000)
                } else {
                    // HTTP path returned something too short to be the article — this
                    // is an expected transition. Try AMP (clean, JS-light) BEFORE the
                    // expensive/often-blocked WebView fallback.
                    val snippet = doc.body()?.text()?.take(200) ?: ""
                    CrashLogger.article(
                        cleanLink, body?.length ?: 0, ok = false,
                        snippet = "short-HTTP; $snippet"
                    )
                    tryAmp(cleanLink) ?: webViewFallback(cleanLink, context) ?: body
                }
            } catch (e: Exception) {
                CrashLogger.record(
                    RuntimeException("ArticleFetcher.fetchText failed for $cleanLink", e)
                )
                null
            }
        }

    /**
     * Lightweight hero-image resolver used by the reader when the feed-supplied
     * imageUrl is missing/blank (e.g. a stale cached row, or a feed that shipped no
     * image). Pulls the page's Open Graph image, which is reliable across all our
     * outlets. Returns null if it can't be found.
     */
    suspend fun fetchHeroImage(link: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val doc = Jsoup.connect(normaliseLink(link))
                .userAgent(MOBILE_UA)
                .header("Accept-Language", "en-GB,en;q=0.9")
                .followRedirects(true)
                .timeout(20000)
                .maxBodySize(0)
                .get()
            doc.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.takeIf { it.startsWith("http") }
                ?.let { cleanImageUrl(it) }
        }.getOrNull()
    }

    /**
     * AMP fallback. Google requires AMP pages to be fast and immediately readable,
     * so publishers usually strip the heavy consent/paywall JS from them — which
     * makes the body trivially extractable by our normal selectors + Readability.
     * Tries the two common AMP URL shapes; returns null if neither yields enough.
     */
    private suspend fun tryAmp(cleanLink: String): String? = withContext(Dispatchers.IO) {
        val candidates = listOf(
            cleanLink.trimEnd('/') + "/amp/",
            if (cleanLink.contains("?")) "$cleanLink&amp=1" else "$cleanLink?amp=1"
        )
        for (amp in candidates) {
            val recovered = runCatching {
                val doc = Jsoup.connect(amp)
                    .userAgent(MOBILE_UA)
                    .header("Accept-Language", "en-GB,en;q=0.9")
                    .followRedirects(true)
                    .timeout(20000)
                    .maxBodySize(0)
                    .get()
                val b = extractFromDoc(doc, amp)?.let { sanitise(it) }
                if (b != null && b.length >= 300) {
                    CrashLogger.article(amp, b.length, ok = true, snippet = "amp")
                    b.take(8000)
                } else null
            }.getOrNull()
            if (recovered != null) return@withContext recovered
        }
        null
    }

    /** Normalise a feed link: strip tracking query params
     *  geo-gated .co.uk host to the global .com (the .co.uk 301-redirects to .com
     *  anyway, and .com is the host that serves the full body on-device; .co.uk is
     *  the one that returns the bodyless/403 variant). */
    private fun normaliseLink(link: String): String = UrlUtils.normaliseLink(link)

    /**
     * Strip image-resizing query params so we request the full-size asset rather
     * than a thumbnail. Guardian's CDN (i.guim.co.uk) requires the FULL original
     * query string (the `s` param is a per-URL signature validated against the
     * entire query), so those URLs are kept exactly as served. Other outlets use
     * `?width=` / `auto=webp` style params we can safely drop.
     */
    private fun cleanImageUrl(url: String?): String? =
        if (url.isNullOrBlank()) null else UrlUtils.cleanImageUrl(url)

    /**
     * Extract readable article text from an already-fetched [Document], trying
     * per-site container selectors, then JSON-LD, then Readability4J.
     */
    private fun extractFromDoc(doc: Document, url: String): String? {
        // 1. Strip ad / promo / related / footer noise first.
        for (sel in JUNK_SELECTORS) doc.select(sel).remove()

        // 2. Per-site / generic container selectors — take the largest text block,
        //    remembering the ELEMENT (not just its flat text) so we can serialize it
        //    into paragraph-structured blocks for the reader view.
        var bestEl: org.jsoup.nodes.Element? = null
        var bestLen = 0
        for (sel in CONTAINER_SELECTORS) {
            for (el in doc.select(sel)) {
                val t = el.text().trim()
                if (t.length > bestLen) { bestLen = t.length; bestEl = el }
            }
            if (bestLen >= 600 && bestEl != null) return blocksFrom(bestEl!!) // good enough
        }
        if (bestEl != null && bestLen > 0) return blocksFrom(bestEl!!)

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

    /**
     * Serialize the chosen article container into clean paragraph blocks so the
     * reader can render real paragraph breaks and subheadings. Drops figures,
     * captions and pull-quotes. Emits "## " before subheadings and "• " before
     * list items; paragraphs are separated by a blank line. Applies the same
     * boilerplate line filters as [cleanBody].
     */
    private fun blocksFrom(container: org.jsoup.nodes.Element): String? {
        container.select("figure, figcaption, blockquote, aside").remove()
        // Drop dead/garbage cross-promo anchors (e.g. HuffPost's href="/v" WMO
        // link, or ?origin=*-recirc tracking anchors) so their link-text can't
        // leak into the prose as a plausible sentence.
        for (a in container.select("a")) {
            val href = a.attr("href").lowercase()
            if (href.endsWith("/v") || href.contains("origin=") && href.contains("recirc")) {
                a.remove()
            }
        }
        val out = StringBuilder()
        for (el in container.select("p, h2, h3, li")) {
            val t = el.text().trim()
            if (t.length < 2) continue
            if (isBoilerplate(t)) continue
            when (el.tagName()) {
                "h2", "h3" -> out.append("## ").append(t).append("\n\n")
                "li" -> out.append("• ").append(t).append("\n\n")
                else -> out.append(t).append("\n\n")
            }
        }
        val result = out.toString()
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        // If block extraction came up thin (e.g. a container with no <p> tags), fall
        // back to the flat text so we still return something readable.
        return result.takeIf { it.length >= 40 } ?: cleanBody(container.text())
    }

    /** True if a line is consent/cookie/copyright/footer/promo boilerplate that
     *  slips through. Covers Guardian's leaked page-tail fragments plus the
     *  per-outlet inline gotchas found by manual HTML analysis:
     *   - BBC: "Follow … news on" social CTA (a bare <p> inside <article>)
     *   - Metro: newsletter signup CTA ("Get analysis of the latest stories…")
     *   - Sky: "Read more:" / "Read more from Sky News:" inline cross-promo,
     *          "Why you can trust Sky News", app-install promo, "Related Topics"
     *   - HuffPost: literal "Advertisement" paragraph, "LOADINGERROR LOADING",
     *     doubleclick-block error text, share/correction footer lines. */
    private fun isBoilerplate(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("cookie") || l.contains("consent") ||
            l.contains("we and our partners") || l.contains("privacy policy") ||
            l.contains("privacy manager") || l.contains("do not sell") ||
            l.contains("california resident") || l.contains("most viewed") ||
            l.contains("most read") || l == "closer" || l == "tpc test" ||
            // BBC follow CTA
            l.contains("follow") && l.contains("news on") ||
            // Metro newsletter CTA (classless <p> inside body container)
            l.contains("start your day informed") ||
            l.contains("metro's news updates newsletter") ||
            l.contains("get breaking news alerts") || l.contains("sign up for all of the latest stories") ||
            // HuffPost "Related" cross-promo block + topic chips
            l == "related" || l.contains("related topics") ||
            l.contains("more in ") && l.contains("huffpost") ||
            l.startsWith("read this next") || l.startsWith("more from huffpost") ||
            // Sky inline cross-promo + trust/app promos
            l.startsWith("read more:") || l.startsWith("read more from sky news:") ||
            l.contains("why you can trust") || l.contains("install the sky news app") ||
            l.contains("be the first to get breaking news") ||
            l.contains("see more sky news in google") || l.contains("related topics") ||
            l.startsWith("image:") ||
            // HuffPost gotchas
            l == "advertisement" || l.contains("loadingerror loading") ||
            l.contains("doubleclick.net is blocked") ||
            l.contains("this page has been blocked by an extension") ||
            l.contains("err_blocked_by_client") || l.contains("go to homepage") ||
            l.contains("open comments") || l.startsWith("share on") ||
            l.contains("email this article") || l.contains("suggest a correction") ||
            l.contains("submit a tip") ||
            line.contains("©")
    }

    /** Light cleanup of extracted body text: collapse whitespace, drop obvious
     *  consent/boilerplate lines that slip through. */
    private fun cleanBody(raw: String): String? {
        return raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { isBoilerplate(it) }
            .joinToString("\n\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .takeIf { it.length >= 40 }
    }

    /**
     * Final sanitise pass on the already-assembled article text. Runs ONLY on the
     * joined String after Jsoup / Readability / WebView extraction — never re-parses
     * HTML and never touches the Document. Pure and bounded: a linear entity map
     * (no regex, no backtracking), one possessive-free tag-strip regex with a fixed
     * upper bound, then line/blank-line normalisation.
     */
    private fun sanitise(raw: String): String {
        // (a) Unescape the common HTML entities. Linear .replace — no regex.
        //     &amp; is done first so double-escaped forms (&amp;quot;) decode correctly.
        val unescaped = raw
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")
            .replace("&hellip;", "…")
            .replace("&rsquo;", "’")
            .replace("&lsquo;", "‘")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&euro;", "€")
            .replace("&pound;", "£")

        // (b) Strip any residual tag-like tokens with a safe, non-backtracking regex.
        //     < or </, an opening letter, up to 100 tag-name chars, optional spaces,
        //     optional self-close, then >. The {0,100} bound guarantees linear time.
        val noTags = unescaped.replace(
            Regex("</?[A-Za-z][A-Za-z0-9\\-_:]{0,100}\\s*/?>"), ""
        )

        // (d) Trim trailing whitespace on every line (tabs/spaces/CR, not newlines).
        val trimmed = noTags.lineSequence()
            .map { it.replace(Regex("[ \\t\\r]+$"), "") }
            .joinToString("\n")

        // (c) Collapse 3+ newlines down to a single blank line (two newlines).
        return trimmed.replace(Regex("\\n{3,}"), "\n\n").trim()
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
        }?.let { sanitise(it) }?.takeIf { it.length >= 40 }
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
