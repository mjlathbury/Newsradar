package com.newsradar.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert

@Dao
interface NewsDao {

    // ---- Articles ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(articles: List<Article>)

    /** Feed sorted by learned relevance score, then recency. Excludes RED-rated. */
    @Query(
        "SELECT * FROM articles WHERE rating != 'RED' " +
        "ORDER BY score DESC, publishedAt DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun getFeedPage(limit: Int, offset: Int): List<Article>

    @Query("UPDATE articles SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: String, rating: String)

    /** Cache the fetched article body text (on-demand summary source). */
    @Query("UPDATE articles SET summaryText = :text WHERE id = :id")
    suspend fun setSummaryText(id: String, text: String)

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
}
