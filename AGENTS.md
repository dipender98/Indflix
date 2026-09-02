# Indflix

CloudStream 3 plugin. One provider, `Multimovies`, for
[multimovies.motorcycles](https://multimovies.motorcycles) (Dooplay theme).
Pulls several stream sources in parallel and gives the player the first one
that responds.

## Layout

```
Multimovies/
  src/main/kotlin/com/multimovies/
    MultimoviesPlugin.kt     plugin entrypoint
    MultimoviesProvider.kt   MainAPI (search, mainPage, load, loadLinks) + live-domain resolver
    SearchRanking.kt         pure search ranking, poster upgrade, retry helpers (JVM-testable)
    MultiSourcePuller.kt     parallel-pull engine, per-source timeout, link identity
    GlobalSources.kt         id-based source registry + session caches
    TmdbService.kt           TMDB search/detail/episodes + optional SIMKL
    HttpKit.kt               shared OkHttp client (get/getJson/post) for the extractors
    CryptoJs.kt              CryptoJS AES envelope crypto shared by extractors
    NxshaExtractor.kt        nxsha.space encrypted API + wire protocol (uses HttpKit)
    ApiExtractors.kt         Shows (111Movies) + VidEm JSON-API extractors (uses HttpKit)
  icon.png                   plugin icon, served from the repo (iconUrl)

OTTMirror/
  src/main/kotlin/com/ottmirror/
    OTTMirrorPlugin.kt           entrypoint: registers 4 providers
    OTTMirrorNetflix.kt          MainAPI (Netflix, ott=nf, /mobile/*)
    OTTMirrorHotstar.kt          MainAPI (Hotstar, ott=hs, /mobile/hs/*)
    OTTMirrorPrimeVideo.kt       MainAPI (Prime Video, ott=pv, /mobile/pv/*)
    OTTMirrorDisneyPlus.kt       MainAPI (Disney+, ott=dp, /mobile/hs/*)
    OTTMirrorProvider.kt         abstract base: home/search/load/loadLinks via backend
    OTTMirrorBackend.kt          NetMirror engine: verify, home, search, post, episodes, link flow (NewTV)
    NetMirrorGuard.kt            response classifier (200-body errors, not HTTP 429) + recovery helpers
    NetMirrorConfig.kt           per-OTT config + host lists + cookie/NewTvBase caches + response caches + persisted cookie store
    Base64Decode.kt              pure-JVM base64 decoder (no android.jar dependency)
    Parsers.kt                   pure parsers for SearchData/PostData/EpisodesData/Playlist/NewTv
    HostThrottler.kt             global request gate (1200 ms spacing) + 5 s?60 s cooldown ladder
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

Per source it tries, in order: a dedicated host extractor (nxsha, videm, shows.st) ?
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
post ? episodes ? NewTV player) with the layers the forks lack. The core
lesson from live probing (Aug 2026): the backend
**almost never answers HTTP 429** — rate-limit and session errors hide behind
**HTTP 200 bodies** (`{"status":"n","error":"Invalid User"}`, anti-abuse text
like `Too many request in short..`). Handling only the status code is why the
forks and the first fix failed:

- `NetMirrorGuard` — classifies every response body, not just the status code:
  `OK / LIMITED / SESSION_DEAD / DEAD`. `LIMITED` matches anti-abuse text
  ("too many request…", case-insensitive, ?8 KB bodies); `SESSION_DEAD` matches
  `status:"n"` + `error:"Invalid User"`. Every request path routes its response
  through `classify()`.
- Session TTL reality: the server kills `t_hash_t` **~4-5 minutes** after issue
  (probed: fresh cookie OK at T+210s, `Invalid User` at T+290s). Early revisions
  of this plugin set a 3-min `CookieBox` TTL that forced a `verify.php` POST
  every few minutes of browsing — that storm was the dominant feed into the
  per-IP limiter. The current `CookieBox` reuses the cookie for **15 hours**
  (CNCVerse-proven trust window; `SESSION_TTL_MS` in `NetMirrorConfig.kt:115`).
  The live `SESSION_DEAD` detection on the `Invalid User` body remains as the
  recovery net for the rare genuine server-side expiry. On `SESSION_DEAD` the
  backend drops the cookie, re-verifies once (singleflight), and repeats the
  request.
- `verify()` — one raw POST to `/verify.php` with redirects disabled (the 301
  carries `Set-Cookie: t_hash_t=`), at most 3 hosts, singleflight so concurrent
  callers share one verify. The old two-URL × two-method × five-host storm
  (up to 20 requests on every cookie expiry) fed the very limiter it was
  escaping. `NetMirrorCookieStore` is now only a bootstrap hint used when the
  verify infrastructure itself is unreachable.
- `HostThrottler` — global gate (1200 ms min spacing) + a 15 s ? 60 s cooldown
  ladder (90 s cap). The server never sends `Retry-After`, so `recordLimited()`
  doubles the penalty on consecutive hits and any genuinely-OK response resets
  it. `onLimited(attempt)` waits out the cooldown once per call site, then gives
  up rather than feeding the limiter.
- `DomainRotator` — hosts carry a dead-since timestamp with 5-min recovery. A
  `LIMITED` verdict never marks a host dead (the limit is per client IP, not per
  mirror); only network errors / 5xx / genuinely-broken shapes do.
- `resolveNewTvBase()` — capped at **3** NewTV domains (not all 24): from a
  limited IP every domain answers the same anti-abuse body, and walking the list
  just burns requests. `NewTvBase` stays cached in-memory once resolved.
- `NetMirrorResponseCache` (10 min) — absorbs UI-driven repeat calls for the
  same post.php / episodes.php payload so browsing never re-asks the limiter for
  data it already served.
- Search is fail-closed AND fast: only the OTT-scoped `/mobile/{ott}/search.php`
  endpoint is used (never the unscoped desktop search). A dead cookie silently
  degrades to the server's "Top Searches" fallback, which is detected and
  answered with a re-verify + one retry so scoped quality holds. Empty is a
  correct answer, wrong-platform is not.
- Posters are per-OTT CDN paths (`poster/v/`, `hs/v/`, `pv/341/` for search/home;
  `hsepimg/150/` for the post.php episode batch on hs/dp, `hsepimg/` for paged,
  `pvepimg/`, `poster/v/150/`), with TMDB silently upgrading the poster/rating in
  the background.
- Distinct errors: a saturated IP surfaces `limitedMessage()` — "NetMirror rate
  limit hit — auto-clears in ~Ns" (`ErrorLoadingException`) — instead of a silent
  "no link found". `OTTMirrorProvider` rethrows it from `load()`/`loadLinks()`
  and surfaces `rateLimited()` on home/search rather than returning empty.
- Stable link identity: every emitted link has `source == name ==` the OTT name
  (`Netflix` / `Hotstar` / `Prime Video` / `Disney+`) — no quality/CDN/runtime
  parts, so player priority saves stay stable.
- `LinkCache` (60 min, keyed by the content id `loadLinks()` receives) — the
  resolved m3u8 URLs stay playable well beyond 5 min (the CDN serves them with
  no session), so the longer TTL means more zero-traffic replays.
- **Dual emission — embed + NewTV master**: `loadLinks()` resolves the
  embed-tmdb MP4s AND the NewTV master concurrently (no more embed
  short-circuit). The embed MP4s are the playable-for-everyone quality
  tiers; the NewTV master is the ONLY source carrying real multi-audio
  renditions (`#EXT-X-MEDIA:TYPE=AUDIO`, e.g. "1. English" + "2. Hindi" on
  dual-audio titles like Breaking Bad / GoT). The master link is emitted
  VERBATIM (in=unknown keys and the shared `files/220884` placeholder path
  ship through untouched) and gated ONLY by `NetMirrorParsers.newTvMasterPlayable`:
  the master must declare `#EXT-X-STREAM-INF` video variants and every
  audio rendition URI must carry a host (rejects the broken `https:///`
  audio-only stubs the backend serves to unrecognised contexts — those
  caused the "unexpected runtime error" (1004) / 1003 loader timeout at
  prepare). Per-variant media-playlist URLs and AudioFile children were
  tried and reverted: `#EXT-X-MEDIA` lives only in the master (a variant
  URL kills the audio picker) and a merged child failure fails the whole
  playback (3003 on every title).
