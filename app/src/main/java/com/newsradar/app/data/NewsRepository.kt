package com.newsradar.app.data

import android.content.Context
import com.newsradar.app.CrashLogger
import com.newsradar.app.engine.EntityExtractor
import com.newsradar.app.engine.Recommender
import com.newsradar.app.engine.Tokeniser
import com.newsradar.app.engine.TopicTaxonomy
import com.newsradar.app.rss.ArticleFetcher
import com.newsradar.app.rss.RssFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single source of truth: coordinates RSS fetching, the DB, and the recommender.
 */
class NewsRepository private constructor(context: Context) {

    private val dao = NewsDatabase.get(context).newsDao()
    private val fetcher = RssFetcher()
    val recommender = Recommender(dao)

    @Volatile private var migratedV2 = false

    fun observeOutletStates(): Flow<List<OutletState>> = dao.observeOutletStates()

    /** Ensure every outlet has a state row (respecting each outlet's default). */
    suspend fun ensureOutletStates() {
        val existing = dao.getOutletStates().map { it.outletId }.toSet()
        Outlets.ALL.filter { it.id !in existing }.forEach {
            dao.upsertOutletState(
                OutletState(it.id, enabled = it.defaultEnabled, weight = 0.0)
            )
        }
    }

    suspend fun setOutletEnabled(outletId: String, enabled: Boolean) {
        val os = dao.getOutletState(outletId) ?: OutletState(outletId)
        dao.upsertOutletState(os.copy(enabled = enabled))
    }

    /** Persist the user's read-quality rating for a provider (GREEN/AMBER/RED/""). */
    suspend fun setOutletReadQuality(outletId: String, quality: String) {
        dao.setOutletReadQuality(outletId, quality)
    }

    /** The daily (or manual) refresh: fetch -> store -> rescore -> prune. */
    suspend fun refresh(): Int {
        val refreshStart = System.currentTimeMillis()
        ensureOutletStates()
        // One-time V2 migration (idempotent): adopts hard-veto semantics + backfills
        // entity affinity from legacy RED ratings. Safe to skip on subsequent refreshes.
        if (!migratedV2) {
            migratedV2 = true
            recommender.migrateV2()
        }
        val enabled = dao.getOutletStates().filter { it.enabled }.map { it.outletId }.toSet()
        CrashLogger.lifecycle("refresh start: ${enabled.size} outlets")
        CrashLogger.diagnostic("1. Refresh triggered. outlets=${enabled.size}")
        val fetched = fetcher.fetchAll(enabled)
        CrashLogger.diagnostic("2. Fetch completed. Item count: ${fetched.size}")
        if (fetched.isNotEmpty()) {
            CrashLogger.diagnostic("REFRESH_START fetched=${fetched.size}")
            // Merge across any multi-pass fetches: if the same article id was
            // returned more than once (e.g. a flaky outlet fetched twice and the
            // second pass came back empty), keep the version with the real image
            // and summary so a blank final pass can't wipe a good earlier one.
            val merged = fetched.groupBy { it.id }.map { (_, list) ->
                list.reduce { acc, a ->
                    acc.copy(
                        imageUrl = a.imageUrl ?: acc.imageUrl,
                        summary = if (a.summary.isBlank()) acc.summary else a.summary
                    )
                }
            }
            // Which of these are genuinely new (not already persisted)? Only those
            // need on-device entity extraction — re-extracting unchanged articles
            // every refresh was a major, avoidable CPU cost (esp. on repeat pulls).
            val incomingIds = merged.map { it.id }
            val existingIds = dao.getExistingIds(incomingIds).toSet()
            val newArticles = merged.filter { it.id !in existingIds }

            // Insert brand-new articles (IGNORE keeps an existing row intact so we
            // don't clobber user/recommender state or a cached articleBody).
            dao.insertArticles(merged)
            // Backfill feed-derived fields WITHOUT blanking an existing image:
            // only overwrite imageUrl when this fetch actually supplied one.
            merged.forEach { a ->
                if (a.imageUrl != null) {
                    dao.updateFeedFieldsRow(a.id, a.summary, a.imageUrl, a.publishedAt, a.fetchedAt)
                } else {
                    // image-less fetch: refresh dates/summary but leave the stored
                    // imageUrl untouched (don't regress Metro hero images).
                    dao.updateFeedFieldsRowNoImage(a.id, a.summary, a.publishedAt, a.fetchedAt)
                }
            }
            // V2: extract on-device entities for genuinely NEW articles only, and
            // persist the join rows (deduped by articleId+entityKey) for later
            // affinity learning. Heavy CPU work (regex NER) — must NOT run on the
            // main thread or refresh will ANR. Articles already in the DB already
            // have their entities from a previous refresh, so we skip them.
            withContext(Dispatchers.Default) {
                val entRows = mutableListOf<ArticleEntity>()
                val seen = mutableSetOf<String>()
                for (a in newArticles) {
                    for (e in EntityExtractor.extract(a.title, a.summary)) {
                        val dup = "${a.id}:${e.key}"
                        if (dup in seen) continue
                        seen.add(dup)
                        entRows.add(
                            ArticleEntity(
                                articleId = a.id,
                                entityKey = e.key,
                                rawText = e.rawText,
                                type = e.type
                            )
                        )
                    }
                }
                if (entRows.isNotEmpty()) dao.insertArticleEntities(entRows)
                // Score only the newly-fetched articles (context still built from
                // the full DB). On a warm refresh this is a handful of rows instead
                // of the whole table — the main CPU saving vs. re-scoring all 600+.
                recommender.rescoreAll(newArticles.map { it.id }.toSet())
            }
            // Preload reader bodies for the new articles — FIRE-AND-FORGET, off the
            // critical path. The feed returns immediately (instant initial load /
            // refresh) while bodies cache in the background; the reader shows the
            // live-fetch fallback for any article not yet preloaded.
            CoroutineScope(Dispatchers.IO).launch { preloadBodies(newArticles) }
        }
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        dao.pruneOld(weekAgo)
        val elapsedMs = System.currentTimeMillis() - refreshStart
        CrashLogger.diagnostic("REFRESH_DONE fetched=${fetched.size} elapsedMs=$elapsedMs")
        CrashLogger.lifecycle("refresh done: ${fetched.size} articles in ${elapsedMs}ms")
        return fetched.size
    }

