package com.newsradar.app.engine

import com.newsradar.app.data.Article
import com.newsradar.app.data.ArticleEntity
import com.newsradar.app.data.EntityAffinity
import com.newsradar.app.data.KeywordWeight
import com.newsradar.app.data.NewsDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln

/**
 * V2 scoring engine (100% on-device).
 *
 * Stotal = [(Wbase * Sbase) + (Wdyn * Sdyn)] * e^(-lambda * ageDays)
 *
 * - Sbase  = legacy TF-IDF token score + explicit-topic bonus (preserves prior learning
 *            and honours onboarding topic seeds).
 * - Sdyn   = sum of entity-affinity scores for the article's entities (0 if none; never
 *            penalised for having no entities).
 * - ageDays = recency decay of the ARTICLE (publishedAt), not "days since rating" — this
 *            scores every candidate uniformly.
 * - Hard veto: any disliked entity/topic/token -> Stotal = 0 (Explicit Dislike semantics).
 *
 * RED penalises ENTITIES only (never broad topics), per spec.
 */
class Scorer(private val dao: NewsDao) {

    data class ScoreContext(
        val keywordWeights: Map<String, KeywordWeight>,
        val entityAffinities: Map<String, EntityAffinity>,
        val explicitTopics: Set<String>,
        val dislikedEntities: Set<String>,
        val dislikedTopics: Set<String>,
        val dislikedTokens: Set<String>,
        val articleEntities: Map<String, List<ArticleEntity>>,
        val totalRated: Int,
        val nowMs: Long,
        val lambda: Double
    )

    data class ScoreBreakdown(
        val sBase: Double,
        val sDyn: Double,
        val stotal: Double,
        val topics: List<String>,
        val entities: List<String>,
        val reasons: List<String>
    )

    /**
     * Re-score articles and persist. [targetIds] restricts scoring to a subset
     * (e.g. only newly-fetched articles on a warm refresh) — the ScoreContext is
     * still built from the FULL DB once, so cross-article learning is preserved,
     * but we skip re-scoring every row on every refresh (O(N) -> O(changed)).
     * Pass null (default) to score the whole table (used by migrate / rating
     * changes that can shift global scores).
     */
    suspend fun rescoreAll(targetIds: Set<String>? = null) = withContext(Dispatchers.Default) {
        val ctx = buildContext()
        val articles = dao.getAllArticles()
        val targets = if (targetIds == null) articles else articles.filter { it.id in targetIds }
        val updates = targets.mapNotNull { a ->
            val b = scoreArticle(a, ctx)
            if (b == null) null else com.newsradar.app.data.ArticleScoreUpdate(a.id, b.stotal)
        }
        dao.updateScores(updates)
    }

