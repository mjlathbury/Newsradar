package com.newsradar.app.data

/** How much of an outlet's full articles sit behind a paywall. */
enum class Paywall { NONE, METERED, HARD }

/**
 * Master list of UK news outlets and their public RSS feeds.
 * All feeds are free and unlimited. Users can disable any outlet in Settings.
 *
 * `paywall` describes access to the FULL article when tapped through (RSS
 * summaries are always free). `defaultEnabled` starts hard-paywall outlets
 * OFF so a fresh install isn't full of locked stories — the user can enable
 * them in Settings if they subscribe.
 */
data class Outlet(
    val id: String,
    val name: String,
    val feedUrl: String,
    val paywall: Paywall = Paywall.NONE,
    val defaultEnabled: Boolean = true
)

object Outlets {
    val ALL: List<Outlet> = listOf(
        Outlet("bbc", "BBC News", "https://feeds.bbci.co.uk/news/rss.xml"),
        Outlet("bbc_uk", "BBC UK", "https://feeds.bbci.co.uk/news/uk/rss.xml"),
        Outlet("guardian", "The Guardian", "https://www.theguardian.com/uk/rss"),
        Outlet("independent", "The Independent", "https://www.independent.co.uk/news/uk/rss", Paywall.METERED),
        Outlet("sky", "Sky News", "https://feeds.skynews.com/feeds/rss/home.xml"),
        Outlet("mirror", "The Mirror", "https://www.mirror.co.uk/news/?service=rss"),
        Outlet("metro", "Metro", "https://metro.co.uk/feed/"),
        Outlet("mail", "Daily Mail", "https://www.dailymail.co.uk/news/index.rss"),
        Outlet("express", "Daily Express", "https://www.express.co.uk/posts/rss/1/news"),
        Outlet("standard", "Evening Standard", "https://www.standard.co.uk/news/rss"),
        Outlet("huffpost", "HuffPost UK", "https://www.huffingtonpost.co.uk/feeds/index.xml"),
        Outlet("inews", "iNews", "https://inews.co.uk/feed", Paywall.METERED),
        Outlet("dailyrecord", "Daily Record", "https://www.dailyrecord.co.uk/news/?service=rss"),
        Outlet("scotsman", "The Scotsman", "https://www.scotsman.com/rss", Paywall.METERED),
        Outlet("walesonline", "Wales Online", "https://www.walesonline.co.uk/news/?service=rss"),
        // Hard paywalls: kept in the list but OFF by default.
        Outlet("telegraph", "The Telegraph", "https://www.telegraph.co.uk/news/rss.xml",
            Paywall.HARD, defaultEnabled = false),
        Outlet("ft", "Financial Times", "https://www.ft.com/rss/home/uk",
            Paywall.HARD, defaultEnabled = false)
    )

    fun byId(id: String): Outlet? = ALL.firstOrNull { it.id == id }

    /** Outlets whose full article is consent-gated / body-less on-device: extraction
     *  reliably comes back too thin, so the reader routes straight to a Chrome
     *  Custom Tab ("Read on Web") instead of showing a broken stub. Revisit as AMP
     *  extraction improves — drop an id here if it starts extracting cleanly.
     *  NOTE: mail + mirror were removed (2026-07-13) — a live HTTP probe proved
     *  both serve full server-rendered bodies under a normal mobile UA (Mirror
     *  JSON-LD articleBody = ~1.4k chars; Mail body in .article-text), so the
     *  native reader extracts them fine. dailyrecord remains gated (unprobed CMS). */
    private val GATED = setOf("dailyrecord")

    fun isGated(id: String): Boolean = id in GATED
}
