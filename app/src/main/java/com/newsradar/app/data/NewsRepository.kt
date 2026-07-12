package com.newsradar.app.data

import android.content.Context
import com.newsradar.app.engine.Recommender
import com.newsradar.app.rss.RssFetcher
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth: coordinates RSS fetching, the DB, and the recommender.
 */
class NewsRepository private constructor(context: Context) {

    private val dao = NewsDatabase.get(context).newsDao()
    private val fetcher = RssFetcher()
    val recommender = Recommender(dao)

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

    /** The daily (or manual) refresh: fetch -> store -> rescore -> prune. */
    suspend fun refresh(): Int {
        ensureOutletStates()
        val enabled = dao.getOutletStates().filter { it.enabled }.map { it.outletId }.toSet()
        val fetched = fetcher.fetchAll(enabled)
        if (fetched.isNotEmpty()) {
            dao.insertArticles(fetched)
            recommender.rescoreAll()
        }
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        dao.pruneOld(weekAgo)
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
        val exploreCount = (pageSize - guidedCount).coerceAtLeast(1)

        // Guided offset is based on guidedCount (we only pull guidedCount per page).
        val guided = dao.getFeedPage(guidedCount, page * guidedCount, enabled)
        val guidedIds = guided.map { it.id }.toSet()

        // Pull a larger random pool, then drop anything already shown so no duplicates.
        val randomPool = dao.getFeedRandom(exploreCount * 4 + pageSize, enabled)
            .filter { it.id !in excludeIds && it.id !in guidedIds }
        val explore = randomPool.take(exploreCount)

        // Interleave: one exploration story every `step` positions, so variety is
        // spread through the page instead of dumped at the end.
        val step = (guidedCount / exploreCount).coerceAtLeast(1)
        val merged = mutableListOf<Article>()
        var ei = 0
        guided.forEachIndexed { idx, a ->
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
            val extra = dao.getFeedPage(1, backfill, enabled).firstOrNull() ?: break
            if (extra.id in excludeIds || merged.any { it.id == extra.id }) {
                backfill++
                continue
            }
            merged.add(extra)
            backfill++
        }
        return merged.take(pageSize)
    }

    suspend fun rate(article: Article, rating: Rating) {
        recommender.applyRating(article, rating)
        recommender.rescoreAll()
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
        recommender.matchReasons(article)

    companion object {
        @Volatile private var INSTANCE: NewsRepository? = null
        fun get(context: Context): NewsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NewsRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
