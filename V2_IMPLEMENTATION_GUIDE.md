# NewsRadar V2 — Implementation Guide (Design Only)

Scope: Recommender V2 (entity affinity + topic taxonomy + time decay + Core70/Fringe20/Wildcard10 + cold start), the **No Noise** UX (Peacock Teal #006D77, borderless surface-contrast cards, `FontFamily.Monospace` source tags, skeleton loading, system fonts only), and skeleton loading. No code edits — this is a design + techniques + pitfalls + sources document.

All file paths below are real and inspected:
- `app/src/main/java/com/newsradar/app/data/NewsDao.kt`, `Entities.kt`, `NewsDatabase.kt`, `NewsRepository.kt`
- `app/src/main/java/com/newsradar/app/engine/Recommender.kt`, `Tokeniser.kt`
- `app/src/main/java/com/newsradar/app/ui/FeedScreen.kt`, `ArticleCard.kt`, `ReaderOverlay.kt`, `theme/Theme.kt`, `theme/Type.kt`
- `app/src/main/java/com/newsradar/app/rss/ArticleFetcher.kt`

---

## 0. Current-state facts you must design around

- **DB:** `NewsDatabase` is `version = 3`, `exportSchema = false`, `fallbackToDestructiveMigration()`. A schema change for V2 MUST add a real `Migration(3, 4)` (or bump + migrate) because `fallbackToDestructiveMigration()` will otherwise wipe `keyword_weights` (the learned entity-affinity store) and `outlet_state` on every upgrade. Preserving learned weights across the V2 upgrade is a hard requirement.
- **Pagination today:** `NewsDao.getFeedPage(limit, offset, enabledOutletIds)` is **offset-based** (`ORDER BY score DESC, publishedAt DESC LIMIT :limit OFFSET :offset`). `NewsRepository.getFeedPage` already does ε-greedy **mixing** (current `EXPLORATION_RATIO = 0.3`) by interleaving `getFeedPage` (guided) with `getFeedRandom` (explore). So the mixing machinery *already exists* as manual offset pagination — V2 only changes the bucket ratios and splits "guided" into Core + Fringe.
- **Scoring today:** `Recommender.rescoreAll()` runs on `Dispatchers.Default`, tokenises every article's title+summary on the fly, and rewrites **every** `Article.score` on each refresh AND each rating. There is no time decay and no persisted per-article entity list. This is the expensive path V2 must make incremental.
- **Entity-affinity store:** `KeywordWeight(keyword, weight, docCount)` is exactly the token→weight table V2 reuses. `Recommender.applyRating` / `applySeeds` / `applyDislikes` already write to it. Topic taxonomy and entity affinity both map onto this table (add a `kind`/`taxonomy` column or a parallel `entity_weights` table — see §3).
- **Theme:** `Theme.kt` already builds "No Noise" surfaces (charcoal `0xFF0A0A0A` / paper `0xFFFFFFFF`, with `surface` a step lighter and NO dividers). `colorScheme = TEAL` is the default. Peacock Teal `#006D77` should become the **accent** (`primary`/`tertiary`), not just a palette member. `ArticleCard` currently draws a **2.dp `BorderStroke(ageColor)`** — that directly contradicts the "no borders" No-Noise brief and must be removed (see §6).

---

## (a) Feed mixing: manual offset pagination vs Paging3

**Recommendation: KEEP the existing manual offset pagination. Do NOT adopt Paging3.**

Rationale grounded in the code:
1. The feed is **not a single flat list** — it is three interleaved buckets (Core70 / Fringe20 / Wildcard10) plus dedup across pages (`excludeIds`) and an allowlist of enabled outlets. Paging3's `PagingSource`/`PagingData` is built for one ordered query source; three heterogeneous buckets with cross-bucket dedup fight the library. You'd end up faking a `PagingSource` that internally does the same manual mixing — paying Paging3's complexity for nothing.
2. `getFeedPage` is already correct and offline (`LIMIT/OFFSET` against Room, no network). Paging3's main win (network+DB caching via `RemoteMediator`) is irrelevant for a purely local DB.
3. The existing infinite-scroll trigger in `FeedScreen.kt` (`snapshotFlow` over `listState.layoutInfo`, load when `total - last <= 5`) is the idiomatic Compose pattern and already works. Paging3 would replace it with `LazyPagingItems`, but you'd lose the custom interleave.

**Concrete V2 change to the manual mixer** (`NewsRepository.getFeedPage`):

```kotlin
// V2 bucket ratios — replace EXPLORATION_RATIO = 0.3 model.
private val CORE_RATIO    = 0.70f   // highest-score, on-affinity (your interests)
private val FRINGE_RATIO  = 0.20f   // mid-score / adjacent-taxonomy (gentle explore)
private val WILDCARD_RATIO = 0.10f  // pure RANDOM() (cold exploration, anti-bubble)

suspend fun getFeedPage(page: Int, pageSize: Int = 10, excludeIds: Set<String> = emptySet()): List<Article> {
    val enabled = dao.getOutletStates().filter { it.enabled }.map { it.outletId }
    if (enabled.isEmpty()) return emptyList()

    val coreN    = (pageSize * CORE_RATIO).toInt().coerceAtLeast(1)
    val fringeN  = (pageSize * FRINGE_RATIO).toInt()
    val wildN    = (pageSize - coreN - fringeN).coerceAtLeast(1)

    // Core: existing guided query, offset by coreN.
    val core = dao.getFeedPage(coreN, page * coreN, enabled).toMutableList()
    // Fringe: mid band — offset past the top coreN*page+coreN, take fringeN.
    val fringe = dao.getFeedPageFringe(fringeN, page * coreN + coreN, enabled)
    // Wildcard: random, deduped against already-shown ids.
    val wildPool = dao.getFeedRandom(wildN * 5 + pageSize, enabled)
        .filter { it.id !in excludeIds && it.id !in core.map{it.id} && it.id !in fringe.map{it.id} }
    val wild = wildPool.take(wildN)

    // Interleave: core, then sprinkle fringe+wildcard so variety is spread, not dumped.
    return interleaveByRatio(core, fringe, wild, pageSize)
}
```

Add to `NewsDao`:
```kotlin
// Fringe = the NEXT band of scored articles (not the top), i.e. slightly-off-interest.
@Query("SELECT * FROM articles WHERE rating != 'RED' AND outletId IN (:enabled) " +
       "ORDER BY score DESC, publishedAt DESC LIMIT :limit OFFSET :offset")
suspend fun getFeedPageFringe(limit: Int, offset: Int, enabled: List<String>): List<Article>
```

**Pitfalls to avoid:**
- **Offset pagination cost:** `OFFSET` re-scans skipped rows. At NewsRadar's scale (a week of UK headlines, low thousands of rows) this is fine; do NOT prematurely optimize to keyset pagination. If it grows, keyset on `score, publishedAt, id` is the upgrade path.
- **`getFeedRandom` uses `ORDER BY RANDOM()`** — fine for the small wildcard pool, but cap the pool (done: `wildN*5+pageSize`). Never `RANDOM()` over the whole table.
- **Dedup correctness:** the current backfill loop (`while (merged.size < pageSize)`) can re-add an excluded id if `getFeedPage(1, backfill, ...)` returns one already in `excludeIds`. Keep the `extra.id in excludeIds` guard (it's already there) and also guard against `merged.any { it.id == extra.id }`.
- **Cold start:** when `ratedArticleCount() == 0` and no seeds, Core is meaningless. Detect cold start in the VM and force `wildN = pageSize` (pure exploration) until the first GREEN/seed arrives. Surface a one-time "teach me" seed prompt (the `setSeedInterests` path already exists).

Sources:
- Paging from cache to LazyColumn (offset vs Paging3 tradeoffs): https://proandroiddev.com/paging-in-android-jetpack-compose-from-caching-data-with-room-to-displaying-in-lazycolumn-a018f0b6cb2
- Paging3 + Room: https://developer.android.com/topic/libraries/architecture/paging/v3-network-db
- Why Paging3 still uses offset under the hood (and when keyset matters): https://stackoverflow.com/questions/76184361/paging3-in-android-how-come-uses-inefficient-offset-limit-pagination

---

## (b) Skeleton loading pattern in Compose LazyColumn

**Recommendation: a reusable `Modifier.shimmer()` + a `SkeletonCard` composable rendered as the first page's placeholder, NOT a full-screen `CircularProgressIndicator`.**

Current `FeedScreen.kt` shows a centered `CircularProgressIndicator` while `state.loading && articles.isEmpty()`. For No-Noise, replace that with a **shimmer skeleton list** that matches `ArticleCard`'s exact layout (hero block + source row + title + 2 summary lines + action row) so the transition into real content is seamless.

**Shimmer modifier (no extra dependency, pure Compose):**
```kotlin
fun Modifier.shimmer(show: Boolean): Modifier = composed {
    if (!show) return@composed this
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x = transition.animateFloat(0f, 1000f, infiniteRepeatable(tween(1200, easing = LinearEasing)))
    val c1 = MaterialTheme.colorScheme.surfaceVariant
    val c2 = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    background(
        brush = remember(x.value) {
            Brush.horizontalGradient(
                0.0f to c2, 0.5f to c1, 1.0f to c2,
                startX = x.value - 500f, endX = x.value + 500f
            )
        }
    )
}
```

**Skeleton card mirrors ArticleCard's geometry** (same paddings/radii so layout doesn't jump):
```kotlin
@Composable
fun SkeletonCard() {
    Card(Modifier.fillMaxWidth().padding(horizontal=12.dp, vertical=6.dp),
         shape = RoundedCornerShape(16.dp),
         colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(4.dp)).shimmer(true))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(4.dp)).shimmer(true))
            Spacer(Modifier.height(6.dp))
            repeat(2) { Box(Modifier.fillMaxWidth(fraction = if (it==0) 1f else 0.7f).height(14.dp)
                .clip(RoundedCornerShape(4.dp)).shimmer(true)); Spacer(Modifier.height(6.dp)) }
        }
    }
}
```

**In FeedScreen:** replace the `CircularProgressIndicator` initial branch with:
```kotlin
state.loading && state.articles.isEmpty() ->
    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
        items(6) { SkeletonCard() }
    }
```

**Pitfalls to avoid:**
- **Don't animate shimmer on the main thread with a manual `animate*AsState` loop** — use `rememberInfiniteTransition` (GPU/compositor driven, no recomposition storm).
- **Don't render skeletons inside the real `items()` list using a fake model** — keep a separate `loading` branch; mixing a skeleton item into `state.articles` corrupts `key = { it.id }` and scroll position.
- **Respect reduced-motion:** gate shimmer behind `androidx.compose.foundation.isSystemInDarkTheme()` is wrong; use `androidx.compose.ui.platform.Accessibility` / `ReduceMotionController` (Compose Material3 respects `MotionScheme` in 2024.09 BOM — `composeOptions kotlinCompilerExtensionVersion = "1.5.14"` already in `build.gradle.kts`). When `MotionScheme` is `ReduceMotion`, swap shimmer for a static `surfaceVariant` block.
- **Coil images:** hero `AsyncImage` already uses `crossfade(true)`. Keep skeletons only for the *initial* load; per-card image loading uses Coil's own `AsyncImagePainter.State.Loading` placeholder, not a list-level skeleton.

Sources:
- Shimmer/skeleton in Compose (modifier approach, no lib): https://dev.to/myougatheaxo/shimmer-loading-effect-skeleton-screen-in-jetpack-compose-2feb
- Seamless shimmer integration with existing Compose code: https://proandroiddev.com/seamless-shimmer-integration-with-existing-compose-code-b95cc3bbcd17
- `placeholder-compose` library (optional, if you'd rather not hand-roll): https://github.com/RevenueCat/placeholder-compose

---

## (c) Storing per-article extracted entities (new Room entity vs JSON column)

**Recommendation: a new normalized join entity `ArticleEntity` (article_id, keyword, weight, kind) — NOT a JSON blob column on `Article`.**

Why not a JSON column (`val entities: String?` with `@TypeConverter`):
- V2 scoring needs to **join article entities against `keyword_weights`** (`weight`, `docCount`/IDF) per article, every rescore. A JSON blob forces you to deserialize every article's entities in Kotlin (what `rescoreAll` already does for title+summary, O(N) tokenisation). A join table lets SQL do the heavy lifting in one query and lets you add/remove entities incrementally.
- You also want **per-article entity provenance for "Matched your interests" reasons** and for the topic-taxonomy rollup — a queryable table serves both.

**New entity:**
```kotlin
@Entity(
    tableName = "article_entities",
    primaryKeys = ["articleId", "keyword"],
    foreignKeys = [ForeignKey(entity = Article::class, parentColumns=["id"],
        childColumns=["articleId"], onDelete = ForeignKey.CASCADE)]
)
data class ArticleEntity(
    val articleId: String,
    @ColumnInfo(index = true) val keyword: String,
    val weight: Double,            // TF or occurrence count in THIS article
    val kind: String = "TOKEN"     // TOKEN | ENTITY (NER) | TOPIC (taxonomy node)
)
```

**DAO:** `INSERT` extracted entities once at ingest (in `NewsRepository.refresh()` right after `insertArticles`/`updateFeedFields`), keyed by `articleId`. Use `@Upsert` on a list. Add:
```kotlin
@Query("""SELECT ae.keyword, kw.weight, kw.docCount, ae.weight
          FROM article_entities ae JOIN keyword_weights kw ON ae.keyword = kw.keyword
          WHERE ae.articleId = :id AND kw.weight > 0
          ORDER BY kw.weight * ae.weight DESC LIMIT :max""")
suspend fun matchedReasons(id: String, max: Int): List<ReasonRow>
```
This replaces `Recommender.matchReasons` (which re-tokenises title+summary) with a pure SQL lookup — faster and uses the *actual* extracted entities.

**When to extract entities:** during `refresh()`, not on rating. You already have the RSS `summary`; optionally run lightweight NER on `title+summary` (no need for the full Readability body — that's on-demand in `ArticleFetcher`). Keep extraction cheap: tokenise + stem (reuse `Tokeniser`) + optional a small offline NER (e.g. a curated UK-org/person dictionary, since you can't bundle a model easily offline at minSdk 26). Persist once; never re-extract on rescore.

**Migration:** add `Migration(3,4)` that `CREATE TABLE article_entities (...)` with the FK + index. Do NOT rely on `fallbackToDestructiveMigration()` (wipes learned weights). Keep `exportSchema = false` but write the migration in `NewsDatabase`.

**Pitfalls to avoid:**
- **`@TypeConverter` JSON trap:** if you do go JSON, never put a `List`/`Map` in an `@Entity` without a `@TypeConverter` — Room will fail at compile with "Cannot figure out how to save this field." Even then, prefer the join table for queryability.
- **Foreign key + `fallbackToDestructiveMigration()`:** adding an FK-backed table without a migration = destructive wipe. Pin a real migration.
- **Write volume:** extracting entities for every fetched article each refresh multiplies writes. Batch upsert in one transaction (`@Transaction`) and only for *new* articles (the `insertArticles` IGNORE path already tells you which are new — capture returned ids or re-query `WHERE fetchedAt = :now AND rating='NONE'`).

Sources:
- Room complex data / TypeConverters: https://developer.android.com/training/data-storage/room/referencing-data
- TypeConverter List<Object> Room: https://stackoverflow.com/questions/64310106/how-to-typeconverter-listobject-room
- Room relationships (one-to-many join table): https://developer.android.com/training/data-storage/room/relationships

---

## (d) Time-decay scoring without per-refresh full rescore (cache + incremental)

**The problem:** `rescoreAll()` rewrites every `Article.score` on every refresh and every rating — O(N) tokenisation + join each time. With time decay `e^{-λt}` the score for an *unrated, unchanged* article only changes because `t` (age) changed. You can avoid recomputing affinity entirely.

**Recommendation: split the score into a cached, age-independent affinity component + a cheap live time-decay multiplier.**

1. **Persist the affinity component** (`baseScore`) per article once, when entities are extracted/updated — NOT the final score. Add `val baseScore: Double = 0.0` to `Article` (or keep `score` as base and compute display score live).
   - `baseScore = Σ over article entities ( entity.weight_learned * idf * localWeight ) + outletWeight` — computed only when entities change (ingest or rating), not on refresh.
2. **Compute the displayed/feed score live** with time decay, cheaply, in SQL or in the mixer:
   ```
   displayScore = baseScore * exp(-lambda * ageHours)
   ```
   `lambda` tuned so a story halves relevance in ~12–24h (UK news is perishable). `ageHours = (now - publishedAt)/3.6e6`.
3. **Do the decay in SQL** so you never materialise it per refresh:
   ```kotlin
   @Query("""SELECT *, (baseScore * exp(-:lambda * ((:now - publishedAt)/3600000.0))) AS dispScore
             FROM articles WHERE rating != 'RED' AND outletId IN (:enabled)
             ORDER BY dispScore DESC, publishedAt DESC LIMIT :limit OFFSET :offset""")
   suspend fun getFeedPageTimedecay(limit: Int, offset: Int, enabled: List<String>,
                                    lambda: Double, now: Long): List<ArticleWithScore>
   ```
   (Return a `@Embedded`/`@Relation` projection `ArticleWithScore` carrying `dispScore`.)
4. **Incremental on rating:** when the user rates, only the *rated* article's `baseScore` and the `keyword_weights` rows it touched change. Update just that one article's `baseScore` (`@Query("UPDATE articles SET baseScore = :s WHERE id=:id")`) instead of `rescoreAll()`. The decay multiplier is applied live at query time, so nothing else needs touching.

**Cold-start / seed interaction:** seeds/dislikes write to `keyword_weights`; the *next* ingest recomputes `baseScore` for affected articles. To make seeds take effect immediately without a full rescore, recompute `baseScore` only for articles whose entities intersect the changed keywords (query `article_entities WHERE keyword IN (:changed)` → update just those).

**Pitfalls to avoid:**
- **Don't `UPDATE articles SET score = ...` for all rows on every refresh** — that's the current O(N) cost and the bug to kill.
- **`exp()` in SQLite:** available in API 26+ (SQLite 3.18+). Verify on a minSdk 26 emulator; if unavailable, compute the multiplier in Kotlin over the already-fetched page (pages are tiny — 10 rows).
- **`now` drift:** pass `System.currentTimeMillis()` as a bind arg (`:now`) so every row in a page uses the same timestamp — otherwise rows computed at different ms skew ordering.
- **Pruning vs decay:** keep `pruneOld` (drops `NONE` older than 7d) so the table stays small; decay handles ranking, pruning handles storage.
- **Never block main thread:** all of this is already in `suspend` DAOs / `Dispatchers.Default` in `Recommender` — keep it there. Room queries are main-thread-safe only via `suspend`/`Flow`; the existing code already uses `suspend`.

Sources:
- On-device recommender systems survey (incremental/update chapter): https://arxiv.org/html/2401.11441v1
- On-device recommendation engine SDK (category/brand affinity, incremental): https://vickycodes.com/post/building-on-device-recommendation-engine-sdk-android/
- Web Conference 2024 tutorial slides (ODRS training/updating): https://www2024.thewebconf.org/docs/tutorial-slides/on-device-recommender-systems.pdf

---

## 1. V2 recommender architecture (entity affinity + taxonomy + decay + mixing + cold start)

Layered model, all reusing existing `keyword_weights` as the affinity store:

| Layer | Where | Technique |
|---|---|---|
| **Entity extraction** | `NewsRepository.refresh()` → `article_entities` | tokenise+stem (`Tokeniser`) + optional dictionary NER; persist once |
| **Affinity store** | `keyword_weights(keyword,weight,docCount)` | unchanged; add `kind`/`taxonomy` column or parallel `entity_weights` for NER vs token distinction |
| **Topic taxonomy** | new `taxonomy` table or a `kind='TOPIC'` row set | map free tokens → a small fixed topic tree (UK Politics, Football, Tech, …) for Fringe bucket selection |
| **Base score** | `Article.baseScore` | Σ(entityLearnedWeight × IDF × localWeight) + outletWeight; recomputed only on ingest/rating |
| **Time decay** | SQL `exp(-λ·ageH)` at query | live multiplier, never materialised per refresh |
| **Mixing** | `NewsRepository.getFeedPage` | Core70 / Fringe20(mid-band+adjacent-taxonomy) / Wildcard10(random) |
| **Cold start** | VM guard `ratedArticleCount()==0` | force Wildcard=100% until first GREEN/seed; seed prompt via existing `setSeedInterests` |

**Topic taxonomy (lightweight, offline):** since you can't bundle an ML model at minSdk 26, ship a **static `Map<keyword, topic>`** (a hand-curated UK-news taxonomy of a few hundred entries) as a Kotlin `object`. Fringe bucket = articles whose topic is *adjacent* (sibling in the tree) to the user's top topics. This is cheap, offline, and explainable ("Because you read Football → Rugby").

**`docCount`/IDF reuse:** `Recommender.rescoreAll` already computes `idf = ln((1+totalRated)/(1+docCount)) + 1`. Keep it; it doubles as the taxonomy weight prior.

---

## 2. No-Noise UX specifics

**Color / accent:**
- Make **Peacock Teal `#006D77`** the brand accent. In `Theme.kt`, set `primary`/`tertiary` for the TEAL palette to `0xFF006D77` (currently `0xFF00796B`/`0xFF004D40` — close, just retune). Keep `RatingGreen/Amber/Red` constant (they encode meaning, already noted in `Theme.kt`).
- Surfaces: keep the existing charcoal/paper `background`/`surface`/`surfaceVariant` step. The "no borders" rule is enforced by removing `BorderStroke` from cards (§6).

**Borderless surface-contrast cards:**
- In `ArticleCard.kt`, **delete** `border = BorderStroke(2.dp, ageColor)` and `elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)`. Replace with `colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)` so cards read as a lighter step on the `background`, separated by whitespace (`padding(vertical = 6.dp)` already there) — not lines.
- Age indicator: instead of a colored border, show age as a small `FontFamily.Monospace` tag (see below) tinted with `ageColor` text only.

**`FontFamily.Monospace` source tags:**
```kotlin
Text(
    text = article.outletName.uppercase(),
    style = MaterialTheme.typography.labelLarge,
    fontFamily = FontFamily.Monospace,           // JetBrains-Mono-style tag, system monospace
    color = Color(0xFF006D77)                     // peacock teal accent
)
```
- **No font files:** minSdk 26 + offline → use `FontFamily.Monospace` / `FontFamily.Serif` / `FontFamily.SansSerif` (system fonts). The reader already uses `ReaderFont.SERIF/SANS/MONO` mapping to these — extend the same pattern to feed tags. `Type.kt` already defines `AppTypography`; add `val MonoTag = TextStyle(fontFamily = FontFamily.Monospace, ...)`.

**Skeleton loading:** see §(b). Tie it to the initial `state.loading && articles.isEmpty()` branch.

**Pitfalls to avoid:**
- Don't ship `@Font`/`Font()` with bundled `.ttf` — breaches the offline/no-bundle constraint and bloats the APK. System font families only.
- Monospace tags can overflow on long outlet names (`"DAILY MAIL"` is fine; `"THE INDEPENDENT"` may clip) — cap with `maxLines=1` + `TextOverflow.Ellipsis`, or use an abbreviation map in `Outlets.kt`.
- Keep contrast WCAG-AA: teal `#006D77` on paper `0xFFFFFFFF` passes; on charcoal `0xFF0A0A0A` it passes too. Don't put teal text on `surfaceVariant` dark (`0xFF1E1E1E`) without checking — it's fine, but verify in both themes.

---

## 3. Entity-affinity store schema decision (final)

Two viable shapes; recommend **(i)**:

**(i) Extend `keyword_weights` + add `article_entities` join (recommended).**
- Reuses the exact table V2 is told to reuse. Add `kind TEXT DEFAULT 'TOKEN'` to distinguish learned tokens vs NER entities vs taxonomy topics. `article_entities` gives per-article queryability.
- Migration `3→4`: `ALTER TABLE keyword_weights ADD COLUMN kind TEXT NOT NULL DEFAULT 'TOKEN';` + `CREATE TABLE article_entities (...)`.

**(ii) Parallel `entity_weights` table** (mirror of `keyword_weights` but for NER entities only). Cleaner separation, more tables. Only choose if you expect entities and tokens to diverge a lot in update cadence.

Avoid a JSON `entities` column on `Article` (see §(c)) — it kills the SQL join that makes incremental scoring work.

---

## 4. Migration checklist (so V2 doesn't wipe learned state)

1. `NewsDatabase` version `3 → 4`.
2. Add `Migration(3,4)`:
   - `ALTER TABLE keyword_weights ADD COLUMN kind TEXT NOT NULL DEFAULT 'TOKEN';`
   - `CREATE TABLE article_entities (articleId TEXT NOT NULL, keyword TEXT NOT NULL, weight REAL NOT NULL, kind TEXT NOT NULL DEFAULT 'TOKEN', PRIMARY KEY(articleId, keyword), FOREIGN KEY(articleId) REFERENCES articles(id) ON DELETE CASCADE);`
   - `CREATE INDEX IF NOT EXISTS idx_ae_keyword ON article_entities(keyword);`
   - `ALTER TABLE articles ADD COLUMN baseScore REAL NOT NULL DEFAULT 0.0;`
3. Register migration in `Room.databaseBuilder(...).addMigrations(MIGRATION_3_4)`. **Do NOT** keep `fallbackToDestructiveMigration()` as the only safety net for production (it would drop `keyword_weights` on a failed migrate) — keep it only as a last resort after a real migration.
4. Backfill: on first V2 launch, run a one-time job that extracts entities + `baseScore` for all existing `NONE` articles (they have `summary` already), so the feed isn't empty of affinity until the next refresh.

---

## 5. Source URL index

- Paging/offset tradeoffs: https://proandroiddev.com/paging-in-android-jetpack-compose-from-caching-data-with-room-to-displaying-in-lazycolumn-a018f0b6cb2
- Paging3 + Room: https://developer.android.com/topic/libraries/architecture/paging/v3-network-db
- Paging3 offset inefficiency discussion: https://stackoverflow.com/questions/76184361/paging3-in-android-how-come-uses-inefficient-offset-limit-pagination
- Shimmer skeleton (no lib): https://dev.to/myougatheaxo/shimmer-loading-effect-skeleton-screen-in-jetpack-compose-2feb
- Seamless shimmer: https://proandroiddev.com/seamless-shimmer-integration-with-existing-compose-code-b95cc3bbcd17
- placeholder-compose lib: https://github.com/RevenueCat/placeholder-compose
- Room TypeConverters: https://developer.android.com/training/data-storage/room/referencing-data
- Room relationships/foreign keys: https://developer.android.com/training/data-storage/room/relationships
- On-device recommender survey: https://arxiv.org/html/2401.11441v1
- On-device rec engine SDK (incremental affinity): https://vickycodes.com/post/building-on-device-recommendation-engine-sdk-android/
- ODRS tutorial slides: https://www2024.thewebconf.org/docs/tutorial-slides/on-device-recommender-systems.pdf
- Readability4J (extraction fallback already used): https://github.com/dankito/Readability4J
- jsoup: https://jsoup.org/

---

## 6. One-line fixes that directly serve the brief (design notes, not edits)

- `ArticleCard.kt:94` — remove `border = BorderStroke(2.dp, ageColor)` + elevation → borderless surface-contrast card (No-Noise rule).
- `Theme.kt:21` — retune TEAL `primary`/`tertiary` to `#006D77` Peacock Teal accent.
- `ArticleCard.kt:116-120` — source tag → `fontFamily = FontFamily.Monospace`, color `#006D77`.
- `FeedScreen.kt:157-158` — replace centered `CircularProgressIndicator` with 6 `SkeletonCard()` (§b).
- `NewsRepository.getFeedPage` — swap `EXPLORATION_RATIO=0.3` for Core70/Fringe20/Wildcard10 (§a).
- `Recommender.rescoreAll` — replace with incremental `baseScore` + live SQL decay (§d).
