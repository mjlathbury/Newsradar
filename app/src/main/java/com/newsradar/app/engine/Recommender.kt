package com.newsradar.app.engine

import com.newsradar.app.data.Article
import com.newsradar.app.data.ArticleScoreUpdate
import com.newsradar.app.data.KeywordWeight
import com.newsradar.app.data.NewsDao
import com.newsradar.app.data.OutletState
import com.newsradar.app.data.Rating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln

/**
 * Content-based recommender with TF-IDF weighting.
 *
 * Learning (on rating):
 *   delta = GREEN:+1.5  AMBER:+0.4  RED:-1.5
 *   For each unique token in the article: keyword.weight += delta; keyword.docCount++
 *   The outlet's weight += delta * 0.25 (outlet is a weaker signal than topic).
 *
 * Seeding (initial interests):
 *   The user can type seed keywords (e.g. "football, space"). Each is stored as a
 *   GREEN-equivalent keyword weight so the TF-IDF scoring ranks matching stories
 *   higher from day one — no ratings required. Seeds are merged with learned
 *   weights, so once the user rates enough, their real feedback dominates.
 */
class Recommender(private val dao: NewsDao) {

    private val SEED_WEIGHT = 2.0 // equivalent to one GREEN rating per seed token

    private fun delta(rating: Rating): Double = when (rating) {
        Rating.GREEN -> 1.5
        Rating.AMBER -> 0.4
        Rating.RED -> -1.5
        Rating.NONE -> 0.0
    }

    /**
     * Seed initial interests. Existing learned weights are preserved; seeds only
     * ADD positive weight (they never push a keyword negative). Re-scores after.
     */
    suspend fun applySeeds(words: List<String>) {
        val tokens = words.flatMap { Tokeniser.tokenise(it) }.toSet().filter { it.isNotBlank() }
        if (tokens.isEmpty()) return
        val existing = dao.getKeywords(tokens).associateBy { it.keyword }
        val updated = tokens.map { t ->
            val cur = existing[t]
            KeywordWeight(
                keyword = t,
                weight = maxOf(cur?.weight ?: 0.0, 0.0) + SEED_WEIGHT,
                docCount = maxOf(cur?.docCount ?: 0, 1)
            )
        }
        dao.upsertKeywords(updated)
        rescoreAll()
    }

    /** Apply a user rating: update the article + learn keyword/outlet weights. */
    suspend fun applyRating(article: Article, rating: Rating) {
        dao.setRating(article.id, rating.name)
        val d = delta(rating)
        if (d == 0.0) return

        val tokens = Tokeniser.tokenise("${article.title} ${article.summary}").toSet()
        val existing = dao.getKeywords(tokens.toList()).associateBy { it.keyword }
        val updatedKeywords = tokens.map { t ->
            val cur = existing[t]
            KeywordWeight(t, (cur?.weight ?: 0.0) + d, (cur?.docCount ?: 0) + 1)
        }
        dao.upsertKeywords(updatedKeywords)

        val os = dao.getOutletState(article.outletId)
            ?: OutletState(article.outletId, enabled = true, weight = 0.0)
        dao.upsertOutletState(os.copy(weight = os.weight + d * 0.25))
    }

    /** Score every article and persist in one batch. Heavy work off the main thread. */
    suspend fun rescoreAll() = withContext(Dispatchers.Default) {
        val totalRated = dao.ratedArticleCount()
        val kw = dao.getAllKeywords().associateBy { it.keyword }
        val outlets = dao.getOutletStates().associateBy { it.outletId }
        val updates = dao.getAllArticles().map { a ->
            val tokens = Tokeniser.tokenise("${a.title} ${a.summary}").toSet()
            var s = 0.0
            for (t in tokens) {
                val k = kw[t] ?: continue
                val idf = ln((1.0 + totalRated) / (1.0 + k.docCount)) + 1.0
                s += k.weight * idf
            }
            s += outlets[a.outletId]?.weight ?: 0.0
            ArticleScoreUpdate(a.id, s)
        }
        dao.updateScores(updates)
    }

    /** Human-readable reason: top matched positive keywords for an article. */
    suspend fun matchReasons(article: Article, max: Int = 3): List<String> {
        val tokens = Tokeniser.tokenise("${article.title} ${article.summary}").toSet()
        return dao.getKeywords(tokens.toList())
            .filter { it.weight > 0 }
            .sortedByDescending { it.weight }
            .take(max)
            .map { it.keyword }
    }
}
