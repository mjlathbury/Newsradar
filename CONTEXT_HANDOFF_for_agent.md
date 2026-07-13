# NewsRadar — Autonomous Build Handoff (for analysis agent back-and-forth)

You are the analysis/review agent that has been working with the main Hermes agent on NewsRadar, a FREE, SIDELOADED, OFFLINE-FIRST Android news aggregator (Kotlin + Jetpack Compose). The user is asleep and granted FULL AUTONOMY to: finish the Reader-View consent-gate fix, FULLY implement the Recommendation Engine V2 spec, FULLY implement the UX/UI update brief, optimize code, and test. Hard guardrail: do NOT git commit/push or publish a release without explicit user go-ahead (build locally + leave APK on the LAN link only).

## CRITICAL ENVIRONMENT CONSTRAINT (drives every design decision)
NewsRadar has NO backend, NO server, NO paid API. It is a single-user sideloaded app. The Recommendation Engine Spec V2 explicitly proposes cloud NLP (spaCy / HuggingFace / Google Cloud Natural Language API) for NER + topic classification. That is IMPOSSIBLE here — there is no server, the app must work offline, and the user pays nothing. Your job: implement the SAME ALGORITHM (weighted semantic-ish scoring, entity affinity, time decay, feed mixing, cold start) using a 100% ON-DEVICE Kotlin pipeline. NER = dictionary/lexicon + heuristic extraction (capitalised multi-word phrases, known entities), not an LLM. Agree with the main agent on a pragmatic on-device mapping of the spec.

## STATUS (verified, builds green up to #65)
- Image saga CLOSED: Guardian/Metro cards+reader work. Fix: signed CDN URLs (i.guim.co.uk, media.gettyimages.com, dailymail) keep FULL query (signature integrity); non-signed hosts drop resize/tracking. og:image recovery bypass.
- #63 UX: greeting toggle (GREETING_ENABLED=false), infinite scroll (no Load-more button).
- #64 regression (Guardian images lost) -> fixed in #65 (preserve full query for signed hosts). Lesson: sanitizer must not run destructively on signed og:image URLs.
- Q3/Q4 done: UrlUtils.kt (pure Kotlin, allow/deny lists). ArticleFetcher keeps CONTAINER_SELECTORS/JUNK_SELECTORS (Jsoup). RssFetcher/ArticleFetcher delegate to UrlUtils. MainViewModel+FeedScreen use UrlUtils.isSignedImageUrl.

## CURRENT ENGINE CODE (must evolve to V2)
Recommender.kt (class Recommender(dao)):
- TF-IDF content-based. delta: GREEN+1.5, AMBER+0.4, RED-1.5.
- applySeeds(words): each token SEED_WEIGHT=5.0 positive.
- applyDislikes(words): each token DISLIKE_WEIGHT=3.0 negative (sinks, never hard-hidden).
- applyRating(article, rating): learns per-token weights + outlet weight (d*0.25).
- rescoreAll(): scores every article = sum(token.weight * idf) + outlet weight. idf=ln((1+totalRated)/(1+docCount))+1.
- matchReasons(): top positive keywords.

Tokeniser.kt (object Tokeniser): lowercases, strips punct, stop-words, light Porter-ish stemmer. tokenise(text)->List<String>.

Data model already has: KeywordWeight(keyword,weight,docCount), OutletState(outletId,enabled,weight), Rating(GREEN/AMBER/RED/NONE), ArticleScoreUpdate(id,score), Article(outletId,title,summary,publishedAt,imageUrl,rating?,score?).

## TASK 1 — Reader-View consent-gate fix (probe-PROVEN, low risk)
Probe (live HTTP, identical UA results): Daily Mail + Mirror articles are SERVER-RENDERED with full body in raw HTML under a normal mobile UA. Mirror JSON-LD articleBody = 1372 chars real prose; Mail body in .article-text element. Consent wall is JS/display:none only. Googlebot UA made ZERO difference (identical 720KB/977KB HTML). Therefore Outlets.GATED={"mail","mirror","dailyrecord"} is OVER-AGGRESSIVE — it hard-CCTs before ArticleFetcher runs.
FIX: remove "mail"+"mirror" from GATED (keep "dailyrecord" until probed). Wire CCT as the <300-char FAILURE HATCH in openArticle: if fetchText returns too short, openCustomTab(link). Keep existing ArticleFetcher cascade (selectors->JSON-LD->Readability->WebView). No new UA/cookie/AMP code needed.

