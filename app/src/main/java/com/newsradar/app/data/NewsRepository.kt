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
     */
    private val EXPLORATION_RATIO = 0.3 // share of each page that is random exploration

    suspend fun getFeedPage(page: Int, pageSize: Int = 5): List<Article> {
        val guidedCount = (pageSize * (1 - EXPLORATION_RATIO)).toInt().coerceAtLeast(1)
        val exploreCount = (pageSize - guidedCount).coerceAtLeast(1)

        val guided = dao.getFeedPage(guidedCount, page * pageSize)
        val guidedIds = guided.map { it.id }.toSet()
        val explore = dao.getFeedRandom(exploreCount).filter { it.id !in guidedIds }

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
        return merged
    }

    suspend fun rate(article: Article, rating: Rating) {
        recommender.applyRating(article, rating)
        recommender.rescoreAll()
    }

    /** Cache the fetched article body so a 60s summary survives re-opens. */
    suspend fun cacheSummaryText(id: String, text: String) {
        dao.setSummaryText(id, text)
    }

    /** Seed initial keyword interests into the recommender (bias the early feed). */
    suspend fun applySeeds(words: List<String>) {
        recommender.applySeeds(words)
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
