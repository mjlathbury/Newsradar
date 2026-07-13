package com.newsradar.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NewsDao {

    // ---- Articles ----
    // Insert new articles; for ones whose id already exists (re-fetched story) we
    // IGNORE the insert but then refresh the feed-derived fields (summary, image,
    // dates) WITHOUT clobbering the user/recommender state (rating, score,
    // cached articleBody) — a plain REPLACE would delete+reinsert and lose those.
    // The insert+update dance is orchestrated in NewsRepository.refresh() so this
    // interface stays body-free (Room DAOs can't declare methods with bodies).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(articles: List<Article>)

    /** Cache the extracted reader body for offline reads. */
    @Query("UPDATE articles SET articleBody = :body WHERE id = :id")
    suspend fun updateArticleBody(id: String, body: String)

    @Query(
        "UPDATE articles SET summary = :summary, imageUrl = :imageUrl, " +
        "publishedAt = :publishedAt, fetchedAt = :fetchedAt WHERE id = :id"
    )
    suspend fun updateFeedFieldsRow(
        id: String,
        summary: String,
        imageUrl: String?,
        publishedAt: Long,
        fetchedAt: Long
    )

    @Query(
        "UPDATE articles SET summary = :summary, " +
        "publishedAt = :publishedAt, fetchedAt = :fetchedAt WHERE id = :id"
    )
    suspend fun updateFeedFieldsRowNoImage(
        id: String,
        summary: String,
        publishedAt: Long,
        fetchedAt: Long
    )

    /** Overwrite an article's hero image (used to replace a poisoned/signed feed
     *  URL — e.g. Guardian's UA-pinned ?s= — with the page's unsigned og:image). */
    @Query("UPDATE articles SET imageUrl = :imageUrl WHERE id = :id")
    suspend fun updateArticleImage(id: String, imageUrl: String)

    /** Feed sorted by learned relevance score, then recency. Excludes RED-rated
     *  and any articles from disabled outlets. Uses an *allowlist* (IN enabled)
     *  so that turning every source off yields a guaranteed-blank feed (a
     *  blocklist would let through any article whose outletId isn't tracked). */
    @Query(
        "SELECT * FROM articles WHERE rating != 'RED' " +
        "AND outletId IN (:enabledOutletIds) " +
        "ORDER BY score DESC, publishedAt DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getFeedPage(limit: Int, offset: Int, enabledOutletIds: List<String>): List<Article>

    /** Ids (from [ids]) that already exist in the articles table. Used to skip
     *  redundant on-device entity extraction for unchanged articles on refresh. */
    @Query("SELECT id FROM articles WHERE id IN (:ids)")
    suspend fun getExistingIds(ids: List<String>): List<String>

    /** Exploration pool: random un-RED articles, excluding disabled outlets. */
    @Query(
        "SELECT * FROM articles WHERE rating != 'RED' " +
        "AND outletId IN (:enabledOutletIds) " +
        "ORDER BY RANDOM() LIMIT :limit"
    )
    suspend fun getFeedRandom(limit: Int, enabledOutletIds: List<String>): List<Article>

    @Query("UPDATE articles SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: String, rating: String)

    /** Batch score update — one transaction for the whole feed. */
    @Update(entity = Article::class)
    suspend fun updateScores(updates: List<ArticleScoreUpdate>)

    @Query("SELECT * FROM articles")
    suspend fun getAllArticles(): List<Article>

    /** Housekeeping: drop articles older than the cutoff (keeps DB small). */
    @Query("DELETE FROM articles WHERE fetchedAt < :cutoff AND rating = 'NONE'")
    suspend fun pruneOld(cutoff: Long)

    // ---- Keyword weights ----
    @Upsert
    suspend fun upsertKeywords(kws: List<KeywordWeight>)

    @Query("SELECT * FROM keyword_weights WHERE keyword IN (:keys)")
    suspend fun getKeywords(keys: List<String>): List<KeywordWeight>

    @Query("SELECT * FROM keyword_weights")
    suspend fun getAllKeywords(): List<KeywordWeight>

    /** Delete noise/stop-word keyword rows so they can't pollute scoring or chips. */
    @Query("DELETE FROM keyword_weights WHERE keyword IN (:keys)")
    suspend fun deleteKeywords(keys: List<String>)

    @Query("SELECT COUNT(*) FROM articles WHERE rating != 'NONE'")
    suspend fun ratedArticleCount(): Int

    // ---- Outlet state ----
    @Upsert
    suspend fun upsertOutletState(state: OutletState)

    @Query("SELECT * FROM outlet_state")
    fun observeOutletStates(): kotlinx.coroutines.flow.Flow<List<OutletState>>

    @Query("SELECT * FROM outlet_state")
    suspend fun getOutletStates(): List<OutletState>

    @Query("SELECT * FROM outlet_state WHERE outletId = :id")
    suspend fun getOutletState(id: String): OutletState?

    // ---- Entity affinity (V2) ----
    @Upsert
    suspend fun upsertEntityAffinities(list: List<EntityAffinity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticleEntities(list: List<ArticleEntity>)

    @Query("SELECT * FROM entity_affinity WHERE entityKey IN (:keys)")
    suspend fun getEntityAffinities(keys: List<String>): List<EntityAffinity>

    @Query("SELECT * FROM entity_affinity")
    suspend fun allEntityAffinities(): List<EntityAffinity>

    @Query("SELECT * FROM article_entities")
    suspend fun allArticleEntities(): List<ArticleEntity>

    @Query("SELECT * FROM article_entities WHERE articleId = :id")
    suspend fun entitiesForArticle(id: String): List<ArticleEntity>

    @Query("SELECT * FROM article_entities WHERE articleId IN (:ids)")
    suspend fun entitiesForArticles(ids: List<String>): List<ArticleEntity>

    // ---- Explicit dislikes (V2 hard veto) ----
    @Upsert
    suspend fun upsertExplicitDislikes(list: List<ExplicitDislike>)

    @Query("SELECT * FROM explicit_dislikes")
    suspend fun allExplicitDislikes(): List<ExplicitDislike>

    /** Time-boundary pre-filter (perf): recent, non-RED, enabled-outlet articles. */
    @Query(
        "SELECT * FROM articles WHERE rating != 'RED' " +
        "AND outletId IN (:enabledOutletIds) " +
        "AND publishedAt >= :windowStart " +
        "ORDER BY publishedAt DESC"
    )
    suspend fun getArticlesInWindow(
        windowStart: Long,
        enabledOutletIds: List<String>
    ): List<Article>

    /** Global trending pool for cold-start / Wildcard: top GREEN-rated articles. */
    @Query(
        "SELECT * FROM articles WHERE rating = 'GREEN' " +
        "AND outletId IN (:enabledOutletIds) " +
        "ORDER BY publishedAt DESC LIMIT :limit"
    )
    suspend fun getTrendingGreen(limit: Int, enabledOutletIds: List<String>): List<Article>

    // ---- Read history (last 100 read articles, keyword searchable) ----
    /** Insert or update a read-history row. Because [ReadHistory.articleId] is
     *  UNIQUE, re-reading the same article replaces the row and bumps readAt. */
    @Upsert
    suspend fun upsertReadHistory(entry: ReadHistory)

    /** Substring search over title + summary. [pattern] is pre-wrapped with
     *  "%...%" by the repository (Room can't concatenate LIKE wildcards inline). */
    @Query(
        "SELECT * FROM read_history " +
        "WHERE title LIKE :pattern OR summary LIKE :pattern " +
        "ORDER BY readAt DESC"
    )
    fun searchReadHistory(pattern: String): Flow<List<ReadHistory>>

    /** Keep only the 100 most-recently-read rows (newest by readAt). */
    @Query(
        "DELETE FROM read_history WHERE id NOT IN (" +
        "SELECT id FROM read_history ORDER BY readAt DESC LIMIT 100)"
    )
    suspend fun pruneReadHistory()
}
