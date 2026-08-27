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

OTTMirror/
  src/main/kotlin/com/ottmirror/
    OTTMirrorPlugin.kt           entrypoint: registers 4 providers
    OTTMirrorNetflix.kt          MainAPI (Netflix, ott=nf, /mobile/*)
    OTTMirrorHotstar.kt          MainAPI (Hotstar, ott=hs, /mobile/hs/*)
    OTTMirrorPrimeVideo.kt       MainAPI (Prime Video, ott=pv, /mobile/pv/*)
    OTTMirrorDisneyPlus.kt       MainAPI (Disney+, ott=dp, /mobile/hs/*)
    OTTMirrorProvider.kt         abstract base: home/search/load/loadLinks via backend
    OTTMirrorBackend.kt          NetMirror engine: verify, home, search, post, episodes, link flow (NewTV + native)
    NetMirrorConfig.kt           per-OTT config + host lists + cookie/NewTvBase caches + persisted cookie store
    Base64Decode.kt              pure-JVM base64 decoder (no android.jar dependency)
    Parsers.kt                   pure parsers for SearchData/PostData/EpisodesData/Playlist/NewTv
    HostThrottler.kt             per-host rate limiter + 429 exponential backoff + jitter
    DomainRotator.kt             host-list rotation + failure marking + stale-cache invalidation
    LinkCache.kt                 fast replay cache (5 min TTL, keyed by content id)
    TmdbMeta.kt                  TMDB metadata ONLY (detail enrichment + poster rating, no stream resolution)
  icon.png

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
- Every emitted link carries `source == name == "<Server>[ Hindi]"`
  (`MultiSourcePuller.linkLabel`). CloudStream sorts and saves player priorities
  by exact match on `source` while the server list displays `name` — one
  unstable character (CDN suffix, quality suffix, load counter) breaks the
  user's ranking forever. Never add runtime-derived parts to the label.

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

## OTTMirror

Second provider module (added Aug 2026). Same NetMirror backend the
CNC Verse / NetMirror plugins use — NOT the Multimovies dooplayer pipeline and
NOT id-based GlobalSources hosts. Four providers in one plugin:
`OTTMirror: Netflix` (ott=nf, base `/mobile/*`), `Hotstar` (ott=hs, `/mobile/hs/*`),
`Prime Video` (ott=pv, `/mobile/pv/*`), `Disney+` (ott=dp, `/mobile/hs/*` —
per the reference repo, Disney shares the Hotstar mobile namespace with the
`dp` ott cookie).

### TMDB scope

TMDB is metadata ONLY (`TmdbMeta.kt`): detail-page enrichment in `load()`
(plot, backdrop, cast, genres, year, episode names/thumbnails via the
`tmdb_id` the NetMirror `post.php` payload returns) and the poster rating badge
on search/home results (background title+year lookup, never blocking). TMDB is
NOT used for search (NetMirror's `/mobile/search.php` is) and NOT used to
resolve stream links. Keep it that way — no net27 embed-tmdb fallback.

### Reliability (the whole point)

`OTTMirrorBackend` re-implements the NetMirror flow (verify ? home ? search ?
post ? episodes ? NewTV player ? native play.php/playlist.php) with the layers
the forks lack:

- `HostThrottler` — per-host rate limiter (1 s base) + 429 exponential backoff
  (2 s ? 4 s ? … ? 60 s cap, reset on success) + ±20% jitter. Not the forks'
  single 1.2 s global spacer.
- `DomainRotator` — ordered host lists per role (`VERIFY_HOSTS`, base64
  `NEWTV_DOMAINS`); on 429/5xx/timeout a host is pinned dead and the role
  advances. A failed NewTV base is cleared so the token probe re-runs instead of
  trusting a stale `tv.imgcdn.kim` for hours.
- `CookieBox` — `t_hash_t` cached 15 min in memory (not 15 h): the backend
  invalidates server-side well before the forks' window, and a stale cookie is
  the classic "no link found" trap. The cache also tracks which host issued it
  (`issuedHost`): after `DomainRotator` rotates to another mirror, `verify()`
  re-runs against the new host instead of reusing the old host's cookie.
- `NetMirrorCookieStore` — same `t_hash_t` persisted to SharedPreferences with a
  15 h TTL (reference repo's `bypass()` approach), so a restart never pays the
  verify round-trip again. `verify()` checks in-memory first, then the persisted
  value (host-matched), and only then re-verifies.
- `warmUp()` — session health probe hits the current mobile host + NewTV base
  once; dead hosts never burn the 15 s timeout during real work. NOT called on
  the search path (search must stay fast; it has its own verify + host rotation).
- Search is fail-closed AND fast: only the OTT-scoped `/mobile/{ott}/search.php`
  endpoint is used (never the unscoped desktop search), no `warmUp()`, no
  `HostThrottler` spacing on the GET — matching the reference repo's single
  round-trip. Empty is a correct answer, wrong-platform is not.
- Posters are per-OTT CDN paths (`poster/v/`, `hs/v/`, `pv/341/` for search/home;
  `hsepimg/150/` for the post.php episode batch on hs/dp, `hsepimg/` for paged,
  `pvepimg/`, `poster/v/150/`), with TMDB silently upgrading the poster/rating in
  the background.
- Distinct errors: a NewTV outage or a seen 429 surfaces
  "NetMirror servers busy — retry in a minute" (`ErrorLoadingException`) instead
  of a silent "no link found".
- Stable link identity: every emitted link has `source == name ==` the OTT name
  (`Netflix` / `Hotstar` / `Prime Video` / `Disney+`) — no quality/CDN/runtime
  parts, so player priority saves stay stable.
- `LinkCache` (5 min, keyed by the content id `loadLinks()` receives) for
  instant replay.

Everything is session-scoped in-memory, matching the CNC Verse family. When the
backend rotates domains or adds a `t_hash_t` variant, update
`NetMirrorConfig.kt` (host lists) / `Parsers.kt` (wire shape).

## Build

JDK 17 + Android SDK.

```bash
./gradlew make                    # all modules: Multimovies.cs3 + OTTMirror.cs3 (R8-shrunk)
./gradlew :Multimovies:make       # Multimovies only
./gradlew :OTTMirror:make         # OTTMirror only
./gradlew makePluginsJson         # build/plugins.json
./gradlew :Multimovies:testDebugUnitTest
./gradlew :OTTMirror:testDebugUnitTest
```

The plugin ships `jsoup`, `okhttp`, `kotlinx-coroutines`, `NiceHttp` and
`kotlin-stdlib` as `compileOnly` (the host app provides them); they're
`testImplementation` for the JVM unit tests. That's how the `.cs3` stays
under 50 KB.

## Publishing

`.github/workflows/build.yml` runs `make` + `makePluginsJson` and deploys
`plugins.json`, `repo.json`, and `Multimovies.cs3` + `OTTMirror.cs3` to the
`builds` branch. Install link:

```
https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json
```

Enable **Settings ? Pages ? Source: GitHub Actions** and make sure Actions
has write permission on the repo.

## Legal

Doesn't host anything. Aggregates public embed hosts. Use at your own risk
and only on content you have rights to.