- **Verbatim master + per-context CDN validation (the reference
  architecture, Sep 2026)**: four working implementations of this backend
  (Sushan64/NetMirror-Extension, Spyou/Zangetsu, SaurabhKaperwan/CSX,
  m2k3a/mangayomi) all emit the master URL as-is and let the CDN validate
  lazily per client context — an `in=unknown` key or a `files/220884`
  placeholder path is NOT a defect, and a datacenter-IP 404 of a variant
  URL does NOT mean the player's device will 404. All per-variant/audio
  network probes (`verifyAudioUrls`, `probeVariantAlive`, `probeUrlAlive`,
  segment Range-GETs) were REMOVED from the plugin: they were built on the
  wrong premise, gated masters that actually play (producing "No link
  found" on NewTV-only titles), and added a request burst right before the
  player's own ~11-request HLS prepare. `probeNewTvMaster` is now ONE
  master fetch + the pure structural check, cached 10 min under
  `master|<ott>|<contentId>`.
- **App-faithful playback headers — the actual dual-audio unlock**: the
  master link's `ExtractorLink.headers` are the NewTV app fingerprint
  (`newTvStreamHeaders`): `User-Agent: … /OS.GatuNewTV v1.0` (`NEWTV_UA` in
  `NetMirrorConfig.kt`), `Referer` = the player.php response referer,
  `Cookie: ott=<ott>; hd=on` (variants 404 without hd=on — Zangetsu),
  `X-Requested-With: NetmirrorNewTV v1.0`, and `Usertoken`. The player
  applies link headers to master + variants + segments, so its HLS loader
  inherits the privileged app context — that is what makes the CDN serve
  the full multi-audio master.
- **otp.php usertoken**: `GET {newtvBase}/newtv/otp.php` with header
  `otp: 111111` (dummy) + `Ott` ? response field `usertoken`, cached 55 min
  per ott in `NewTvUserToken`. This is the privileged-mode marker behind
  player.php's `status:"otp"`. Attached to player.php and playback headers;
  a failure degrades to an empty Usertoken and the flow continues (nf works
  with an empty token — Sushan64 precedent).
- **`loadLinks` hard deadline (30 s)**: the whole resolve+probe section runs
  under `withTimeoutOrNull(30_000)` — `resolveNewTvBase` can otherwise walk
  24 dead domains × 8 s and the spinner could run for minutes. On timeout
  the player gets a clean "No link found".
- **Cancellation hygiene**: every suspend network call on the play path uses
  `softCatch` (top-level in `NetMirrorConfig.kt`) which RETHROWS
  `CancellationException` — plain `runCatching` swallowed it, so cancelled
  player loads kept running and called `callback(link)` into a torn-down
  CS3 player (crash vector). `HostThrottler.gate()` also computes its wait
  under the mutex but sleeps OUTSIDE it — holding the lock during a 90 s
  cooldown delay serialized every caller behind one giant stall.
- **Playback is 100% sessionless (Sep 2026)**: `loadLinks()` touches ONLY
  embed-tmdb (net27) + NewTV player/master/audio (imgcdn/freecdn) — never the
  net7x per-IP limiter. The old native net7x play fallback (`verify()` +
  `play.php` + `playlist.php`) was REMOVED: it was the only code path that
  could produce "Too many request in short.." at play time. A title both
  sessionless paths miss now answers a clean "No link found" instead of
  risking a rate-limit storm. net7x remains only for browsing (home/search/
  post/episodes), all response-cached.
- **`player.php` response cache**: raw `newtv/player.php` bodies are cached
  for 10 min in `NetMirrorResponseCache` under `player|<ott>|<contentId>`.
  Combined with the 60-min `LinkCache` and the per-contentId master probe
  cache, repeat taps replay with zero net7x traffic.
- **No LIMITED error at play**: a LIMITED verdict on `player.php` exhausts
  its retry ladder and returns null — the embed links (if any) still play;
  `loadLinks()` never throws `limitedMessage()`.
- **Plugin `version` must be bumped** (`OTTMirror/build.gradle.kts`) on
  every user-facing change: CloudStream's updater compares the version in
  plugins.json against the installed plugin and skips identical versions —
  a rebuilt .cs3 with the same `version = 1` never reached devices, which
  masked several shipped fixes.
- **Stream referer**: every `ExtractorLink` carries `referer = $host/home` (the
  player page), never the m3u8 URL. The old `collect()` path set `referer = u`
  (the stream URL itself), which some CDNs reject as an invalid hotlink context.

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