    /**
     * ε-greedy feed: a portion of each page is the highest-scored (guided) stories,
     * the rest are random exploration stories interleaved through the page. This keeps
     * the recommender a *guide* rather than a filter — the user still sees varied,
     * un-rated stories so the model keeps learning their preferences.
     *
     * @param excludeIds articles already shown (across all pages) — the random
     *        exploration pool skips these so the user never sees duplicates.
     */
    private val EXPLORATION_RATIO = 0.3 // share of each page that is random exploration
    // Articles shorter than this aren't worth caching as a "clean read".
    private val MIN_READER_LEN = 300
    // Cap of keyword-matched (guided) stories per page so the feed never becomes a
    // monotone wall of "more of the same" — at most 3 of every 5 are interest-driven;
    // the rest are exploration so the model keeps learning. (User request.)
    private val MAX_GUIDED_PER_PAGE = 3
    // Articles older than this are dropped from the feed (user request: >24h ignored).
    private val FEED_MAX_AGE_MS = 24L * 60 * 60 * 1000

    suspend fun getFeedPage(
        page: Int,
        pageSize: Int = 5,
        excludeIds: Set<String> = emptySet()
    ): List<Article> {
        // Outlets turned off in Settings are excluded from the feed immediately.
        // Use an *allowlist* of enabled IDs: if the user disables everything the
        // list is empty and we return a blank feed (a blocklist would leak any
        // article whose outletId isn't tracked).
        val enabled = dao.getOutletStates().filter { it.enabled }.map { it.outletId }
        if (enabled.isEmpty()) return emptyList()
        val guidedCount = (pageSize * (1 - EXPLORATION_RATIO)).toInt().coerceAtLeast(1)
            .coerceAtMost(MAX_GUIDED_PER_PAGE) // never let >3 of 5 be interest-driven
        val exploreCount = (pageSize - guidedCount).coerceAtLeast(1)

        // 24h age cutoff is enforced in SQL (minPublishedAt) so pagination pages
        // over fresh articles and a full page of 5 still sets canLoadMore=true.
        val minPublishedAt = System.currentTimeMillis() - FEED_MAX_AGE_MS

        // Guided offset is based on guidedCount (we only pull guidedCount per page).
        val guided = dao.getFeedPage(guidedCount, page * guidedCount, enabled, minPublishedAt)
        val guidedIds = guided.map { it.id }.toSet()

        // Pull a larger random pool, then drop anything already shown so no duplicates.
        val randomPool = dao.getFeedRandom(exploreCount * 4 + pageSize, enabled, minPublishedAt)
            .filter { it.id !in excludeIds && it.id !in guidedIds }
        val explore = randomPool.take(exploreCount)

        // V2 hard-veto: drop any article whose tokens / topics / entities hit
        // explicit_dislikes (so "Explicit Dislike -> 0" actually hides, not just sinks).
        val filtered = applyVeto(guided + explore)

        // Interleave: one exploration story every `step` positions, so variety is
        // spread through the page instead of dumped at the end.
        val step = (guidedCount / exploreCount).coerceAtLeast(1)
        val merged = mutableListOf<Article>()
        var ei = 0
        filtered.forEachIndexed { idx, a ->
            merged.add(a)
            if ((idx + 1) % step == 0 && ei < explore.size) {
                merged.add(explore[ei++])
            }
        }
        while (ei < explore.size) merged.add(explore[ei++])

        // Guarantee exactly pageSize (don't signal EOF early): backfill with more
        // guided stories if the random pool came up short.
        var backfill = page * guidedCount + guided.size
        while (merged.size < pageSize) {
            val extra = dao.getFeedPage(1, backfill, enabled, minPublishedAt).firstOrNull() ?: break
            if (extra.id in excludeIds || merged.any { it.id == extra.id }) {
                backfill++
                continue
            }
            merged.add(extra)
            backfill++
        }
        return merged.take(pageSize)
    }

