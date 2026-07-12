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
            // Clean the link first: Daily Mail RSS serves tracking-wrapped URLs that
            // 410 (Gone). Strip query params and normalise to the .co.uk article host.
            val cleanLink = link
                .substringBefore("?")
                .replace("dailymail.com", "dailymail.co.uk")
                .replace("https://www.dailymail.co.uk/news/article-1490", "https://www.dailymail.co.uk/news")

            val doc = Jsoup.connect(cleanLink)
                .userAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-GB,en;q=0.9")
                .followRedirects(true)
                .timeout(30000)
                .maxBodySize(0)
                .get()

            // Strip ad / promo / related / footer noise first.
            for (sel in JUNK_SELECTORS) doc.select(sel).remove()

            // Prefer a real article container; fall back to <p> across the page.
            val candidates = doc.select(
                "article p, .article-body p, .story-body p, .article__body p, " +
                    ".js-article-body p, main p, .content p, p"
            )
            val text = candidates.map { it.text().trim() }
                // drop nav/boilerplate one-liners AND huge single-line menu blobs
                .filter { it.length in 40..400 }
                .filter { !it.contains("Cookie Policy", ignoreCase = true) }
                .filter { !it.contains("Subscribe to", ignoreCase = true) }
                .filter { !it.contains("Sign up to", ignoreCase = true) }
                .filter { !it.contains("More from", ignoreCase = true) }
                .filter { !it.contains("©", ignoreCase = true) }
                .filter { !it.contains("continue reading", ignoreCase = true) }
                .filter { !it.contains("click to continue", ignoreCase = true) }
                .distinct()
                .joinToString("\n\n")
            if (text.isBlank()) null else text.take(8000)
        } catch (e: Exception) {
            null
        }
    }
}
