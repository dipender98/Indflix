# Indflix

CloudStream 3 plugin. One provider, `Multimovies`, for
[multimovies.motorcycles](https://multimovies.motorcycles) (Dooplay theme).
Pulls several stream sources in parallel and gives the player the first one
that responds.

## Layout

```
Multimovies/
  src/main/kotlin/com/multimovies/
    MultimoviesProvider.kt   MainAPI impl: search, getMainPage, load, loadLinks
    MultiSourcePuller.kt     parallel-pull engine, per-source timeout, sort
    TmdbService.kt           TMDB search/detail/episodes + optional SIMKL
    ScreenscapeExtractor.kt  screenscape.me crypto port (no WebView)
    NxshaExtractor.kt        nxsha.space encrypted /api/servers+/api/sources
    NxshaProtocol.kt         nxsha wire rules (crypto, ids, ordering) - testable
    CryptoJs.kt              CryptoJS AES envelope crypto shared by extractors
    GlobalSources.kt         id-based source registry + session caches
    MultimoviesPlugin.kt     plugin entrypoint
  icon.png                   plugin icon, served from the repo (iconUrl)
```

Root `build.gradle.kts` + `settings.gradle.kts` auto-include any provider dir
that has its own `build.gradle.kts`. CloudStream gradle plugin is pinned to
`com.github.recloudstream.gradle:gradle:81b1d424d2` in the root buildscript.
`make` ends with `shrinkCs3`, which re-dexes R8-minified release classes into
the `.cs3`.

## Source engine

All knobs live in `MultimoviesProvider.kt`:

- `SOURCE_PRIORITY` — static order, most-reliable first. The primary sort key.
  Cineverse wins. If the site adds a new dooplayer server, add its name here.
- `SOURCE_TIMEOUT_MS = 15_000L` — per-source hard cap. A dead host can never
  block the others.

`MultiSourcePuller.pull()` does the following for every source:

1. Sort by static priority.
2. Launch all sources concurrently with `coroutineScope` + `async` / `awaitAll`.
3. Each source is wrapped in `withTimeoutOrNull(SOURCE_TIMEOUT_MS)`.
4. Record the time in `SourceSpeedTracker`; learned latency breaks ties
   within the same priority bucket.
5. Final link sort: `priority ? learned latency ? embed latency ? Hindi ? HLS`.

Per source it tries, in order: a dedicated host extractor (screenscape) ?
`loadExtractor` (the CloudStream registry) ? a generic m3u8/mp4 sniff of the
player page. YouTube/trailer embeds are filtered out.

## Fast start

`loadLinks()` checks three fast paths before doing any real work:

1. `LinkCache` (5 min TTL, key = imdbId|season|episode) — same episode, same
   streams, instant playback.
2. `EmbedPrefetchCache` — while `load()` is rendering the detail page, the
   provider starts `prefetchEmbeds()` on `searchScope`, resolving the top
   `EMBED_PREFETCH_COUNT = 2` dooplayer servers through admin-ajax and the
   unwrap pipeline. Play tap reads the result. If prefetch is still running,
   `awaitInFlight(1200ms)` joins it instead of re-doing the work. Empty
   results are invalidated so the next tap takes the full path.
3. `cachedDocOrFetch()` — `mmDocCache` (bounded at 24) is populated by both
   the search?TMDB?slug-guess path and direct main-page card taps. No re-fetch.

Adaptive HLS is the last tie-break so the player starts at the lowest
rendition and ramps up.

## Search

TMDB-only. One `/search/multi` call (or SIMKL when its client id is set)
returns poster + rating + year inline — no enrichment round-trips. Each hit
is then run through `relevanceOf()` which requires every significant query
token to match the title (exact / substring / small Levenshtein tolerance)
and a weighted score above `SEARCH_RELEVANCE_THRESHOLD = 0.5`. Hindi /
Devanagari combining marks survive normalization.

Constants: `SEARCH_MAX_RESULTS = 6`, `SEARCH_TOTAL_BUDGET_MS = 2500`,
`SEARCH_CACHE_TTL_MS = 15 min`.

After the user sees results, the real Multimovies page URL for each hit
resolves in the background (slug-guess first, site search as fallback), so
`load()` doesn't re-solve the slug on tap.

## Metadata

`TmdbService` is the single source of truth. No Cinemeta, no TMDB-web
scraping. Search: `/search/multi`. Detail: `/movie|tv/{id}?append_to_response=external_ids,credits`.
Episodes: `/tv/{id}/season/{n}`. IMDB lookup: `/find/{imdbId}`.

`SIMKL_CLIENT_ID` is embedded but blank — TMDB only. Set it and the search
call switches to SIMKL; detail still resolves through TMDB using the
returned id.

`addImdbId(imdbId)` is always called on the `LoadResponse` so the host app's
built-in TMDB meta-provider can layer richer episode data on top when the
user enables it.

### Posters

`upgradePosterUrl()` strips Dooplay `-WxH` thumbnails (?500px only — leaves
`scaled` alone), TMDB CDN size prefixes (`/w92..w500/`), and Amazon
`_SX*` suffixes so search/detail posters come in at full resolution.

## Build

JDK 17 + Android SDK.

```bash
./gradlew make                    # Multimovies/build/Multimovies.cs3 (R8-shrunk)
./gradlew makePluginsJson         # build/plugins.json
./gradlew :Multimovies:testDebugUnitTest
```

The plugin ships `jsoup`, `okhttp`, `kotlinx-coroutines`, `NiceHttp` and
`kotlin-stdlib` as `compileOnly` (the host app provides them); they're
`testImplementation` for the JVM unit tests. That's how the `.cs3` stays
under 50 KB.

## Publishing

`.github/workflows/build.yml` runs `make` + `makePluginsJson` and deploys
`plugins.json`, `repo.json`, and `Multimovies.cs3` to the `builds` branch.
Install link:

```
https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json
```

Enable **Settings ? Pages ? Source: GitHub Actions** and make sure Actions
has write permission on the repo.

## Legal

Doesn't host anything. Aggregates public embed hosts. Use at your own risk
and only on content you have rights to.
