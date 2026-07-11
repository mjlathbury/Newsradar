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
 *   delta = GREEN:+2  AMBER:+0.5  RED:-2
 *   For each unique token in the article: keyword.weight += delta; keyword.docCount++
 *   The outlet's weight += delta * 0.25 (outlet is a weaker signal than topic).
 *
 * Scoring (on ranking a fetched article):
 *   For each token t in the article:
 *     idf = ln( (1 + totalRated) / (1 + docCount(t)) ) + 1
 *     score += keywordWeight(t) * idf
 *   score += outletWeight
 *   Rare, meaningful tokens (low docCount -> high idf) dominate, so the model
 *   learns *topics* rather than common filler. The more articles rated, the
 *   sharper the idf term and the more accurate ranking becomes.
 */
class Recommender(private val dao: NewsDao) {

    private fun delta(rating: Rating): Double = when (rating) {
        Rating.GREEN -> 2.0
        Rating.AMBER -> 0.5
        Rating.RED -> -2.0
        Rating.NONE -> 0.0
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
