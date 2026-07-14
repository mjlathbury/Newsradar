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
     *  native reader extracts them fine. dailyrecord remains gated (unprobed CMS).
     *  sky added (2026-07-14): device + datacenter both get a 24-byte WAF challenge
     *  page ("Powered and protected by …"), so there is no article HTML to extract —
     *  route straight to the browser rather than a failed fetch. */
    private val GATED = setOf("dailyrecord", "sky")

    fun isGated(id: String): Boolean = id in GATED

    /**
     * Human-readable reason a gated outlet can't use the in-app reader and is
     * opened in the system browser instead. Shown to the user in a dialog so the
     * behaviour isn't mysterious.
     */
    fun gatedReason(id: String): String = when (id) {
        "telegraph" -> "The Telegraph is behind a paywall. NewsRadar can't extract its full article text, so it opens in your browser — a subscription may be required to read the whole piece."
        "ft" -> "The Financial Times is behind a paywall. NewsRadar can't extract its full article text, so it opens in your browser — a subscription may be required to read the whole piece."
        "dailyrecord" -> "Daily Record serves a consent wall that blocks the reader. It opens in your browser instead, where you can accept and read it."
        "sky" -> "Sky News blocks automated readers with a bot-protection page, so there's no article text to extract. It opens in your browser instead."
        else -> "This outlet can't be shown in the in-app reader, so it opens in your browser."
    }

    /**
     * Developer-set read-quality rating per provider (our QA findings). Shown as a
     * read-only dot in Settings so users can see which sources read cleanly in the
     * in-app reader. This is NOT user-editable — we update it as we improve
     * extraction. GREEN = clean, AMBER = some issues, RED = broken/black,
     * "" = not yet rated.
     */
    // Ratings are ONLY recorded once YOU have actually tested the provider in the
    // in-app reader during the QA pass. Anything absent here is unrated (blank dot)
    // until tested — never pre-filled from offline replicas or subagent checks.
    private val READ_QUALITY: Map<String, String> = mapOf(
        "bbc" to "GREEN",         // verified clean by app extraction
        "bbc_uk" to "AMBER",      // user-tested: minor stray end items
        "guardian" to "GREEN",    // verified 8000/23, 5934/13 clean
        "independent" to "GREEN", // verified 3951/21, 2016/19 (bookmark junk stripped)
        "sky" to "BLACK",         // WAF bot-wall (24-byte challenge) -> opens in browser (expected)
        "mirror" to "GREEN",      // verified 8000/62, 3464/16 clean
        "metro" to "GREEN",       // verified 2999/26, 2879/24 (tail promos stripped)
        "mail" to "AMBER",        // link-heavy guard added (was "just links"); verify on-device
        "express" to "AMBER",     // articleBody selector + header fix; WAF transient on sandbox, verify
        "standard" to "GREEN",    // verified 2561/13, 4115/25 clean
        "huffpost" to "AMBER",    // user-tested AMBER; entry__body selector added
        "inews" to "GREEN",       // verified 3722/21, 4198/26 clean
        "dailyrecord" to "BLACK", // gated -> opens in browser (expected)
        "scotsman" to "GREEN",    // verified 1816/13, 4641/20 (Comments tail stripped)
        "walesonline" to "GREEN", // verified 5733/35, 1670/8 (live-blog artifact stripped)
        "telegraph" to "BLACK",   // 403 server wall -> opens in browser (expected)
        "ft" to "BLACK"           // paywall -> opens in browser (expected)
    )

    fun readQuality(id: String): String = READ_QUALITY[id] ?: ""
}