    /**
     * Background body preload: extract + cache reader bodies for newly-fetched
     * articles so the in-app reader works instantly + offline, instead of doing a
     * live network fetch on every open (which fails for WAF/consent-walled outlets
     * and after a refresh-all burst). Runs with BOUNDED parallelism + a short
     * per-article timeout so a slow/outage outlet can't serially stall the whole
     * refresh — a batch of N articles finishes in ~one article's time, not N.
     * Best-effort: failures are ignored, the article stays text-less until opened.
     */
    private suspend fun preloadBodies(articles: List<Article>) = withContext(Dispatchers.IO.limitedParallelism(6)) {
        articles.map { a ->
            async {
                if (a.articleBody != null) return@async
                runCatching {
                    val body = withTimeoutOrNull(8_000) {
                        ArticleFetcher.fetchText(a.link, null)
                    }
                    if (!body.isNullOrBlank() && body.length >= MIN_READER_LEN) {
                        cacheArticleBody(a.id, body)
                    }
                }
            }
        }.awaitAll()
    }

    suspend fun rate(article: Article, rating: Rating) {
        recommender.applyRatingV2(article, rating)
    }

    /** Revert a prior rating (toggle-to-NONE failsafe): undo its learning. */
    suspend fun unrate(article: Article, prev: Rating) {
        recommender.unrate(article, prev)
    }

    /**
     * V2 hard-veto: remove any article whose tokens / topics / entities intersect
     * explicit_dislikes. Cheap: token/topic checks run on the small candidate pool;
     * entity keys are bulk-loaded once for the pool. (matches the spec's
     * "Explicit Dislike -> 0" being a hard hide, not just a sink.)
     */
    private suspend fun applyVeto(articles: List<Article>): List<Article> {
        if (articles.isEmpty()) return articles
        val dislikes = dao.allExplicitDislikes()
        if (dislikes.isEmpty()) return articles
        val dTokens = dislikes.filter { it.kind == "TOKEN" }.map { it.key }.toSet()
        val dTopics = dislikes.filter { it.kind == "TOPIC" }.map { it.key }.toSet()
        val dEntities = dislikes.filter { it.kind == "ENTITY" }.map { it.key }.toSet()
        val entsByArticle = dao.entitiesForArticles(articles.map { it.id })
            .groupBy { it.articleId }
            .mapValues { it.value.map { e -> e.entityKey }.toSet() }
        return articles.filterNot { a ->
            val tokens = Tokeniser.tokenise("${a.title} ${a.summary}").toSet()
            val topics = TopicTaxonomy.topicsForTokens(tokens)
            val ents = entsByArticle[a.id] ?: emptySet()
            tokens.any { it in dTokens } ||
                topics.any { it in dTopics } ||
                ents.any { it in dEntities }
        }
    }

    /** Seed initial keyword interests (likes) into the recommender. */
    suspend fun applySeeds(words: List<String>) {
        recommender.applySeeds(words)
    }

    /** Seed disliked keyword interests — down-weighted, shown rarely. */
    suspend fun applyDislikes(words: List<String>) {
        recommender.applyDislikes(words)
    }

    suspend fun reasonsFor(article: Article): List<String> =
        recommender.reasonsForArticle(article)

    /** Persist an extracted reader body so the article reads offline next time. */
    suspend fun cacheArticleBody(id: String, body: String) = dao.updateArticleBody(id, body)

    /** Persist a corrected hero image (e.g. replace Guardian's UA-pinned signed
     *  feed URL with the page's unsigned og:image) so it loads instantly + offline. */
    suspend fun updateArticleImage(id: String, imageUrl: String) =
        dao.updateArticleImage(id, imageUrl)

    // ---- Read history ----
    /** Record (or bump) a read article and prune the list back to the last 100.
     *  Deduped by [Article.id] -> re-reading an article updates its readAt. */
    suspend fun recordRead(article: Article) {
        dao.upsertReadHistory(
            ReadHistory(
                articleId = article.id,
                title = article.title,
                summary = article.summary,
                outletName = article.outletName,
                link = article.link,
                imageUrl = article.imageUrl,
                readAt = System.currentTimeMillis()
            )
        )
        dao.pruneReadHistory()
    }

    /** Keyword-searchable read history. [query] is matched as a substring of
     *  title or summary; a blank query returns the full most-recent list. */
    fun getHistory(query: String): Flow<List<ReadHistory>> {
        val pattern = if (query.isBlank()) "%" else "%${query.trim()}%"
        return dao.searchReadHistory(pattern)
    }

    /** Trim the stored history to the 100 most-recent rows (offline housekeeping). */
    suspend fun pruneHistory() = dao.pruneReadHistory()

    companion object {
        @Volatile private var INSTANCE: NewsRepository? = null
        fun get(context: Context): NewsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NewsRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
