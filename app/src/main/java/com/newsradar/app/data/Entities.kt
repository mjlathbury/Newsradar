package com.newsradar.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Rating states for an article. */
enum class Rating { NONE, GREEN, AMBER, RED }

/**
 * A news article ingested from an RSS feed.
 * `id` is a stable hash of the link so re-fetches dedupe naturally.
 */
@Entity(tableName = "articles")
data class Article(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val link: String,
    val imageUrl: String?,
    val outletId: String,
    val outletName: String,
    val publishedAt: Long,
    val fetchedAt: Long,
    val rating: String = Rating.NONE.name,
    // Cached relevance score for the current day's ranking.
    val score: Double = 0.0
)

/** Learned weight for a single keyword (token). */
@Entity(tableName = "keyword_weights")
data class KeywordWeight(
    @PrimaryKey val keyword: String,
    val weight: Double,
    // Document frequency: how many rated articles contained this token (for IDF).
    val docCount: Int
)

/** Learned weight for an outlet, plus enabled/disabled toggle from Settings. */
@Entity(tableName = "outlet_state")
data class OutletState(
    @PrimaryKey val outletId: String,
    val enabled: Boolean = true,
    val weight: Double = 0.0
)

/** Partial-update projection: updates only the `score` column of an article. */
data class ArticleScoreUpdate(
    val id: String,
    val score: Double
)
