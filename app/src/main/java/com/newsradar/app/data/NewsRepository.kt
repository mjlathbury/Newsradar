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

    suspend fun getFeedPage(page: Int, pageSize: Int = 5): List<Article> =
        dao.getFeedPage(pageSize, page * pageSize)

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
