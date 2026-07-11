package com.newsradar.app.rss

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
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

    /** Returns the main article text, or null if it can't be read. */
    suspend fun fetchText(link: String): String? = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(link)
                .userAgent("Mozilla/5.0 (Linux; Android) NewsRadar/1.0")
                .timeout(15000)
                .get()

            // Strip ad / promo / related / footer noise first.
            for (sel in JUNK_SELECTORS) doc.select(sel).remove()

            // Prefer common article containers; fall back to <p> tags site-wide.
            val candidates = doc.select("article p, .article-body p, .story-body p, main p, p")
            val text = candidates.map { it.text().trim() }
                .filter { it.length > 40 }          // drop nav/boilerplate one-liners
                .filter { !it.contains("Cookie Policy", ignoreCase = true) }
                .filter { !it.contains("Subscribe to", ignoreCase = true) }
                .filter { !it.contains("Sign up to", ignoreCase = true) }
                .distinct()
                .joinToString("\n\n")
            if (text.isBlank()) null else text.take(8000)
        } catch (e: Exception) {
            null
        }
    }
}
