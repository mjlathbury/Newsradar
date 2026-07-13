package com.newsradar.app.engine

import com.newsradar.app.data.Article
import com.newsradar.app.data.ArticleEntity
import com.newsradar.app.data.ArticleScoreUpdate
import com.newsradar.app.data.EntityAffinity
import com.newsradar.app.data.ExplicitDislike
import com.newsradar.app.data.KeywordWeight
import com.newsradar.app.data.NewsDao
import com.newsradar.app.data.OutletState
import com.newsradar.app.data.Rating
import com.newsradar.app.engine.EntityExtractor
import com.newsradar.app.engine.Tokeniser
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

    private val SEED_WEIGHT = 5.0 // equivalent to several GREEN ratings per seed token (dominates early)
    private val DISLIKE_WEIGHT = 3.0 // negative weight: pushes disliked topics down, not out

    private fun delta(rating: Rating): Double = when (rating) {
        Rating.GREEN -> 1.5
        Rating.AMBER -> 0.4
        Rating.RED -> -1.5
        Rating.NONE -> 0.0
    }

    /**
     * Seed initial interests (likes). Each token gets a strong positive weight so
     * matching stories rank near the top from day one. Seeds are merged with learned
     * weights, so once the user rates enough, real feedback dominates.
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

    /**
     * Seed dislikes. Each token gets a negative weight so matching stories sink to
     * the bottom of the feed — shown rarely (via the exploration pool) but never
     * hard-hidden, so the model keeps a little training signal on them.
     * Also records an ExplicitDislike (TOKEN) so the V2 hard-veto (Stotal=0) applies
     * consistently with the spec's "Explicit Dislike -> 0" semantics.
     */
    suspend fun applyDislikes(words: List<String>) {
        val tokens = words.flatMap { Tokeniser.tokenise(it) }.toSet().filter { it.isNotBlank() }
        if (tokens.isEmpty()) return
        val existing = dao.getKeywords(tokens).associateBy { it.keyword }
        val updated = tokens.map { t ->
            val cur = existing[t]
            KeywordWeight(
                keyword = t,
                weight = minOf(cur?.weight ?: 0.0, 0.0) - DISLIKE_WEIGHT,
                docCount = maxOf(cur?.docCount ?: 0, 1)
            )
        }
        dao.upsertKeywords(updated)
        dao.upsertExplicitDislikes(tokens.map { ExplicitDislike(it, "TOKEN") })
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

    /** Score every article and persist in one batch. Delegates to the V2 Scorer.
     *  [targetIds] restricts scoring to a subset (warm refresh); null = whole table. */
    suspend fun rescoreAll(targetIds: Set<String>? = null) = Scorer(dao).rescoreAll(targetIds)

    /**
     * V2 rating: dual learning.
     *  - legacy token learning (unchanged): token.weight += delta; outlet.weight += delta*0.25
     *  - entity learning: for each entity in the article, affinity += entity delta (GREEN+10,
     *    AMBER+2, RED-15), docCount++, lastTouched = now. RED penalises ENTITIES only, never topics.
     * Then rescore.
     */
    suspend fun applyRatingV2(article: Article, rating: Rating) {
        // 1. legacy token learning
        applyRating(article, rating)
        if (rating == Rating.NONE) { rescoreAll(); return }

        // 2. entity learning
        val ents = dao.entitiesForArticle(article.id)
        if (ents.isEmpty()) { rescoreAll(); return }
        val d = when (rating) {
            Rating.GREEN -> Scorer.ENTITY_DELTA_GREEN
            Rating.AMBER -> Scorer.ENTITY_DELTA_AMBER
            Rating.RED -> Scorer.ENTITY_DELTA_RED
            else -> 0.0
        }
        val now = System.currentTimeMillis()
        val existing = dao.getEntityAffinities(ents.map { it.entityKey }).associateBy { it.entityKey }
        val updated = ents.map { e ->
            val cur = existing[e.entityKey]
            EntityAffinity(
                entityKey = e.entityKey,
                rawText = e.rawText,
                type = e.type,
                affinity = (cur?.affinity ?: 0.0) + d,
                docCount = (cur?.docCount ?: 0) + 1,
                lastTouched = now
            )
        }
        dao.upsertEntityAffinities(updated)
        rescoreAll()
    }

    /**
     * Revert a previous rating: subtract the learning deltas it added (token +
     * outlet + entity affinity) so the model returns to its prior state. Called
     * by the rating "failsafe" — tapping the active rating again clears it.
     * If [prev] is NONE there is nothing to undo.
     */
    suspend fun unrate(article: Article, prev: Rating) {
        if (prev == Rating.NONE) { rescoreAll(); return }

        // 1. reverse legacy token + outlet learning
        val d = -delta(prev)
        val tokens = Tokeniser.tokenise("${article.title} ${article.summary}").toSet()
        val existing = dao.getKeywords(tokens.toList()).associateBy { it.keyword }
        val updatedKeywords = tokens.mapNotNull { t ->
            val cur = existing[t] ?: return@mapNotNull null
            KeywordWeight(t, cur.weight + d, cur.docCount + 1)
        }
        if (updatedKeywords.isNotEmpty()) dao.upsertKeywords(updatedKeywords)

        val os = dao.getOutletState(article.outletId)
        if (os != null) dao.upsertOutletState(os.copy(weight = os.weight + d * 0.25))

        // 2. reverse entity learning
        val ents = dao.entitiesForArticle(article.id)
        if (ents.isNotEmpty()) {
            val ed = -when (prev) {
                Rating.GREEN -> Scorer.ENTITY_DELTA_GREEN
                Rating.AMBER -> Scorer.ENTITY_DELTA_AMBER
                Rating.RED -> Scorer.ENTITY_DELTA_RED
                else -> 0.0
            }
            val existingEnt = dao.getEntityAffinities(ents.map { it.entityKey })
                .associateBy { it.entityKey }
            val updatedEnt = ents.mapNotNull { e ->
                val cur = existingEnt[e.entityKey] ?: return@mapNotNull null
                EntityAffinity(
                    entityKey = e.entityKey,
                    rawText = e.rawText,
                    type = e.type,
                    affinity = cur.affinity + ed,
                    docCount = (cur.docCount + 1).coerceAtLeast(0),
                    lastTouched = cur.lastTouched
                )
            }
            if (updatedEnt.isNotEmpty()) dao.upsertEntityAffinities(updatedEnt)
        }
        rescoreAll()
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

    /**
     * Interest chips for the feed card.
     *
     * IMPORTANT: chips must reflect ONLY the user's own signal — typed seed
     * interests, or keywords/entities they've actually GREEN-rated. We do NOT show
     * the article's intrinsic topic (e.g. "Politics") or its named entities, because
     * those describe the story itself and would appear on every card even after a
     * full data reset with zero ratings — reading as the app "choosing" interests
     * for the user. After a reset with no input, this returns an empty list
     * (no chips), which is correct.
     */
    suspend fun reasonsForArticle(article: Article): List<String> {
        val tokens = Tokeniser.tokenise("${article.title} ${article.summary}").toSet()
        val entities = EntityExtractor.extract(article.title, article.summary)
            .associateBy { it.key }

        // Positive learned signal only (weight > 0): seeded interests + liked tokens/entities.
        val kw = dao.getKeywords(tokens.toList()).filter { it.weight > 0 }
            .associateBy { it.keyword }
        val entAff = dao.getEntityAffinities(entities.keys.toList())
            .filter { it.affinity > 0 }.associateBy { it.entityKey }

        return buildList {
            // 1) matched seed/liked keyword interests (real user signal)
            kw.keys.take(2).forEach { add(it) }
            // 2) matched seed/liked named entities (real user signal)
            entities.values.filter { it.key in entAff }.take(2)
                .forEach { add(it.rawText) }
        }
    }

    /**
     * One-time V2 migration (idempotent). Preserves existing learned weights while
     * adopting the V2 hard-veto semantics:
     *  1. Legacy soft-dislikes (negative KeywordWeight) -> ExplicitDislike(TOKEN) and
     *     zeroed, so they stop soft-sinking and instead hard-veto consistently.
     *  2. Existing RED-rated articles -> backfill entity affinity (-15) so learned
     *     "don't show again" survives. GREEN/AMBER RED-backfill skipped (only RED
     *     is load-bearing).
     * Call once on first V2 launch; safe to call again (upserts are idempotent).
     */
    suspend fun migrateV2() = withContext(Dispatchers.Default) {
        // 0. one-time hygiene: drop any noise/stop-word keyword rows inherited
        //    from earlier builds (e.g. "take", "year", "easy") so they can't leak
        //    into scoring or chips.
        val noise = dao.getAllKeywords().map { it.keyword }.filter { Tokeniser.isStopWord(it) }
        if (noise.isNotEmpty()) dao.deleteKeywords(noise)

        // 1. soft-dislikes -> explicit dislikes (only those not already recorded)
        val neg = dao.getAllKeywords().filter { it.weight < 0 }
        val existingDislikes = dao.allExplicitDislikes().map { it.key }.toSet()
        val newNeg = neg.filter { it.keyword !in existingDislikes }
        if (newNeg.isNotEmpty()) {
            dao.upsertExplicitDislikes(newNeg.map { ExplicitDislike(it.keyword, "TOKEN") })
            dao.upsertKeywords(neg.map { it.copy(weight = 0.0) })
        }
        // 2. backfill entity affinity from RED ratings — idempotent: only for entities
        //    that have no affinity row yet (avoids double -15 on re-run).
        val reds = dao.getAllArticles().filter { it.rating == Rating.RED.name }
        for (a in reds) {
            val ents = dao.entitiesForArticle(a.id)
            if (ents.isEmpty()) continue
            val existing = dao.getEntityAffinities(ents.map { it.entityKey })
                .associateBy { it.entityKey }
            val now = System.currentTimeMillis()
            val updated = ents.filter { existing[it.entityKey] == null }.map { e ->
                EntityAffinity(
                    entityKey = e.entityKey,
                    rawText = e.rawText,
                    type = e.type,
                    affinity = (existing[e.entityKey]?.affinity ?: 0.0) + Scorer.ENTITY_DELTA_RED,
                    docCount = (existing[e.entityKey]?.docCount ?: 0) + 1,
                    lastTouched = now
                )
            }
            if (updated.isNotEmpty()) dao.upsertEntityAffinities(updated)
        }
        rescoreAll()
    }
}
