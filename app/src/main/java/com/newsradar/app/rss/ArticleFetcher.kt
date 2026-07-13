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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

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
        // BBC: trailing "Get in touch" / contact-promo block that leaks past <article>
        "[data-component=contact-block]", "[class*=get-in-touch]", "[class*=getintouch]",
        "[id*=contact-block]", "[class*=share-block]",
        // Independent: taboola/teads in-article promos + newsletter aside
        "[class*=teads]", "aside.newsletter-component",
        "figure figcaption", ".caption", ".credit",
        // Daily Mail: related-stories / "more stories" link lists that otherwise
        // leak into .article-text as a wall of cross-promo links.
        "[class*=article-related]", "[class*=related-articles]", "[class*=story-list]",
        "[class*=mol-related]", "[class*=linkList]", "[class*=more-stories]",
        "[class*=read-more]", "[id*=related]",
        // HuffPost: recirc / "Read this next" / section cross-promo blocks whose
        // anchor text reads like prose and leaks into the body.
        "[class*=recirc]", "[class*=entry__embed]", "[class*=card__headline]",
        "[class*=related-posts]", "[class*=stream-item]", "[class*=vertical-related]",
        // Cluster C (broadcast / regional / paywall) — Sky, Scotsman, FT, Telegraph:
        // precise junk blocks that leak past <article> (newsletter forms, comments,
        // related/recirc, trust/share CTAs). Verified 2026-07-13 by subagent probe.
        "[class*=article-author-job-title]", "[class*=newsletter-heading]",
        "[class*=newsletter-message]", "[class*=socials-comments]",
        "[class*=commentButton]", "[class*=Viafoura]", "[data-taboola-placement]",
        "[class*=sdc-related]", "[class*=sdc-social]", "[class*=sdc-trust]",
        "[class*=sdc-share]", "[class*=more-on]",
        "[class*=in-article-sign-up]", "[class*=myFT]", "[class*=article__more]",
        "[class*=share__list]", "[class*=follow]", "[id*=comments]",
        "[class*=article__share]", "[class*=article__comments]",
        "[class*=more-from]", "[class*=see-also]",
        // Independent / Wales Online / Metro: bookmark + "Comments for …" + save CTAs
        // that leak into the body tail as prose-like lines.
        "[class*=bookmark]", "[class*=save-story]", "[class*=story-saved]",
        "[class*=my-bookmarks]", "[class*=comments-wrapper]", "[class*=comments-block]",
        "[class*=share-this]", "[class*=article-tools]", "[class*=social-share]",
        "[class*=notify-me]", "[class*=sign-up-cta]"
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
        "[class*=sdc-article-body]",          // Sky (precise)
        "[class*=article__content]",          // FT (precise)
        "[class*=article-body-container]",     // Telegraph (precise)
        "[class*=js-article-body]",            // Telegraph
        ".articleBody", "#articleBody",        // Daily Express (precise)
        "[class*=articleBody]",                // Daily Express — camelCase (hyphenated selector misses it)
        "[class*=entry__body]", "[class*=card__body]",  // HuffPost (precise)
        "main", "article"               // last resort (too broad — pulls tail)
    )

    // Real mobile UA — Daily Mail's Akamai edge returns HTTP 403 to missing/bot UAs,
    // so a real browser-like mobile UA is required to get past it on the HTTP path.
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // OkHttp client for article fetches — shares the browser UA + connection pool
    // approach that defeats 403s on RSS (Sky hard-blocks Jsoup's own HttpClient).
    private val HTTP_CLIENT = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Fetch a URL over OkHttp (browser UA + consent cookie) and parse with Jsoup.
     *  Returns null on any non-success so callers can fall back. Full browser
     *  header set defeats WAF fingerprinting (Express/HuffPost were returning
     *  consent shells to a bare request), but some outlets (Independent) serve a
     *  truncated shell when they see Sec-Fetch / DNT headers — so if the primary
     *  fetch yields a suspiciously short body we retry with a minimal header set
     *  and keep whichever document has more text. */
    private fun fetchDoc(url: String): Document? {
        val primary = buildRequest(url, fullHeaders = true)
        val doc1 = tryParse(primary) ?: return null
        val len1 = doc1.body()?.text()?.length ?: 0
        if (len1 >= 600) return doc1
        // Short body → likely a consent/wall shell. Retry with minimal headers.
        val doc2 = tryParse(buildRequest(url, fullHeaders = false))
        val len2 = doc2?.body()?.text()?.length ?: 0
        return if (len2 > len1) doc2 ?: doc1 else doc1
    }

    private fun buildRequest(url: String, fullHeaders: Boolean) = Request.Builder()
        .url(url)
        .header("User-Agent", MOBILE_UA)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-GB,en;q=0.9")
        .header("Upgrade-Insecure-Requests", "1")
        .header("Referer", "https://www.google.com/")
        .header("Cookie", "cookie_consent=accepted; gdpr=0; cmp_consented=true; notice_preferences=2:; euconsent-v2=CPXxR4A_CPXxR4A")
        .apply {
            if (fullHeaders) {
                header("Connection", "keep-alive")
                header("Sec-Fetch-Dest", "document")
                header("Sec-Fetch-Mode", "navigate")
                header("Sec-Fetch-Site", "cross-site")
                header("Sec-Fetch-User", "?1")
                header("DNT", "1")
                header("Cache-Control", "max-age=0")
            }
        }
        .build()

    private fun tryParse(req: Request): Document? {
        val resp = HTTP_CLIENT.newCall(req).execute()
        if (!resp.isSuccessful) { resp.close(); return null }
        return Jsoup.parse(resp.body?.string().orEmpty())
    }

    /** Returns the main article text, or null if it can't be read. */
    suspend fun fetchText(link: String, context: Context? = null): String? =
        withContext(Dispatchers.IO) {
            val cleanLink = normaliseLink(link)
            try {
                val doc = fetchDoc(cleanLink) ?: run {
                    CrashLogger.record(RuntimeException("ArticleFetcher: fetch failed (null doc) for $cleanLink"))
                    return@withContext null
                }

                val extracted = extractFromDoc(doc, cleanLink)
                val body = extracted?.let { sanitise(it) }
                CrashLogger.diagnostic(
                    "FETCH_BODY host=${runCatching { java.net.URL(cleanLink).host }.getOrDefault("?")} " +
                        "httpBodyLen=${doc.body()?.text()?.length ?: 0} " +
                        "containerLen=${extracted?.length ?: 0}"
                )
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
                    // TEMP DIAG: when HTTP body is short, log the best container
                    // candidates so we can target the real mobile markup from a
                    // device capture (datacenter IPs get consent-walled shells).
                    runCatching {
                        val cands = doc.select("div, article, main, section")
                            .map { el -> Pair(el, el.text().length) }
                            .filter { it.second >= 200 }
                            .sortedByDescending { it.second }
                            .take(6)
                        val report = cands.joinToString(" | ") { (el, len) ->
                            val cls = (el.className()?.take(60) ?: el.tagName())
                            "${el.tagName()}[$cls]=$len"
                        }
                        CrashLogger.diagnostic(
                            "CONTAINER_CANDIDATES host=${runCatching { java.net.URL(cleanLink).host }.getOrDefault("?")} " +
                            "chosen=${extracted?.length ?: 0} top=[$report]"
                        )
                    }
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
            val doc = fetchDoc(normaliseLink(link)) ?: return@runCatching null
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
            if (cleanLink.contains("?")) "$cleanLink&amp=1" else "$cleanLink?amp=1",
            cleanLink.trimEnd('/') + "/amp/"
        )
        for (amp in candidates) {
            val recovered = runCatching {
                val doc = fetchDoc(amp) ?: return@runCatching null
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
     * True if a candidate container is mostly navigation/related links rather than
     * prose — e.g. Daily Mail's related-stories `div.article-body` sibling that a
     * broad selector grabs when the real body (.article-text) is absent (consent-
     * walled on-device). Such a node has few <p>/<li> blocks and a high fraction of
     * its text living inside <a> elements. Excluding it lets the REAL prose body win.
     */
    private fun isLinkHeavy(el: org.jsoup.nodes.Element): Boolean {
        val blockCount = el.select("p, li").size
        if (blockCount < 2) return true
        val total = el.text().length
        if (total == 0) return true
        val linkText = el.select("a").sumOf { it.text().length }
        return (linkText.toDouble() / total) > 0.5
    }

    /**
     * Extract readable article text from an already-fetched [Document], trying
     * per-site container selectors, then JSON-LD, then Readability4J.
     */
    private fun extractFromDoc(doc: Document, url: String): String? {
        // 1. Strip ad / promo / related / footer noise first.
        for (sel in JUNK_SELECTORS) doc.select(sel).remove()

        // 2. Per-site / generic container selectors — take the largest text block,
        //    remembering the ELEMENT (not just its flat text) so we can serialize it
        //    into paragraph-structured blocks for the reader view. Skip link-heavy
        //    nodes (related/recirc walls) so the real prose body wins over a links div.
        var bestEl: org.jsoup.nodes.Element? = null
        var bestLen = 0
        for (sel in CONTAINER_SELECTORS) {
            for (el in doc.select(sel)) {
                val t = el.text().trim()
                if (t.length > bestLen && !isLinkHeavy(el)) { bestLen = t.length; bestEl = el }
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
        // Drop cross-promo / navigation anchors so their headline text can't leak
        // into the prose as a plausible paragraph. This catches related-story
        // promos (e.g. BBC's "<a href="/news/articles/..."><p>Headline</p></a>")
        // and other internal recirc links across all outlets. External http(s)
        // citation links keep their text; internal "/path" nav links are removed.
        for (a in container.select("a")) {
            val href = a.attr("href").lowercase()
            val atext = a.text().lowercase()
            if (href.startsWith("/") ||
                href.contains("origin=") && href.contains("recirc") ||
                href.endsWith("/v") ||
                href.contains("utm_") && href.contains("recirc") ||
                // promo / "more stories" anchors whose link text gives them away
                atext.startsWith("arrow") || atext.startsWith("more ") ||
                atext.contains("more stories") || atext.contains("check our news page") ||
                atext.contains("load more") || atext.contains("see more")
            ) {
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
            l.contains("most read") || l.contains("most viewed") || l == "closer" || l == "tpc test" ||
            // BBC follow CTA + "get in touch" contact block tail
            l.contains("follow") && l.contains("news on") ||
            l.contains("get in touch") || l.contains("contact the bbc") || l.contains("whatsapp") ||
            l.contains("send your story") || l.contains("share with bbc") ||
            // Metro newsletter CTA (classless <p> inside body container)
            l.contains("start your day informed") ||
            l.contains("metro's news updates newsletter") ||
            l.contains("get breaking news alerts") || l.contains("sign up for all of the latest stories") ||
            // HuffPost "Related" cross-promo block + topic chips
            l == "related" || l.contains("related topics") ||
            l.contains("more in ") && l.contains("huffpost") ||
            l.startsWith("read this next") || l.startsWith("more from huffpost") ||
            l == "more on this story" || l == "related content" || l == "related stories" ||
            // Sky inline cross-promo + trust/app promos + image captions
            l.startsWith("read more:") || l.startsWith("read more from sky news:") ||
            l.contains("why you can trust") || l.contains("install the sky news app") ||
            l.contains("be the first to get breaking news") ||
            l.contains("see more sky news in google") || l.contains("related topics") ||
            l.contains("image:") || l.contains("pic:") || l.contains("video:") ||
            l.contains("istock") || l.contains("getty images") || l.contains("rex features") ||
            // Sky dateline + byline line, e.g. "Monday 13 July 2026 12:16, UK" /
            // "...UKImage:" (byline merges with the image caption, no separator).
            l.matches(Regex(".*\\d{1,2}\\s+[a-z]+\\s+\\d{4}.*")) && l.contains(", uk") ||
            l.contains("ukimage:") || l.contains("uk image:") ||
            // HuffPost gotchas
            l == "advertisement" || l.contains("loadingerror loading") ||
            l.contains("doubleclick.net is blocked") ||
            l.contains("this page has been blocked by an extension") ||
            l.contains("err_blocked_by_client") || l.contains("go to homepage") ||
            l.contains("open comments") || l.startsWith("share on") ||
            l.contains("email this article") || l.contains("suggest a correction") ||
            l.contains("submit a tip") ||
            // Cluster C (Sky/Scotsman/FT/Telegraph) — newsletter + recirc tails
            l.contains("sign up to our") || l.contains("thank you for signing up") ||
            l.contains("want to join the conversation") || l.contains("westminster correspondent") ||
            l.contains("protected by recaptcha") || l.contains("more on ") ||
            l.contains("stay informed with free updates") || l.contains("simply sign up to the") ||
            l.contains("myft digest") || l.contains("reuse this content") || l.contains("add to myft") ||
            l.contains("follow the topics") || l.contains("latest on ") ||
            l.contains("more from the telegraph") || l.contains("see also") ||
            l.contains("sign up to our newsletters") || l.contains("get the daily telegraph newsletter") ||
            // Independent / Wales Online / Metro bookmark + "Comments for …" tails
            l == "bookmark" || l == "story saved" || l.contains("my bookmarks") ||
            l.startsWith("comments for ") || l.contains("story saved") ||
            (l.contains("not now") && l.contains("yes please")) ||
            l.contains("stay up to date with notifications") ||
            l.contains("sign up to our newsletter") || l.contains("join the conversation") ||
            l.contains("you can find this story in") || l.contains("or by navigating to the user icon") ||
            // Independent: bookmark popover + commenting-forum tails
            l == "bookmark popover" || l == "removed from bookmarks" ||
            l.startsWith("comments go to comments") || l.contains("go to comments") ||
            l.contains("join our commenting forum") || l.contains("thought-provoking conversations") ||
            l == "more about" || l.startsWith("## more about") ||
            // Metro / Wales Online / Scotsman tail promos + live-blog artifacts
            l.contains("sign up now") || l.startsWith("arrow more") ||
            l.contains("for more stories like this") || l.contains("contribute to the live blog") ||
            l.contains("click here to read more") || l == "comments" || l.startsWith("## comments") ||
            l.contains("read more from") || l.contains("more stories like this") ||
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
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .takeIf { it.length >= 40 }
    }

    /** Test-only wrapper so unit tests can exercise the boilerplate filter. */
    @kotlin.jvm.JvmStatic
    fun cleanBodyForTest(raw: String): String? = cleanBody(raw)

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
                            // Accept any JS-gated consent wall (e.g. Mirror/Reach plc)
                            // so the real article body is revealed in the DOM before we
                            // extract. Best-effort; ignore failure.
                            view?.evaluateJavascript(CONSENT_ACCEPT_JS) { _ -> }
                            val scope = CoroutineScope(cont.context)
                            scope.launch {
                                var lastLen = 0
                                var best = ""
                                var attempts = 0
                                delay(1200) // let the CMP / consent wall finish rendering
                                while (attempts < 14 && cont.isActive) {
                                    delay(600)
                                    // Re-click consent each pass: the CMP usually
                                    // loads AFTER onPageFinished, so one click at load
                                    // is not enough to reveal the body.
                                    view?.evaluateJavascript(CONSENT_ACCEPT_JS) { _ -> }
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
        }?.let { cleanBody(it) }?.takeIf { it.length >= 40 }
    }

    // JS that returns the longest article container's innerText using the same
    // per-site selectors as the Jsoup path (Daily Mail .article-text, Mirror
    // [class*=ArticleBody]), then generic fallbacks, then body only if nothing else.
    private const val EXTRACT_JS = """
        (function() {
          var sels = ['.article-text','[itemprop="articleBody"]','[class*="ArticleBody"]',
                      '[class*="article-body"]','.article-body','.story-body','.js-article-body',
                      '[class*="sdc-article-body"]','main','.content','article'];
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

    // JS that clicks the most likely consent "Accept"/"Agree" button so a
    // JS-gated CMP (e.g. Mirror/Reach plc) reveals the article body inside the
    // WebView before we extract. Tries common CMP button text + the
    // Sourcepoint/OneTrust accept-all id, then a generic button containing
    // "accept"/"agree"/"allow".
    private const val CONSENT_ACCEPT_JS = """
        (function() {
          function click(el){ if(el){ try{ el.click(); return true; }catch(e){ return false; } } return false; }
          var pick = document.querySelector('#sp-cc-accept, #notice-accept, ' +
            '[id*="accept"], [class*="accept"], [aria-label*="Accept"]');
          if (pick) return click(pick);
          var btns = document.querySelectorAll('button, a.btn, [role=button]');
          for (var i=0;i<btns.length;i++){
            var t=(btns[i].innerText||btns[i].textContent||'').toLowerCase();
            if (t.indexOf('accept')>=0 || t.indexOf('agree')>=0 || t.indexOf('allow all')>=0 || t.indexOf('i agree')>=0){
              if (click(btns[i])) return true;
            }
          }
          return false;
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
