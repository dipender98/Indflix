# Indflix

CloudStream 3 extension (provider + multi-source streamer) that scrapes
**Multimovies** (mirror: https://multimovies.motorcycles).

This repo is a fork/test of the CloudStream plugin template. It is a technical demo of a multi-source scraper/streamer.

## Architecture

- \Multimovies/src/main/kotlin/com/multimovies/MultimoviesProvider.kt\ — the \MainAPI\ provider.
  Implements \search\, \getMainPage\, \load\, \loadLinks\ against the Dooplay
  theme used by Multimovies.
- \Multimovies/src/main/kotlin/com/multimovies/MultiSourcePuller.kt\ — the **source engine**:
  - **Source priority**: \MultimoviesProvider.SOURCE_PRIORITY\ orders servers from
    most reliable/fast to least.
  - **Parallel pulling**: every server is launched concurrently via \coroutineScope\ + \sync\/\waitAll\.
  - **Per-source timeout**: each source is wrapped in \withTimeoutOrNull(30s)\
    (\SOURCE_TIMEOUT_MS\). A slow/dead source can never block the others.
  - Results are returned sorted by priority.
- \Multimovies/src/main/kotlin/com/multimovies/MultimoviesPlugin.kt\ — registers the provider.
- Build uses the **Kotlin DSL** multi-provider layout: root \uild.gradle.kts\ +
  \settings.gradle.kts\ auto-includes each provider dir that has a
  \uild.gradle.kts\ (here: \Multimovies/\). The CloudStream gradle plugin
  (\com.github.recloudstream:gradle:81b1d424d2\) is declared in the root
  \uildscript\ block with pinned commit.

## Source policy (requirement)

\\\
timeout per source ˜ 30s
pull all sources in parallel
prefer reliable + fast sources first (priority order)
\\\

Tune in one place: \SOURCE_TIMEOUT_MS\ and \SOURCE_PRIORITY\ in
\MultimoviesProvider.kt\ (consumed by \MultiSourcePuller\).

## Metadata

Metadata enrichment is **keyless by default** and uses **Cinemeta** (Stremio's public, keyless
metadata API). This matches how CSX (VegaMoviesProvider) and other CloudStream extensions enrich
content, so no personal API key is required.

- \CinemetaService\ (in \TmdbService.kt\, object repurposed from the old keyed TMDB client)
  fetches \https://v3-cinemeta.strem.io/meta/{type}/{imdbId}.json\ by IMDB id.
- \MultimoviesProvider.extractImdbId(doc)\ pulls the IMDB \tt…\ id from the Multimovies page.
- Enrichment fills \posterUrl\, \year\, \plot\, \score\ (IMDB rating), \tags\ (genre),
  \backgroundPosterUrl\, and cast via \addActors(List<String>)\ (names only — Cinemeta does
  not provide cast headshots).
- \MultimoviesProvider\ also calls \addImdbId(imdbId)\ on every LoadResponse, so CloudStream's
  built-in \TmdbProvider\ meta-provider can further enrich episodes (descriptions, ratings) and
  cast (with profile photos) when the user enables TMDB in the app.

### Optional TMDB key (ratings only)

The provider exposes a setting **"TMDB API Key (optional — used only for ratings)"**.
- If left empty: all metadata (including rating) comes from Cinemeta.
- If the user enters a TMDB key: \TmdbRatingService\ (in \TmdbRatingService.kt\) is used
  **only** to override the \score\ with TMDB's \vote_average\. Every other field still comes
  from Cinemeta. On any TMDB failure it silently falls back to Cinemeta. No key is hardcoded
  in the source.

### Poster quality

\upgradePosterUrl()\ strips Dooplay "\-WxH" thumbnail size suffixes so search/detail posters
load at full resolution instead of pixelated thumbnails.

- \epo.json\ — CloudStream repository manifest at repo root; \pluginLists\
  points to \plugins.json\ on the \uilds\ branch. This is the **extension
  link** users add in the app.

## Publishing (extension link)

The Actions workflow (\.github/workflows/build.yml\) builds the plugin with
\./gradlew make makePluginsJson\, then deploys \plugins.json\, \epo.json\ and
\Multimovies.cs3\ to the **\uilds\ branch**. The resulting install link is:

\\\
https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json
\\\

To make it live, enable **Settings ? Pages ? Source: GitHub Actions** and ensure
Actions have write permission. The \epo.json\ at the repo root mirrors this
URL for documentation.

## Build

This plugin builds against the CloudStream gradle plugin (\com.lagradost.cloudstream3.gradle\).
Requires Android SDK + JDK 17.

\\\
./gradlew make            # builds Multimovies/build/Multimovies.cs3
./gradlew makePluginsJson # generates build/plugins.json
\\\

## Legal

This is a technical demonstration of source aggregation. It does not host any
content. Streaming copyrighted material may violate the source site's ToS and
applicable law; use at your own risk and only with content you have rights to.
