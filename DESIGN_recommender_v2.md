# NewsRadar Recommendation Engine V2 — On-Device Design (Design Only)

Reviewer: Senior Reviewer (subagent). No code changed; this is the implementable spec.
Constraint recap: FREE, sideloaded, OFFLINE-FIRST, single-user, NO backend, NO paid API.
All NLP (NER, topic classification) is dictionary/lexicon + heuristic, 100% on-device Kotlin.

---

## 0. CRITIQUE OF THE HANDOFF PLAN — RISKS

### (a) Reconciling existing TF-IDF token learning with V2 entity-affinity (HIGH risk if mishandled)
The current `KeywordWeight(keyword,weight,docCount)` holds accumulated per-token weights with a
`docCount` used for IDF. This is the user's entire learned profile today. The plan says "keep
token-based learning but ADD entity affinity + topic taxonomy + time decay + feed mixing." Risks:

1. **Orphaned weights.** If V2 scores only from `EntityAffinity` + `TopicTaxonomy`, every existing
   user's hard-earned `KeywordWeight` rows stop contributing and the feed jumps. FIX: keep
   `KeywordWeight` as the **Sbase** signal. Do NOT drop the table.
2. **Dislike semantics break.** Existing `applyDislikes` stores tokens at `weight = -3.0` and
   *soft-sinks* them (comment: "never hard-hidden"). V2 says "Explicit Dislike → Stotal=0 (hard
   veto)." Shipping a hard veto over legacy soft-dislikes is a behavioral regression for existing
   users. FIX: one-time migration exports negative `KeywordWeight` rows into a new
   `explicit_dislikes` table and zeroes them out of `keyword_weights` so they don't double-penalize.
3. **Two RED meanings.** Legacy RED = `-1.5` applied to *tokens* (topic-level). V2 RED = `-15` to
   *entities only, never topics*. These are different signals on different tables — both must run.
   `applyRatingV2` writes BOTH: token delta to `KeywordWeight` (legacy, for Sbase) AND entity delta
   to `EntityAffinity` (for Sdyn). RED on tokens is retained for back-compat Sbase smoothing; RED on
   entities is the new explicit penality.
4. **Time-decay `t` is mis-specified in the spec.** Spec: "e^-lambda*t, t = days since rating."
   That is wrong/ambiguous — unscored or un-rated articles have no rating to measure from. FIX:
   `t = ageDays = (now - publishedAt)/86_400_000` (recency decay of the ARTICLE). This is the only
   definition that scores every article uniformly. Entity-affinity *learning* can additionally decay
   old affinities via `lastTouched`, but that is separate from article scoring `t`.

### (b) On-device NER accuracy vs effort (MEDIUM-HIGH)
Lexicon + capitalized-phrase NER on UK headlines will over-produce junk entities ("The", "UK",
"More", "How", month/day names, every capitalized word). Without a curated entity lexicon,
`EntityAffinity` gets polluted and affinity learning amplifies noise. Mitigations baked into the
design:
- A **capitalized-token stoplist** (pronouns, months, days, "UK"/"The"/"How"/"More", articles).
- A **bundled entity lexicon** (multi-word proper nouns: politicians, parties, clubs, companies,
  countries) giving high-precision hits; capitalized-phrase rule is a lower-priority fallback.
- **Normalization**: `normalise(raw)` lowercases + collapses whitespace + singular/plural fuzz
  (`Trump`↔`Donald Trump`) so affinity isn't split across spellings.
- Accept lower recall early; affinity smoothing + the existing exploration pool cover gaps.
- **Roll out incrementally**: store entities from day one, but gate Sdyn contribution behind a
  feature flag so a weak lexicon can't wreck ranking until the lexicon is decent.

### (c) Feed-mixing cost vs payoff (LOW-MEDIUM)
`getFeedPage` already does ε-greedy (70% guided / 30% random explore). V2's Core 70 / Fringe 20 /
Wildcard 10 is mostly a relabeling. The expensive part is **Fringe** ("Amber/low-positive Sdyn"),
which needs a per-article Sdyn value at query time. Recommendation: ship **two pools** first —
Core (top Stotal) + Wildcard (global trending/explore) — which is a direct rename of today's
guided/explore. Implement Fringe later as a soft band *within* Core (articles with `0 < sDyn` but
below median), not a third query, unless metrics show the feed is too homogeneous. Don't over-build.

### (d) UX scope realism (LOW — plan is already pragmatic)
Agree with the handoff: system font fallbacks (serif headlines / sans UI / monospace source tags),
Peacock Teal `#006D77` accent, surface-contrast instead of card borders, skeleton loaders, mono
source tags. **Source Swipe = skip/defer.** No disagreement; nothing to add.

---

## 1. DATA MODEL (Room) — additive, zero loss