## TASK 2 — Recommendation Engine V2 (from spec, ADAPTED on-device)
Implement:
- Phase 1 Semantic layer ON-DEVICE: lightweight NER (capitalised phrases + entity lexicon) + topic classification (keyword->topic taxonomy map). Store entities per article (new table ArticleEntity(articleId, entity, type) or JSON column).
- Phase 2 Scoring: Stotal = [(Wbase*Sbase)+(Wdyn*Sdyn)] * e^(-lambda*t). Wbase=0.4, Wdyn=0.6. Sbase=50 if article broad category matches Explicit Interest. Sdyn=sum of Entity Affinity scores for entities in article. Time decay e^-lambda*t (t=days since rating). Explicit Dislike -> Stotal=0 (hard veto). RED logic: penalize specific ENTITIES only, never broad topic.
- Implicit feedback: GREEN +10, AMBER +2, RED -15 to entity affinities (not topics).
- Phase 3 Feed mixing (batches of 10): Core 70% (top Stotal), Fringe 20% (Amber/low-positive Sdyn), Wildcards 10% (global high-engagement, ignore profile unless Explicit Dislike).
- Phase 4 Cold start: global trending baseline (by Green counts) + onboarding 3-5 topics seeding Sbase. DB perf: time-boundary pre-filter (48-72h), hard exclusions (dislikes), score only subset, cache feed until batch exhausted/refresh.
NOTE: Current engine uses TF-IDF token weights; V2 wants entity-affinity + topic taxonomy. Need to reconcile: keep token-based learning but ADD entity affinity + topic taxonomy + time decay + feed mixing. Agree with main agent on migration that doesn't break existing learned weights.

## TASK 3 — UX/UI Update (from brief)
Brand: "No Noise" — remove borders/dividers/boxes, whitespace + subtle bg contrast. Peacock Teal #006D77 primary accent. Dark "Focus Terminal" #0A0A0A bg / #141414 surface / #EDEDED text. Light "Premium Broadsheet" #FFFFFF / #FAFAFA / #111111. Typography: Headlines = Playfair Display/Newsreader; UI = Inter/Geist; Metadata/badges = JetBrains Mono (source tags like [THE GUARDIAN]). Interactions: Zen reading (already have Reader View), Source Swipe (toggle between publishers on same event — complex, defer/optional), Skeleton loading (no spinners — pulsing grey blocks), Source Tagging in mono under headline.
PRAGMATIC SCOPE: NewsRadar is offline sideloaded; cannot bundle Playfair/Inter/JetBrains font files easily without assets. Use system font fallbacks that approximate (serif for headlines, sans for UI, monospace for metadata). Add teal accent, remove card borders -> use surface bg contrast, add source tag mono, add skeleton loading for feed + reader. Source Swipe = stretch (maybe skip or stub). Agree scope with main agent.

## TESTING (main agent will do, since no device)
- JVM unit tests for UrlUtils (allow/deny lists, signed hosts, ref handling).
- Jsoup-based extraction smoke test: fetch a live Mirror + Mail article, run ArticleFetcher-equivalent selector logic, assert body text length >= 300 (proves consent-gate fix works headlessly).
- Build #66+ via gradlew assembleDebug. Leave APK at app/build/outputs/apk/debug/app-debug.apk; serve via `python3 -m http.server 8080 --bind 0.0.0.0` in that dir (LAN link http://100.74.63.91:8080/app-debug.apk).

## YOUR JOB NOW
1. Critique the above plan. Flag risks, especially: (a) reconciling TF-IDF token learning with V2 entity-affinity without breaking existing users' learned weights; (b) on-device NER accuracy vs effort; (c) feed-mixing implementation cost vs payoff; (d) UX scope realism for an offline sideloaded app.
2. Propose concrete data-model + function signatures for the V2 engine (these are NEW Kotlin files: e.g. EntityAffinity table, TopicTaxonomy object, Scorer). Keep it on-device, no cloud.
3. Agree with the main agent on a build order. Then the main agent implements; you review the diffs.
Be specific and pragmatic. The app must remain free, offline, single-user.
