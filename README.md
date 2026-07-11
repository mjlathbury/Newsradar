# NewsRadar 📡

A personal Android news app that fetches the top UK stories every morning, lets you rate how interested you are, and **learns your taste** so tomorrow's feed is sharper than today's.

## Features

- **Personal greeting on launch** — a welcome popup says "Good Morning/Afternoon/Evening, (your name)" and shows the full day and date. Set your name in Settings, where you can also switch on a persistent **date bar** (Day, Date Month Year) at the top of the feed.
- **Local weather bar** — a collapsible bar above the feed shows today's conditions for your town; tap it to expand into today's detail (feels-like, wind, humidity, **sunrise/sunset**) plus the **7-day week ahead** with hourly drill-down. Toggle it or the sunrise/sunset line off in Settings.
- **Choice of weather providers** (single-select, defaults to **Met Office (UK)**) — Met Office UK / ECMWF / GFS / Best-match, all free with no API key via [Open-Meteo](https://open-meteo.com). *(BBC has no public weather API; the Met Office option uses the same official data BBC Weather is based on.)* Location is set by typing your town — no GPS permission needed.
- **Daily digest at 7am** — an automatic background fetch pulls fresh headlines from every major UK outlet, then a notification tells you they're ready. There's also a **Refresh now** button any time.
- **Rate every story** — 🟢 Interested / 🟡 Maybe / 🔴 Not for me. Your ratings train the app.
- **Seed your interests** — type a few topics in Settings (e.g. "football, space, economy") to bias the early feed before you've rated anything; they merge with what you teach it by rating.
- **5 stories at a time** with a **Load more** button.
- **Summary + tap to open** — reads the RSS summary in-app; tap **Read full story** to open the article on the outlet's site. Tap **60s summary** for an on-device overview built from the article's own sentences (fetched only on demand, no API key).
- **All major UK sources** — BBC, Guardian, Telegraph, Independent, Sky, Mirror, Metro, Daily Mail, Express, Evening Standard, HuffPost UK, iNews, FT, Daily Record, Scotsman, Wales Online. Turn any of them **off** in Settings.
- **Paywall-aware** — a 🔒 badge marks outlets that need a subscription to read the full article (headlines & summaries always show for free). Hard-paywall outlets (Telegraph, Financial Times) start **switched off**; enable them in Settings if you subscribe.
- **Themes** — light / dark / follow-system, plus **6 colour schemes** (Blue, Teal, Purple, Sunset, Forest, Mono).
- **100% on-device** — no server, no account, no API keys, no tracking. Uses free, unlimited RSS feeds.

## Tech

Kotlin · Jetpack Compose · Material 3 · Room · WorkManager · DataStore · Coil · RssParser

## How the learning works

1. When you rate an article, every keyword in it gets a weight nudge: 🟢 +2, 🟡 +0.5, 🔴 −2. The outlet gets a smaller nudge.
2. When ranking a fetched article, each keyword's learned weight is multiplied by its **inverse document frequency** (rare words count more), summed, plus the outlet weight → a relevance score.
3. The feed is sorted by score. 🔴-rated stories are hidden. Over days, your feed converges on your interests.

## Build & install (sideload)

### Easiest: let GitHub build it for you
1. Push this repo to GitHub.
2. The **Build APK** workflow (`.github/workflows/build-apk.yml`) runs automatically. Open the **Actions** tab → latest run → download the **NewsRadar-debug-apk** artifact.
3. To cut a downloadable Release, push a tag: `git tag v1.0 && git push --tags`. The APK attaches to the Release automatically.
4. Copy the APK to your phone, tap it, allow "install from unknown sources", done.

### Local build (if you have Android Studio)
```bash
# First time only, to create the Gradle wrapper:
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug
# APK lands at app/build/outputs/apk/debug/app-debug.apk
```
Or just open the folder in Android Studio and hit **Run**.

## Notes

- This is a **debug-signed** APK — perfect for personal sideloading. No Play Store account or signing key needed.
- If an outlet changes its RSS URL, update it in `app/src/main/java/com/newsradar/app/data/Outlets.kt`.
- Minimum Android 8.0 (API 26).