**KEEP** (unchanged schema): `articles`, `keyword_weights`, `outlet_state`, `ArticleScoreUpdate`.
`KeywordWeight` becomes the **Sbase** signal. `Article.score` stays the persisted `Stotal` for
ordering (and `getFeedPage` already filters `rating != 'RED'` + disabled outlets — keep that).

**ADD** three tables (new Room migration; no alter of existing tables → existing users keep everything):

```kotlin
@Entity(tableName = "entity_affinity")
data class EntityAffinity(
    @PrimaryKey val entityKey: String,   // normalized key, e.g. "kier_starmer"
    val rawText: String,                 // canonical display, e.g. "Kier Starmer"
    val type: String,                    // "PERSON" | "ORG" | "GPE" | "PRODUCT" | "EVENT"
    val affinity: Double = 0.0,          // learned weight (GREEN+10 / AMBER+2 / RED-15)
    val docCount: Int = 0,               // # rated articles touching this entity
    val lastTouched: Long = 0L           // epoch ms, for optional affinity decay
)

@Entity(tableName = "article_entities",
        indices = [Index("articleId"), Index("entityKey")])
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleId: String,
    val entityKey: String,
    val type: String
)

@Entity(tableName = "explicit_dislikes")
data class ExplicitDislike(
    @PrimaryKey val key: String,         // entityKey OR topicKey OR token string
    val kind: String                     // "ENTITY" | "TOPIC" | "TOKEN"
)
```

`TopicTaxonomy` is **NOT** a table — it is a bundled static object (a `keyword→topic` map compiled
into the APK, optionally loaded from `assets/taxonomy.json` so it can be updated without a code
release). Topics: Politics, Business, Tech, Science, Health, Sport, Entertainment, UK, World,
Weather, Environment.

Optional (for transparency/debug, not required): add `sBase`, `sDyn` columns to `Article`. Ordering
only needs `score` (= Stotal). Defer unless debugging needs it.

---

## 2. FUNCTION SIGNATURES (new files under engine/)

### engine/EntityExtractor.kt
```kotlin
object EntityExtractor {
    /** Extract normalized entities from title+summary. Pure, deterministic, offline. */
    fun extract(title: String, summary: String): List<ExtractedEntity>

    data class ExtractedEntity(val rawText: String, val type: String, val key: String)

    /** Normalize a surface form to a stable key (lowercase, collapse ws, light singular fuzz). */
    fun normalise(raw: String): String

    /** True if a capitalized token is a known stopword (The, UK, How, months, days…). */
    fun isCapitalizedStopword(tok: String): Boolean
}
```

### engine/TopicTaxonomy.kt
```kotlin
object TopicTaxonomy {
    val TOPICS: Set<String>

    /** Topics whose keyword set intersects the article's tokens. */
    fun topicsForTokens(tokens: Set<String>): Set<String>

    /** Reverse map: tokens that signal a topic (used by taxonomy maintenance). */
    fun tokensForTopic(topic: String): Set<String>

    // Explicit interests come from SettingsStore.seedInterests (onboarding 3-5 topics);
    // Scorer receives them as a Set, not read here.
}
```

### engine/Scorer.kt
```kotlin
class Scorer(private val dao: NewsDao) {

    data class ScoreContext(
        val keywordWeights: Map<String, KeywordWeight>,
        val entityAffinities: Map<String, EntityAffinity>,
        val explicitTopics: Set<String>,     // from seed interests / onboarding
        val dislikedEntities: Set<String>,   // from explicit_dislikes kind=ENTITY
        val dislikedTopics: Set<String>,     // kind=TOPIC
        val dislikedTokens: Set<String>,     // kind=TOKEN (legacy soft-dislikes migrated)
        val articleEntities: List<ArticleEntity>,
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

    /** Score every article in the time window and persist Stotal into Article.score. */
    suspend fun rescoreAll()

    /** Score one article given preloaded context (used by rescoreAll + tests). */
    suspend fun scoreArticle(a: Article, ctx: ScoreContext): ScoreBreakdown

    companion object {
        const val W_BASE = 0.4
        const val W_DYN = 0.6
        const val S_BASE_TOPIC_MATCH = 50.0   // added when an explicit topic matches
        const val LAMBDA_DEFAULT = 0.15       // per day; tune 0.1–0.25
        const val ENTITY_DELTA_GREEN = 10.0
        const val ENTITY_DELTA_AMBER = 2.0
        const val ENTITY_DELTA_RED = -15.0
        const val TIME_WINDOW_MS = 72L * 24 * 60 * 60 * 1000L  // 72h pre-filter
    }
}
```

