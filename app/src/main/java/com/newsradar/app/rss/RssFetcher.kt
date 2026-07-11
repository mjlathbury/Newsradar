package com.newsradar.app.rss

import com.newsradar.app.data.Article
import com.newsradar.app.data.Outlet
import com.newsradar.app.data.Outlets
import com.prof18.rssparser.RssParserBuilder
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
                runCatching { fetchOutlet(outlet, now) }.getOrElse { emptyList() }
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
                    summary = cleanHtml(item.description ?: item.content ?: ""),
                    link = link,
                    imageUrl = item.image ?: item.itunesItemData?.image,
                    outletId = outlet.id,
                    outletName = outlet.name,
                    publishedAt = parseDate(item.pubDate) ?: now,
                    fetchedAt = now
                )
            }
        }

    private fun cleanHtml(s: String): String =
        s.replace(HTML_TAGS, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace(MULTIPLE_SPACES, " ")
            .trim()
            .take(400)

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
        private val HTML_TAGS = Regex("<[^>]*>")
        private val MULTIPLE_SPACES = Regex("\\s+")
    }
}
