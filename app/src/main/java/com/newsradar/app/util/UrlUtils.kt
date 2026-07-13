package com.newsradar.app.util

/**
 * Stateless URL sanitisation helpers. Pure Kotlin — no Android, no Jsoup, no
 * Context, no network, no disk. Every function is `String -> String` with zero
 * side effects. This keeps all URL knowledge out of the DOM-parsing code
 * (ArticleFetcher) and the network code (RssFetcher), per the Q3/Q4 refactor.
 */
object UrlUtils {

    /** Universal tracking garbage, stripped from EVERY url (links + images). */
    private val GLOBAL_TRACKING = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_name", "utm_reader", "gclid", "fbclid", "mc_cid", "mc_eid",
        "ref_src", "igshid", "_ga", "_gl", "spm"
    )

    /** Extra params stripped only from IMAGE urls (cache-busting / analytics that
     *  serve zero rendering purpose on image CDNs). Note: `ref`/`ref_src` are
     *  stripped from images but PRESERVED on links (some outlets route via ref). */
    private val IMAGE_TRACKING = setOf("ref", "ref_src")

    /** Generic resize/transform hints dropped from image urls unless the host's
     *  allow-list preserves them (e.g. Guardian's `s` signature). */
    private val IMAGE_RESIZE = setOf(
        "width", "height", "w", "h", "quality", "q", "fit", "auto", "dpr",
        "crop", "trim", "resize", "sz", "strip", "webp", "fm", "mark", "sharp"
    )

    /**
     * Per-host allow-list: params that MUST survive cleaning. These are CDN
     * signatures validated against the full query — stripping them yields HTTP 401.
     * Hosts not listed drop ALL query params (path only).
     */
    private val HOST_ALLOWLIST: Map<String, Set<String>> = mapOf(
        "i.guim.co.uk" to setOf("s"),
        "media.gettyimages.com" to setOf("s"),
        "i.dailymail.co.uk" to setOf("token"),
        "dailymail.co.uk" to setOf("token")
    )

    /** Hosts whose signed image URLs are UA-pinned and will 401 through Coil —
     *  callers should bypass to og:image instead of waiting for Coil to fail. */
    private val SIGNED_IMAGE_HOSTS = setOf("i.guim.co.uk", "media.gettyimages.com")

    /** Strip the given param keys from a query string, preserving order + values. */
    private fun stripQueryParams(
        url: String,
        drop: Set<String>,
        keepOnly: Set<String>? = null
    ): String {
        val base = url.substringBefore("?")
        if (!url.contains("?")) return base
        val query = url.substringAfter("?")
        val kept = query.split("&").mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore("=").lowercase()
            val keep = if (keepOnly != null) key in keepOnly else key !in drop
            if (keep) pair else null
        }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    private fun hostOf(url: String): String? {
        // Best-effort host extraction without android.net.Uri.
        val noScheme = url.substringAfter("://", missingDelimiterValue = url)
        val authority = noScheme.substringBefore("/").substringBefore("@")
        return if (authority.isBlank()) null else authority.lowercase()
    }

    /** Normalise a navigation/article link: lower host, drop fragment, strip
     *  universal tracking. PRESERVES `ref`/`ref_src` (smaller outlets route via
     *  ref; stripping risks 404 / broken reader extraction). */
    fun normaliseLink(url: String): String {
        if (url.isBlank()) return url
        var u = url.substringBefore("#") // drop fragment
        val host = hostOf(u)
        if (host != null) {
            u = u.replace(host, host, ignoreCase = true) // no-op normalisation hook
            if (host == "dailymail.co.uk") {
                u = u.replace("dailymail.co.uk", "dailymail.com", ignoreCase = true)
            }
        }
        return stripQueryParams(u, GLOBAL_TRACKING)
    }

    /** Normalise an image url: drop resize hints, apply per-host allow-list
     *  (keep `s`/`token`), then strip tracking (incl. ref/ref_src for images). */
    fun cleanImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = url.substringBefore("#")
        val host = hostOf(u)
        val stripped = if (host in HOST_ALLOWLIST) {
            // Signed CDN (Guardian i.guim.co.uk, Getty, DailyMail token): the
            // signature is validated against the FULL original query, so we must
            // preserve every param except universal tracking. Stripping resize
            // params (or reducing to just `s`) breaks the signature -> HTTP 401.
            stripQueryParams(u, GLOBAL_TRACKING)
        } else {
            // Other hosts: drop all resize + image-tracking params, then tracking.
            val trimmed = stripQueryParams(u, IMAGE_RESIZE + IMAGE_TRACKING)
            stripQueryParams(trimmed, GLOBAL_TRACKING)
        }
        return stripped
    }

    /** True if the image url is a known UA-pinned signed CDN url that will fail
     *  in Coil — callers should trigger the og:image bypass instead. */
    fun isSignedImageUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host in SIGNED_IMAGE_HOSTS
    }
}