**Scoring algorithm (inside `scoreArticle`):**
```
legacyScore = Σ over tokens t in article of (kw[t].weight * idf(t))   // existing TF-IDF
topicBonus  = if (articleTopics ∩ explicitTopics ≠ ∅) S_BASE_TOPIC_MATCH else 0.0
sBase = legacyScore + topicBonus     // seeds dominate early (constant 50); real ratings
                                    // (which also raise legacyScore) overtake naturally

sDyn  = Σ over entities E in article of affinity(E)   // clamp floor at, say, -30
        // if article has no extracted entities, sDyn = 0 (neutral, NOT penalized)

ageDays   = (nowMs - a.publishedAt) / 86_400_000.0
timeDecay = exp(-lambda * ageDays)

// HARD VETO (Explicit Dislike → Stotal = 0), replaces legacy soft-sink:
if (any article entity in dislikedEntities
    || any article topic in dislikedTopics
    || any token in dislikedTokens) {
    stotal = 0.0
} else {
    stotal = (W_BASE * sBase + W_DYN * sDyn) * timeDecay
}
reasons = top positive tokens (legacy) + matched explicit topics + top positive entities
```

Notes:
- `sBase` blends legacy token learning AND the V2 topic-seed bonus — this is the key reconciliation:
  existing users keep their token profile; new onboarding topics add the +50 seed on top.
- `sDyn` is entity-only. RED penalizes entities, never topics (per spec).
- `t` uses **recency** (publishedAt), not "days since rating" — see critique 0(a)#4.

### engine/Recommender.kt (extend, keep legacy methods)
```kotlin
// KEEP: applySeeds, applyDislikes (legacy token soft-weights; dislikes now also write explicit_dislikes)
// KEEP: rescoreAll -> reroute to Scorer.rescoreAll()
// KEEP: matchReasons -> Scorer.reasons

/** V2 rating: dual learning — tokens (legacy Sbase) + entities (Sdyn). */
suspend fun applyRatingV2(article: Article, rating: Rating) {
    // 1. legacy token learning (unchanged): token.weight += delta; outlet.weight += delta*0.25
    // 2. entity learning: for each ArticleEntity E of article:
    //      aff += when(rating){ GREEN +10; AMBER +2; RED -15 }
    //      docCount++; lastTouched = now
    // 3. explicit dislike shortcut: if rating == RED AND user long-presses "dislike entity",
    //      insert ExplicitDislike(E.key,"ENTITY")  (UI affordance, optional)
    // 4. rescoreAll()
}
```

### data/NewsDao.kt (add)
```kotlin
@Upsert suspend fun upsertEntityAffinities(list: List<EntityAffinity>)
@Query("SELECT * FROM entity_affinity WHERE entityKey IN (:keys)")
suspend fun getEntityAffinities(keys: List<String>): List<EntityAffinity>
@Query("SELECT * FROM entity_affinity") suspend fun allEntityAffinities(): List<EntityAffinity>

@Insert suspend fun insertArticleEntities(list: List<ArticleEntity>)
@Query("SELECT * FROM article_entities WHERE articleId = :id")
suspend fun entitiesForArticle(id: String): List<ArticleEntity>

@Upsert suspend fun upsertExplicitDislikes(list: List<ExplicitDislike>)
@Query("SELECT * FROM explicit_dislikes") suspend fun allExplicitDislikes(): List<ExplicitDislike>

// Time-boundary pre-filter (perf): only score/select recent, enabled, non-RED articles.
@Query("""SELECT * FROM articles
          WHERE rating != 'RED'
            AND outletId IN (:enabledOutletIds)
            AND publishedAt >= :windowStart
          ORDER BY publishedAt DESC""")
suspend fun getArticlesInWindow(windowStart: Long, enabledOutletIds: List<String>): List<Article>
```

### data/NewsRepository.kt — feed mixing (refactor getFeedPage to Core/Fringe/Wildcard)
```kotlin
suspend fun getFeedPage(page: Int, pageSize: Int = 10, excludeIds: Set<String>): List<Article> {
    val enabled = dao.getOutletStates().filter { it.enabled }.map { it.outletId }
    if (enabled.isEmpty()) return emptyList()

    val windowStart = System.currentTimeMillis() - Scorer.TIME_WINDOW_MS
    val pool = dao.getArticlesInWindow(windowStart, enabled)
        .filter { it.id !in excludeIds }
        .filterNot { isHardVetoed(it) }            // explicit_dislikes (in-memory set)

    // Core 70%: top Stotal
    val coreN   = (pageSize * 0.7).toInt().coerceAtLeast(1)
    val fringeN = (pageSize * 0.2).toInt()
    val wildN   = pageSize - coreN - fringeN

    val byScore = pool.sortedByDescending { it.score }
    val core    = byScore.take(coreN)
    // Fringe 20%: positive but low Sdyn (band within remaining pool)
    val rest    = byScore.drop(coreN)
    val fringe  = rest.filter { it.sDyn > 0 }.take(fringeN)   // needs sDyn column OR recompute
    // Wildcard 10%: global trending by GREEN counts, ignore profile except hard veto
    val wild    = trendingGlobal(wildN, exclude = (core+ /*fringe*/).map { it.id }.toSet())

    return (core + fringe + wild).take(pageSize)   // interleave if desired
}

/** Cold-start fallback: global trending by GREEN counts + onboarding topic seeds. */
suspend fun trendingGlobal(n: Int, exclude: Set<String>): List<Article>
```

