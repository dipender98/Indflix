# Indflix

[![Build](https://github.com/dipender98/Indflix/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/dipender98/Indflix/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A [CloudStream](https://github.com/recloudstream/cloudstream) repository providing the **Multimovies** provider — TMDB-powered search, an 18-row home page (Bollywood, global movies, series and anime), parallel multi-source playback, and a sub-second start that ramps quality up automatically.

> **Disclaimer:** This is a technical demonstration of source aggregation. It does not host any content. Streaming copyrighted material may violate the source site's terms and applicable law — use only with content you have rights to.

## Install

1. Open CloudStream → **Settings → Extensions → Add repository**
2. Paste:

   ```text
   https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json
   ```

3. Refresh the repo, then enable **Multimovies** in the provider list.

## Features

- **18-row home page** — 5 Bollywood (incl. Netflix/Prime/Hotstar/Zee5), 5 global movies, 5 series and 3 anime rows straight from the site's categories.
- **Instant search** — one TMDB `/search/multi` call returns posters + ratings inline; a strict fuzzy-relevance gate (unicode-aware, Devanagari-safe) removes every non-matching hit. Max 6 results, 2.5 s worst-case budget.
- **Parallel multi-source pulling** — every listed server is resolved and pulled concurrently; a dead source can never block the others (15 s per-source timeout).
- **~1 s playback start** — background prefetch resolves the top player servers while you read the details page; adaptive HLS links are preferred so playback starts at a lower rendition and ramps quality up automatically.
- **Hindi-audio preference** — Hindi tracks are detected and ranked first.
- **Clear source naming** — every link is labeled `Server_Quality` (e.g. `Cineverse_Hindi_1080p`); duplicate servers get `-2` suffixes and YouTube/trailer embeds are never shown as sources.
- **Learned source speed** — measured per-source latencies refine the static priority order on your network.
- **Verified direct providers** — 2embed.cc, VidSrc and 111Movies are id-based fallbacks alongside the site's dooplayer servers.
- **Self-contained screenscape.me extractor** — reproduces the host's client-side crypto (HMAC-signed routes + AES responses) in Kotlin, no WebView or JS runtime needed.
- **Full-resolution posters** — Dooplay/TMDB/IMDB thumbnail markers stripped so artwork loads at original size.

## How it works

```mermaid
flowchart LR
    Q[Search query] --> T[TMDB search/multi]
    T --> R[Relevance gate] --> S[Results]
    S -.background: resolve MM page URL.-> M[(mmDocCache)]
    L[Detail tap] --> MD[Multimovies page + TMDB meta in parallel]
    MD --> P[Movie-only embed prefetch]
    P --> PC[(EmbedPrefetchCache)]
    P2[Play tap] --> LC{(LinkCache?)}
    LC -- hit --> E[Emit instantly]
    LC -- miss --> PC -- hit --> U[Pull sources]
    LC -- miss --> CD[cachedDocOrFetch] --> A[admin-ajax resolve] --> U
    U --> X{Per source}
    X --> SE[Screenscape crypto extractor]
    X --> RE[CloudStream extractor registry]
    X --> SN[HLS/mp4 sniffer]
    SE & RE & SN --> O[Sort: priority → latency → Hindi → adaptive HLS]
```

All caches are session-scoped with short TTLs because stream URLs carry expiring signed tokens.

## Configuration

Every tuning knob lives in `MultimoviesProvider.kt` (`SOURCE_PRIORITY` ordering is shared with `MultiSourcePuller`):

| Constant | Default | Purpose |
| --- | --- | --- |
| `SOURCE_TIMEOUT_MS` | `15_000` | Hard per-source extraction timeout |
| `EMBED_PREFETCH_COUNT` | `2` | Player servers resolved ahead of Play (movies) |
| `SOURCE_PRIORITY` | Cineverse, screenscape.me, gdmirror, Nxsha, nhdapi, 2embed, VidSrc, 111Movies | Static server ranking (fastest/most reliable first) |
| `SEARCH_MAX_RESULTS` | `6` | Search results returned |
| `SEARCH_RELEVANCE_THRESHOLD` | `0.5` | Minimum weighted relevance score |
| `SEARCH_TOTAL_BUDGET_MS` | `2500` | Worst-case uncached search budget |
| `SEARCH_CACHE_TTL_MS` | `15 min` | In-memory search cache lifetime |

Reorder `SOURCE_PRIORITY` to change which server wins; raise/lower `SOURCE_TIMEOUT_MS` to trade coverage for speed.

## Project structure

```text
build.gradle.kts                Root config: AGP 9.1.x, Kotlin 2.3.x, pinned CS gradle plugin
settings.gradle.kts             Auto-includes any subdir with a build.gradle.kts
repo.json                       CloudStream repository manifest → builds/plugins.json
.github/workflows/build.yml     Builds .cs3 + plugins.json, pushes the builds branch
LICENSE                         MIT
Multimovies/
  build.gradle.kts              Module config + shrinkCs3 (R8 re-dex into the .cs3)
  src/main/kotlin/com/multimovies/
    ├─ MultimoviesProvider.kt   MainAPI: search / mainPage / load / loadLinks, relevance engine, caches wiring
    ├─ MultiSourcePuller.kt     Parallel + timeout + priority engine, generic sniffers, SourceSpeedTracker
    ├─ TmdbService.kt           TMDB metadata engine (+ optional SIMKL search override)
    ├─ ScreenscapeExtractor.kt  Self-contained screenscape.me extractor (client-crypto port)
    ├─ HostExtractors.kt        Session caches + curated id-based GlobalSources registry
    └─ MultimoviesPlugin.kt     @CloudstreamPlugin registration
```

## Build & publish

Requires Android SDK + JDK 17.

```bash
./gradlew make            # Multimovies/build/Multimovies.cs3 (R8-shrunk via shrinkCs3)
./gradlew makePluginsJson # build/plugins.json
```

Pushing to `main` triggers CI, which builds both artifacts and force-pushes them to the **builds** branch alongside `repo.json`. The install link above then serves the new version automatically.

## Contributing

PRs are welcome. Keep changes minimal, ensure `./gradlew make` succeeds locally, and update this README plus `AGENTS.md` when behavior or constants change.

## License

[MIT](LICENSE) © 2026 dipender98

## Acknowledgments

Built on the [CloudStream](https://github.com/recloudstream/cloudstream) app and its plugin template / gradle tooling. Metadata by [TMDB](https://www.themoviedb.org/).
