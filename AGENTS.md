# Indflix

CloudStream 3 extension (provider + multi-source streamer) that scrapes
**Multimovies** (`https://multimovies.motorcycles`, Dooplay theme). Technical
demo of a multi-source scraper/streamer; forked from the CloudStream plugin
template.

## Architecture

- `Multimovies/src/main/kotlin/com/multimovies/MultimoviesProvider.kt` — the
  `MainAPI` provider. Implements `search`, `getMainPage`, `load`, `loadLinks`,
  plus the search-relevance engine (pure, JVM-testable) and all cache wiring.
  `mainPage` exposes 18 rows (5 Bollywood, 5 global movies, 5 series, 3 anime).
  Emitted links are named `<Server>[_Hindi]_<Quality>` via
  `MultiSourcePuller.LinkNamer` (duplicates get `-2` suffixes); YouTube/trailer
  embeds are excluded from sources.
- `Multimovies/src/main/kotlin/com/multimovies/MultiSourcePuller.kt` — the **source engine**:
  - **Source priority**: `MultimoviesProvider.SOURCE_PRIORITY` orders servers from
    most reliable/fast to least (primary sort key).
  - **Learned latency**: `SourceSpeedTracker` records per-source extraction times;
    breaks ties within the same priority.
  - **Parallel pulling**: every server is launched concurrently via
    `coroutineScope` + `async`/`awaitAll`.
  - **Per-source timeout**: each source is wrapped in `withTimeoutOrNull(SOURCE_TIMEOUT_MS)`
    (15 s). A slow/dead source can never block the others.
  - Sort order: priority -> learned latency -> embed latency -> Hindi audio ->
    adaptive HLS (m3u8) over fixed progressive files.
- `Multimovies/src/main/kotlin/com/multimovies/TmdbService.kt` — TMDB metadata
  engine (search / detail / find-by-imdb / episodes) with an optional SIMKL
  search override. See Metadata below.
- `Multimovies/src/main/kotlin/com/multimovies/ScreenscapeExtractor.kt` —
  self-contained screenscape.me extractor (client-side crypto port, no WebView);
  uses its own OkHttp client and returns plain data classes adapted by the puller.
- `Multimovies/src/main/kotlin/com/multimovies/HostExtractors.kt` — session
  caches (`SourceMetaCache`, `LinkCache`, `EmbedPrefetchCache`) and the curated
  id-based `GlobalSources` registry (2embed.cc, VidSrc, 111Movies).
- `Multimovies/src/main/kotlin/com/multimovies/MultimoviesPlugin.kt` — registers the provider.
- Build uses the **Kotlin DSL** multi-provider layout: root `build.gradle.kts` +
  `settings.gradle.kts` auto-includes each provider dir that has a
  `build.gradle.kts` (here: `Multimovies/`). The CloudStream gradle plugin
  (`com.github.recloudstream.gradle:gradle:81b1d424d2`) is declared in the root
  buildscript block with pinned commit. `make` is finalized by `shrinkCs3`,
  which re-dexes the R8-minified release classes into the `.cs3`.

## Fast start (~1 s playback)

Order of fast paths in `loadLinks()`:

1. `LinkCache` hit -> emit instantly (5 min TTL, keyed imdbId|season|episode).
2. `EmbedPrefetchCache` hit -> page fetch AND admin-ajax skipped entirely.
   Movies only: during `load()`, `prefetchEmbeds()` resolves the top
   `EMBED_PREFETCH_COUNT` (2) dooplayer servers by static priority to their
   post-unwrap stream URLs on `searchScope`. Failures are silent; a fully dead
   entry is invalidated on empty result so retries take the full path.
3. `cachedDocOrFetch()` reuses the memoized detail-page doc (`mmDocCache`)
   before any network fetch (populated by both the TMDB-resolution path and
   direct main-page loads).

Adaptive HLS preference (last sort tie-break) makes the player start at a lower
rendition and ramp quality up automatically.

## Search

Search never queries Multimovies synchronously: one TMDB `/search/multi` call
(optional SIMKL override) returns posters + ratings inline, then a strict
relevance gate removes every non-matching hit:

- `relevanceOf()` — every significant query token must match (exact, substring,
  or small Levenshtein tolerance); unicode-aware normalization keeps Devanagari
  combining marks intact.
- Constants: `SEARCH_MAX_RESULTS = 6`, `SEARCH_RELEVANCE_THRESHOLD = 0.5`,
  `SEARCH_TOTAL_BUDGET_MS = 2500`, `SEARCH_CACHE_TTL_MS = 15 min`.
- Each hit's real Multimovies page URL resolves in the background so `load()` is
  instant on tap.

## Source policy (requirement)

```text
timeout per source <= 15s
pull all sources in parallel
prefer reliable + fast sources first (priority order)
```

Tune in one place: `SOURCE_TIMEOUT_MS` and `SOURCE_PRIORITY` in
`MultimoviesProvider.kt` (consumed by `MultiSourcePuller`).

## Metadata

Metadata comes from **TMDB** via `TmdbService`; there is no Cinemeta/TMDB-web
scraping anywhere in the pipeline.

- Search: TMDB `/search/multi`. Detail: `/movie|tv/{id}?append_to_response=external_ids,credits`
  (imdb id, cast). Episodes: `/tv/{id}/season/{n}`. IMDB lookup: `/find/{imdbId}`.
- `SIMKL_CLIENT_ID` is embedded but blank -> TMDB-only. Setting it switches
  SEARCH to SIMKL (detail still TMDB via the tmdb id SIMKL returns).
- The TMDB api key is the public key shipped with the CloudStream app; the pinned
  library exposes no `preferences` settings DSL on `MainAPI`, so no user-facing
  key setting exists.
- Enrichment fills poster/backdrop/year/plot/genres/cast/score; every
  LoadResponse also calls `addImdbId(imdbId)` so CloudStream's built-in TMDB
  meta-provider can add richer episode data when enabled in the app.

### Poster quality

`upgradePosterUrl()` strips Dooplay `-WxH` thumbnail size suffixes (small dims
only), TMDB CDN size prefixes, and Amazon `_SX*` suffixes so search/detail
posters load at full resolution.

## Publishing (extension link)

The Actions workflow (`.github/workflows/build.yml`) builds the plugin with
`./gradlew make makePluginsJson`, then deploys `plugins.json`, `repo.json` and
`Multimovies.cs3` to the **builds branch**. The resulting install link is:

```text
https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json
```

To make it live, enable **Settings > Pages > Source: GitHub Actions** and ensure
Actions have write permission. The `repo.json` at the repo root mirrors this URL
for documentation.

## Build

Requires Android SDK + JDK 17.

```bash
./gradlew make            # builds Multimovies/build/Multimovies.cs3 (R8-shrunk)
./gradlew makePluginsJson # generates build/plugins.json
./gradlew :Multimovies:testDebugUnitTest   # JVM unit tests, if present
```

## Legal

This is a technical demonstration of source aggregation. It does not host any
content. Streaming copyrighted material may violate the source site's ToS and
applicable law; use at your own risk and only with content you have rights to.
