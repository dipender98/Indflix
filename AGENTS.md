# Indflix

CloudStream 3 extension (provider + multi-source streamer) that scrapes
**Multimovies** (mirror: `https://multimovies.motorcycles`).

This repo is a fork/test of the CloudStream plugin template used for
**cloutstream**. It is a technical demo of a multi-source scraper/streamer.

## Architecture

- `src/main/kotlin/com/indflix/IndflixProvider.kt` — the `MainAPI` provider.
  Implements `search`, `getMainPage`, `load`, `loadLinks` against the Dooplay
  theme used by Multimovies.
- `src/main/kotlin/com/indflix/MultiSourcePuller.kt` — the **source engine**:
  - **Source priority**: `IndflixProvider.SOURCE_PRIORITY` orders servers from
    most reliable/fast to least.
  - **Parallel pulling**: every server is launched concurrently via `apmap`.
  - **Per-source timeout**: each source is wrapped in `withTimeoutOrNull(30s)`
    (`SOURCE_TIMEOUT_MS`). A slow/dead source can never block the others.
  - Results are returned sorted by priority.
- `src/main/kotlin/com/indflix/IndflixPlugin.kt` — registers the provider.

## Source policy (requirement)

```
timeout per source ≈ 30s
pull all sources in parallel
prefer reliable + fast sources first (priority order)
```

Tune in one place: `SOURCE_TIMEOUT_MS` and `SOURCE_PRIORITY` in
`IndflixProvider.kt` (consumed by `MultiSourcePuller`).

## Build

This plugin builds against the CloudStream gradle plugin (`com.lagradost.cloudstream`).
The recommended workflow is to push to GitHub and let the Actions workflow build
the APK (fork of recloudstream/TestPlugins). Requires:
- Android SDK
- `gradle wrapper` (generate the `gradle-wrapper.jar` locally with
  `gradle wrapper --gradle-version 8.9` if you build locally)

```
gradlew assembleRelease   # builds the provider APK
```

## Legal

This is a technical demonstration of source aggregation. It does not host any
content. Streaming copyrighted material may violate the source site's ToS and
applicable law; use at your own risk and only with content you have rights to.