Perf approach (matches handoff): time-boundary pre-filter (72h) shrinks the scoring set from
thousands → hundreds; hard exclusions filtered in-memory from a small `explicit_dislikes` set; only
the windowed subset is scored; feed is cached in `Article.score` and recomputed only on
rate/refresh (not per scroll). Fringe/Wildcard queries are cheap because they operate on the same
small windowed pool.

### Cold start
- **Global trending baseline**: count GREEN ratings per entity/topic over last 14 days; rank
  articles whose entities/topics have the highest global GREEN count; this is the Wildcard pool and
  the full feed for a user with zero ratings AND zero seeds.
- **Onboarding 3–5 topics**: `SettingsStore.seedInterests` → `explicitTopics` → Sbase gets +50 per
  matching topic from day one (same mechanism as existing `applySeeds`, which we keep; seeds become
  topics, not raw tokens, in V2 — but legacy token seeds are preserved via `KeywordWeight`).
- A cold user thus sees: 70% trending-Core by Stotal (topic bonus active) + 30% pure Wildcard until
  they rate enough to build entity affinity.

---

## 3. MIGRATION PATH (preserves existing learned weights — THE CRITICAL PART)

Room migration bumps version. **Schema change is purely additive** (3 new tables). Existing
`keyword_weights`, `outlet_state`, `articles` rows are untouched → zero data loss.

One-time post-migration job on first V2 launch (`NewsRepository.migrateV2()`):

1. **Export legacy dislikes → explicit_dislikes, then zero them.**
   ```
   for each KeywordWeight kw where kw.weight < 0:
       insert ExplicitDislike(kw.keyword, kind="TOKEN")
       delete or set kw.weight = 0   // so they don't soft-sink under the new hard-veto
   ```
   This converts "soft sink" into "hard veto" exactly once, avoiding double penalty.

2. **Backfill entity affinity from existing RED ratings** (so learned penalities survive).
   ```
   for each Article a where a.rating == "RED":
       for each ArticleEntity E of a:
           affinity(E.key) -= 15 ; docCount++ ; lastTouched = now
   ```
   (GREEN/AMBER RED-backfill is optional; only RED is load-bearing for "don't show this again".)

3. **Existing positive KeywordWeight rows: leave exactly as-is.** They ARE the Sbase signal;
   `rescoreAll` recomputes Stotal (with decay) on next refresh and overwrites `Article.score`.

4. **Existing `Article.score` values remain valid** as an initial Stotal proxy until the first
   post-migration `rescoreAll` runs (triggered at end of migration job).

Result: an existing user opens V2 and sees essentially the same ranking they had, now with gentle
recency decay + (once they rate) entity affinity on top. No jarring reset.

---

## 4. RECOMMENDED BUILD ORDER (for main agent)

1. Additive Room migration: `entity_affinity`, `article_entities`, `explicit_dislikes` + DAO methods.
2. `EntityExtractor` + `TopicTaxonomy` (bundled lexicon). Wire `extract` into
   `NewsRepository.refresh()` to store `ArticleEntity` rows for new articles.
3. `Scorer` + reroute `Recommender.rescoreAll()` → `Scorer.rescoreAll()`. Keep legacy feed mixing.
4. `applyRatingV2` (dual token+entity learning). Back-compat: legacy `applyRating` can call it.
5. `migrateV2()` one-time job (dislike export + RED backfill). Run on DB open.
6. Refactor `getFeedPage` → Core/Fringe/Wildcard + `trendingGlobal` cold-start.
7. JVM unit tests (Scorer math, EntityExtractor precision, migration preserves weights) + `gradlew
   assembleDebug`.

---

## 5. OPEN QUESTIONS TO CONFIRM WITH MAIN AGENT
- `lambda` default (0.15/day proposed) — tune against feed freshness.
- Fringe as separate query vs soft band within Core (recommend soft band / defer).
- Whether onboarding seeds should be topics (V2) or stay raw tokens (legacy) — recommend BOTH:
  tokens feed Sbase via KeywordWeight (preserved), topics feed the +50 bonus via explicitTopics.
- Source Swipe: confirmed skip/defer.
