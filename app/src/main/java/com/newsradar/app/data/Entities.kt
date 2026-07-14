package com.newsradar.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.ColumnInfo

/** Rating states for an article. */
enum class Rating { NONE, GREEN, AMBER, RED }

/**
 * A news article ingested from an RSS feed.
 * `id` is a stable hash of the link so re-fetches dedupe naturally.
 */
@Entity(
    tableName = "articles",
    indices = [
        Index(value = ["rating", "outletId", "publishedAt"], name = "index_articles_feed")
    ]
)
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
    val score: Double = 0.0,
    // On-demand cached reader body (clean paragraphs joined by "\n\n", with "## "
    // subheading and "• " bullet markers). Null until the user first opens the
    // reader; cached afterwards so the article reads offline.
    val articleBody: String? = null
)

/** Learned weight for a single keyword (token). */
@Entity(tableName = "keyword_weights")
data class KeywordWeight(
    @PrimaryKey val keyword: String,
    val weight: Double,
    // Document frequency: how many rated articles contained this token (for IDF).
    val docCount: Int
)

/** Learned weight for an outlet, plus enabled/disabled toggle from Settings.
 *  `readQuality` is the user's per-provider rating of how well the in-app reader
 *  extracts that outlet's articles: "" (unrated), "GREEN" (clean), "AMBER" (some
 *  issues), "RED" (broken). Shown as a coloured dot in Settings. */
@Entity(tableName = "outlet_state")
data class OutletState(
    @PrimaryKey val outletId: String,
    val enabled: Boolean = true,
    val weight: Double = 0.0,
    val readQuality: String = ""
)

/** Partial-update projection: updates only the `score` column of an article. */
data class ArticleScoreUpdate(
    val id: String,
    val score: Double
)

/**
 * Learned affinity for a single extracted ENTITY (person / org / place / product / event).
 * V2's dynamic score (Sdyn) is the sum of affinities for an article's entities.
 * Reuses the spirit of KeywordWeight but is keyed by a normalised entity key and is
 * separate from the token-based Sbase signal, so legacy token learning is untouched.
 */
@Entity(tableName = "entity_affinity")
data class EntityAffinity(
    @PrimaryKey val entityKey: String,
    val rawText: String,
    val type: String,            // "PERSON" | "ORG" | "GPE" | "PRODUCT" | "EVENT"
    val affinity: Double = 0.0,
    val docCount: Int = 0,
    val lastTouched: Long = 0L
)

/** Join row: which entities were extracted from which article. */
@Entity(
    tableName = "article_entities",
    primaryKeys = ["articleId", "entityKey"],
    indices = [Index("articleId"), Index("entityKey")]
)
data class ArticleEntity(
    val articleId: String,
    val entityKey: String,
    val rawText: String,
    val type: String
)

/** Hard-excluded keys (entity / topic / token). Explicit Dislike -> Stotal = 0 veto. */
@Entity(tableName = "explicit_dislikes")
data class ExplicitDislike(
    @PrimaryKey val key: String,   // entityKey OR topicKey OR token string
    val kind: String               // "ENTITY" | "TOPIC" | "TOKEN"
)

/**
 * A row in the last-100 read articles, persisted for offline keyword search.
 * Deduped by [articleId] (UNIQUE) so re-reading an article just bumps [readAt]
 * and floats it back to the top of the history list.
 */
@Entity(
    tableName = "read_history",
    indices = [Index(value = ["articleId"], unique = true)]
)
data class ReadHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "articleId") val articleId: String,
    val title: String,
    val summary: String,
    val outletName: String,
    val link: String,
    val imageUrl: String?,
    val readAt: Long
)
