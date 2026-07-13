# NewsRadar V2 Build Plan (autonomous, user asleep)

## DONE (builds green, #66, APK served)
1. Consent-gate fix: removed mail+mirror from Outlets.GATED (probe-proven server-rendered bodies). dailyrecord stays gated. Reader error state already wires "Read on Web" CCT (MIN_READER_LEN=300).
2. "No Noise" theme: Theme.kt now sets exact surfaces (dark #0A0A0A/#141414/#1E1E1E; light #FFFFFF/#FAFAFA/#F0F0F0). Default ColorScheme = TEAL (Peacock Teal #006D77 via Palette).
3. JVM unit tests: UrlUtilsTest (9) + TokeniserTest (4) — all 13 GREEN via gradlew testDebugUnitTest. Real self-testing (no device available; no emulator/system-image in SDK).
4. #66 assembleDebug SUCCESSFUL. APK at app/build/outputs/apk/debug/app-debug.apk, served on http://100.74.63.91:8080/app-debug.apk.

## PENDING (awaiting Google-agent second opinion before code)
### A. Recommendation Engine V2 (on-device, NO cloud)
Design (proposed, for agent confirmation):
- Tokeniser: add extractEntities(text) — NER via capitalised multi-word phrases + small lexicon (people/orgs/places). Keep tokenise() for topic keywords. Improve stemmer for economy/economic/economics convergence (decide with agent: risk of over-stemming).
- TopicTaxonomy object: keyword->Topic map (Technology, Politics, Sports, Business, UK, World, Health, Entertainment...). topicFor(tokens): Set<Topic>.
- KeywordWeight migration: ADD columns type (TOKEN|ENTITY, default TOKEN for existing rows), category (topic or null), lastRatedAt (epoch ms, default 0). Reuses table as entity-affinity store — existing learned weights preserved.
- Scoring: Stotal = [(Wbase*Sbase)+(Wdyn*Sdyn)] * e^(-lambda*t). Wbase=0.4, Wdyn=0.6. Sbase=50 if article topics ∩ explicit interests != empty. Sdyn = sum of entityAffinity(entities in article, decayed by age since lastRatedAt). Explicit Dislike (topic/entity in dislikes) -> Stotal=0 hard veto. RED penalizes entities only, never broad topic (GREEN+10, AMBER+2, RED-15).
- Feed mixing (batches of 10): Core 70% (top Stotal via existing getFeedPage score-desc), Fringe 20% (Amber/low-positive Sdyn), Wildcard 10% (global top by Green counts, ignore profile unless Explicit Dislike).
- Cold start: global trending baseline (by Green counts) if no ratings+seeds; onboarding 3-5 topics seeds Sbase.
- DB perf: time-boundary pre-filter 72h, hard exclusions (dislikes), score only subset, cache feed in VM until batch exhausted/refresh.
- New DAO queries: getFeedFringe(limit, enabledOutletIds), getFeedWildcard(limit, enabledOutletIds, dislikedTopics), getGlobalTrending(limit).

### B. Remaining UX ("No Noise")
- Source tag mono: ArticleCard outletName -> FontFamily.Monospace (already primary teal). Format like [THE GUARDIAN].
- Skeleton loading: feed + reader pulsing grey blocks (no spinners) while loading.
- Typography: headlines FontFamily.Serif, UI default sans, metadata mono (system fonts; no bundled font files offline).
- Cards: rely on surface/surfaceVariant contrast (already borderless).

### C. Inspiration/implementation (from research agents)
- Apply ranked ideas that fit offline single-user; ignore any needing backend/cloud/account.

## CONSTRAINTS
- No git commit/push/release without user go-ahead.
- Offline, free, single-user: NO cloud NLP, NO server.
- Keep CONTAINER_SELECTORS/JUNK_SELECTORS in ArticleFetcher (Jsoup isolated).
- Diagnostics via in-app CrashLogger ring (MIUI drops Logcat).

## TESTING (self, no device)
- gradlew testDebugUnitTest for pure logic (UrlUtils, Tokeniser, scoring math).
- gradlew assembleDebug for compile.
- Live HTTP probe already proved Mail/Mirror extraction works headlessly.