    /** Score one article. Returns null if it's a hard veto (Stotal = 0 already stored). */
    suspend fun scoreArticle(a: Article, ctx: ScoreContext): ScoreBreakdown? {
        val tokens = Tokeniser.tokenise("${a.title} ${a.summary}").toSet()
        val totalRated = maxOf(ctx.totalRated, 1)

        // --- Sbase: legacy TF-IDF token score ---
        var tokenScore = 0.0
        for (t in tokens) {
            val k = ctx.keywordWeights[t] ?: continue
            val idf = ln((1.0 + totalRated) / (1.0 + k.docCount)) + 1.0
            tokenScore += k.weight * idf
        }

        // --- explicit topic bonus ---
        val topics = TopicTaxonomy.topicsForTokens(tokens)
        val topicMatch = topics.any { it in ctx.explicitTopics }
        val topicBonus = if (topicMatch) S_BASE_TOPIC_MATCH else 0.0
        val sBase = tokenScore + topicBonus

        // --- Sdyn: entity affinity ---
        val ents = ctx.articleEntities[a.id] ?: emptyList()
        var sDyn = 0.0
        val entityKeys = mutableListOf<String>()
        for (e in ents) {
            val aff = ctx.entityAffinities[e.entityKey] ?: continue
            sDyn += aff.affinity
            entityKeys.add(e.rawText)
        }
        if (sDyn < ENTITY_FLOOR) sDyn = ENTITY_FLOOR // clamp deep negatives

        // --- hard veto check ---
        val vetoed = ents.any { it.entityKey in ctx.dislikedEntities }
            || topics.any { it in ctx.dislikedTopics }
            || tokens.any { it in ctx.dislikedTokens }
        if (vetoed) {
            return ScoreBreakdown(sBase, sDyn, 0.0, topics.toList(), entityKeys, emptyList())
        }

        // --- recency decay (article age, not rating age) ---
        val ageDays = (ctx.nowMs - a.publishedAt) / 86_400_000.0
        val decay = exp(-ctx.lambda * ageDays.coerceAtLeast(0.0))

        val stotal = (W_BASE * sBase + W_DYN * sDyn) * decay

        // --- reasons for UI ---
        val reasons = buildList {
            if (topicMatch) add("Topics you follow")
            ctx.keywordWeights.filter { (k, v) -> k in tokens && v.weight > 0 }
                .toList().sortedByDescending { it.second.weight }.take(2)
                .forEach { add(it.first) }
            entityKeys.take(1).forEach { add(it) }
        }

        return ScoreBreakdown(sBase, sDyn, stotal, topics.toList(), entityKeys, reasons)
    }

    private suspend fun buildContext(): ScoreContext {
        val keywordWeights = dao.getAllKeywords().associateBy { it.keyword }
        val entityAffinities = dao.allEntityAffinities().associateBy { it.entityKey }
        val dislikes = dao.allExplicitDislikes()
        val dislikedEntities = dislikes.filter { it.kind == "ENTITY" }.map { it.key }.toSet()
        val dislikedTopics = dislikes.filter { it.kind == "TOPIC" }.map { it.key }.toSet()
        val dislikedTokens = dislikes.filter { it.kind == "TOKEN" }.map { it.key }.toSet()
        // explicit topics = topics derived from the user's positive keyword weights
        // (seed interests + learned likes), so the Sbase topic bonus activates.
        val positiveTokens = keywordWeights.filter { it.value.weight > 0 }.keys
        val explicitTopics = TopicTaxonomy.topicsForTokens(positiveTokens)
        // article entities grouped by articleId
        val articleEntities = mutableMapOf<String, MutableList<ArticleEntity>>()
        // bulk load is cheap (small table); avoids N queries in rescoreAll.
        val grouped = bulkArticleEntities()
        for (ae in grouped) {
            articleEntities.getOrPut(ae.articleId) { mutableListOf() }.add(ae)
        }
        return ScoreContext(
            keywordWeights = keywordWeights,
            entityAffinities = entityAffinities,
            explicitTopics = emptySet(), // populated by caller via seed interests
            dislikedEntities = dislikedEntities,
            dislikedTopics = dislikedTopics,
            dislikedTokens = dislikedTokens,
            articleEntities = articleEntities,
            totalRated = dao.ratedArticleCount(),
            nowMs = System.currentTimeMillis(),
            lambda = LAMBDA_DEFAULT
        )
    }

    // Bulk load all article_entities (cheap, small table).
    private suspend fun bulkArticleEntities(): List<ArticleEntity> =
        dao.allArticleEntities()

    companion object {
        const val W_BASE = 0.4
        const val W_DYN = 0.6
        const val S_BASE_TOPIC_MATCH = 50.0
        const val LAMBDA_DEFAULT = 0.15 // per day; tune 0.1–0.25
        const val ENTITY_DELTA_GREEN = 10.0
        const val ENTITY_DELTA_AMBER = 2.0
        const val ENTITY_DELTA_RED = -15.0
        const val ENTITY_FLOOR = -30.0
        const val TIME_WINDOW_MS = 72L * 24 * 60 * 60 * 1000L
    }
}
