package com.newsradar.app.rss

import com.newsradar.app.data.Article
import com.newsradar.app.data.Outlet
import com.newsradar.app.data.Outlets
import com.prof18.rssparser.RssParserBuilder
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Fetches and parses RSS from all enabled UK outlets in parallel,
 * normalises items into [Article]s and de-duplicates by link hash.
 */
class RssFetcher {

    private val parser = RssParserBuilder().build()

    suspend fun fetchAll(enabledOutletIds: Set<String>): List<Article> = coroutineScope {
        val outlets = Outlets.ALL.filter { it.id in enabledOutletIds }
        val now = System.currentTimeMillis()

        val jobs = outlets.map { outlet ->
            async(Dispatchers.IO) {
                runCatching { fetchOutlet(outlet, now) }.getOrElse { e ->
                    // A CancellationException just means this sync was superseded by
                    // another (e.g. init + onResume both kicked off a refresh) — it is
                    // NOT a real feed failure, so don't log it as one (it would show up
                    // as a spurious "feed fetch failed" in Debug while the surviving
                    // sync still delivers the articles). Only log genuine errors.
                    if (e is kotlinx.coroutines.CancellationException) return@getOrElse emptyList()
                    // prof18 can choke on tiny namespace/entity violations that a
                    // server-side probe ignores. Log it and fail over to a direct
                    // Jsoup RSS parse before giving up on the outlet entirely.
                    com.newsradar.app.CrashLogger.record(
                        RuntimeException("RssFetcher: prof18 failed for '${outlet.id}', trying Jsoup fallback", e)
                    )
                    runCatching { fetchOutletFallback(outlet, now) }.getOrElse { e2 ->
                        // Similarly, a cancellation while trying the fallback is benign.
                        if (e2 is kotlinx.coroutines.CancellationException) return@getOrElse emptyList()
                        com.newsradar.app.CrashLogger.record(
                            RuntimeException("RssFetcher: feed fetch failed for '${outlet.id}' (${outlet.feedUrl})", e2)
                        )
                        emptyList()
                    }
                }
            }
        }
        val all = jobs.awaitAll().flatten()

        // Deduplicate: same story often appears once per feed; also BBC has two feeds.
        all.distinctBy { it.id }
            .sortedByDescending { it.publishedAt }
    }

    private suspend fun fetchOutlet(outlet: Outlet, now: Long): List<Article> =
        withContext(Dispatchers.IO) {
            val channel = parser.getRssChannel(outlet.feedUrl)
            channel.items.mapNotNull { item ->
                val link = item.link?.trim() ?: return@mapNotNull null
                val title = item.title?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                Article(
                    id = hash(link),
                    title = title,
                    // Prefer the fuller of description/content so the Brief Summary has
                    // enough source text for a ~60s read (some feeds put the full
                    // article in content:encoded, others only a short description).
                    summary = run {
                        val desc = cleanHtml(item.description ?: "")
                        val content = cleanHtml(item.content ?: "")
                        val full = if (content.length > desc.length) content else desc
                        stripTeaser(full).take(3000)
                    },
                    link = link,
                    imageUrl = item.image ?: item.itunesItemData?.image,
                    outletId = outlet.id,
                    outletName = outlet.name,
                    publishedAt = parseDate(item.pubDate) ?: now,
                    fetchedAt = now
                )
            }
        }

    /**
     * Fallback RSS parse using Jsoup directly (XML mode). Used when prof18 throws
     * on an outlet's feed (e.g. Metro) — Jsoup is far more tolerant of minor
     * namespace / entity quirks. Selects standard <item> fields including the
     * namespaced <content:encoded>.
     */
    private suspend fun fetchOutletFallback(outlet: Outlet, now: Long): List<Article> =
        withContext(Dispatchers.IO) {
            val doc = Jsoup.connect(outlet.feedUrl)
                .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .timeout(15000)
                .parser(Parser.xmlParser())
                .get()
            doc.select("item").mapNotNull { el ->
                val link = el.selectFirst("link")?.text()?.trim() ?: return@mapNotNull null
                val title = el.selectFirst("title")?.text()?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                val rawDesc = el.selectFirst("content|encoded")?.text()
                    ?: el.selectFirst("description")?.text() ?: ""
                Article(
                    id = hash(link),
                    title = title,
                    summary = cleanHtml(rawDesc).take(3000),
                    link = link,
                    imageUrl = el.selectFirst("image|url")?.text()
                        ?: el.selectFirst("enclosure")?.attr("url"),
                    outletId = outlet.id,
                    outletName = outlet.name,
                    publishedAt = parseDate(el.selectFirst("pubDate")?.text()) ?: now,
                    fetchedAt = now
                )
            }
        }

    private fun cleanHtml(s: String): String {
        if (s.isBlank()) return ""
        // Jsoup parses the HTML and .text() strips all tags AND decodes every
        // HTML entity (&rsquo; &mdash; &#8217; etc.) — far more robust than a
        // hand-rolled regex+entity map, which left stray characters (e.g. on
        // HuffPost feeds that embed full HTML in content:encoded).
        return Jsoup.parse(s).text()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            // Drop consent / cookie-wall boilerplate that some feeds embed in
            // content:encoded (e.g. Evening Standard via Google JCP / Exco Player,
            // plus newsletter / privacy sign-up prompts).
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
            .filter { !it.contains("we need your consent", ignoreCase = true) }
            .filter { !it.contains("may use cookies", ignoreCase = true) }
            .joinToString(" ")
            .replace(MULTIPLE_SPACES, " ")
            .trim()
            .take(3000)
    }

    /** Drop trailing "click to continue reading" / "read more" teaser stubs that
     *  some feeds (e.g. Guardian) append to the RSS content. */
    private fun stripTeaser(s: String): String {
        val regex = Regex(
            "(?i)\\s*(\\.\\.\\.|…)?\\s*(click (here to|to) (continue|read)|continue reading|read more|read the full (story|article))"
        )
        val match = regex.find(s)
        return if (match != null) s.take(match.range.first).trim() else s
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (p in patterns) {
            runCatching {
                val fmt = java.text.SimpleDateFormat(p, java.util.Locale.ENGLISH)
                return fmt.parse(raw)?.time
            }
        }
        return null
    }

    private fun hash(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val MULTIPLE_SPACES = Regex("\\s+")
    }
}
