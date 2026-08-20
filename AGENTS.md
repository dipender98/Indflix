# Indflix

CloudStream 3 extension (provider + multi-source streamer) that scrapes
**Multimovies** (mirror: `https://multimovies.motorcycles`).

This repo is a fork/test of the CloudStream plugin template used for
**cloutstream**. It is a technical demo of a multi-source scraper/streamer.

## Architecture

- `Indflix/src/main/kotlin/com/indflix/IndflixProvider.kt` — the `MainAPI` provider.
  Implements `search`, `getMainPage`, `load`, `loadLinks` against the Dooplay
  theme used by Multimovies.
- `Indflix/src/main/kotlin/com/indflix/MultiSourcePuller.kt` — the **source engine**:
  - **Source priority**: `IndflixProvider.SOURCE_PRIORITY` orders servers from
    most reliable/fast to least.
  - **Parallel pulling**: every server is launched concurrently via `apmap`.
  - **Per-source timeout**: each source is wrapped in `withTimeoutOrNull(30s)`
    (`SOURCE_TIMEOUT_MS`). A slow/dead source can never block the others.
  - Results are returned sorted by priority.
- `Indflix/src/main/kotlin/com/indflix/IndflixPlugin.kt` — registers the provider.
- Build uses the **Kotlin DSL** TestPlugins layout: root `build.gradle.kts` +
  `settings.gradle.kts` auto-includes each provider dir that has a
  `build.gradle.kts` (here: `Indflix/`). The CloudStream gradle plugin
  (`com.github.recloudstream:gradle:-SNAPSHOT`) is declared in the root
  `buildscript` block.

## Source policy (requirement)

```
timeout per source ≈ 30s
pull all sources in parallel
prefer reliable + fast sources first (priority order)
```

Tune in one place: `SOURCE_TIMEOUT_MS` and `SOURCE_PRIORITY` in
`IndflixProvider.kt` (consumed by `MultiSourcePuller`).

- `repo.json` — CloudStream repository manifest at repo root; `pluginLists`
  points to `plugins.json` served from GitHub Pages. This is the **extension
  link** users add in the app.

## Publishing (extension link)

The Actions workflow (`.github/workflows/build.yml`) builds the plugin with
`./gradlew make makePluginsJson`, then deploys `plugins.json`, `repo.json` and
`indflix.cs3` to **GitHub Pages**. The resulting install link is:

```
https://dipender98.github.io/Indflix/repo.json
```

To make it live, enable **Settings → Pages → Source: GitHub Actions** and ensure
Actions have write permission. The `repo.json` at the repo root mirrors this
URL for documentation.

## Build

This plugin builds against the CloudStream gradle plugin (`com.lagradost.cloudstream3.gradle`).
Requires Android SDK + JDK 17.

```
./gradlew make            # builds Indflix/build/indflix.cs3
./gradlew makePluginsJson # generates build/plugins.json
```

## Legal

This is a technical demonstration of source aggregation. It does not host any
content. Streaming copyrighted material may violate the source site's ToS and
applicable law; use at your own risk and only with content you have rights to.
