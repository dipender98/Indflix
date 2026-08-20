# Indflix — CloudStream Extension

A CloudStream 3 provider/streamer that scrapes **Multimovies**
(`https://multimovies.motorcycles`) and pulls its video **sources** with a
reliable-first, parallel, timeout-bounded strategy.

## Features

- Search, main page sections, detail + episode loading (Dooplay theme).
- `loadLinks` extracts every "Video Sources" server and resolves it through
  CloudStream's extractor registry.
- **Source priority** — servers tried reliable/fast-first (`SOURCE_PRIORITY`).
- **Parallel pulling** — all sources launched concurrently (`apmap`).
- **Per-source timeout ≈ 30s** — each source isolated with `withTimeoutOrNull`
  so one dead host never blocks the rest.

## Project layout

```
build.gradle(.kotlin?)      CloudStream plugin gradle config
src/main/kotlin/com/indflix/
  ├─ IndflixProvider.kt     MainAPI: search/mainPage/load/loadLinks + SOURCE_PRIORITY
  ├─ MultiSourcePuller.kt   parallel + timeout + priority engine
  └─ IndflixPlugin.kt       @CloudstreamPlugin registration
```

## Tuning the source strategy

All knobs live in `IndflixProvider.kt`:

```kotlin
companion object {
    const val SOURCE_TIMEOUT_MS = 30_000L
    val SOURCE_PRIORITY = listOf("GDMIRROR - Recommended", "Cineverse", "Nxsha", ...)
}
```

Reorder `SOURCE_PRIORITY` to change which source wins; change
`SOURCE_TIMEOUT_MS` to tighten/loosen the per-source budget.

## Build & install

1. Fork `recloudstream/TestPlugins`, drop this repo in, enable GitHub Actions.
2. Push → Actions builds `app-release.apk` (provider bundle).
3. Install in CloudStream → Add repository → enable **Indflix**.

Local build (needs Android SDK + gradle 8.9):

```bash
gradle wrapper --gradle-version 8.9   # generates gradle-wrapper.jar
./gradlew assembleRelease
```

## Disclaimer

Technical demo of source aggregation only. Does not host content. Streaming
copyrighted material may violate the source site's ToS and applicable law.
