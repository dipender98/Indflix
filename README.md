# Indflix

[![Build](https://github.com/dipender98/Indflix/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/dipender98/Indflix/actions/workflows/build.yml)

CloudStream 3 plugin for [multimovies.motorcycles](https://multimovies.motorcycles)
(Dooplay theme). Hindi/English movies, series, and anime.

## Install

CloudStream → Settings → Extensions → Add repository:

```
https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json
```

Refresh, enable **Multimovies**.

## Sources

Each title resolves several stream sources and pulls them in parallel; the
player gets the first one that answers. Server order is static
(`SOURCE_PRIORITY` in `MultimoviesProvider.kt`), then measured latency, then
Hindi-audio preference. A source that doesn't respond in 15 s is skipped.

- dooplayer servers from the site: Cineverse, screenscape.me, GDMIRROR, Nxsha, nhdapi
- global IMDB-keyed embeds: 2embed.cc, VidSrc, 111Movies

## Search

Search goes through TMDB `/search/multi` (poster, year, rating in one call),
then a relevance filter that requires every significant query token to match.
Max 6 results. Hindi/Devanagari input is handled. The real Multimovies page
URL for each result is resolved in the background so opening a title doesn't
re-fetch the site.

## Development

JDK 17 + Android SDK.

```bash
./gradlew make                    # Multimovies/build/Multimovies.cs3 (R8-shrunk)
./gradlew makePluginsJson         # build/plugins.json
./gradlew :Multimovies:testDebugUnitTest
```

Layout:

```
Multimovies/
  src/main/kotlin/com/multimovies/
    MultimoviesProvider.kt      search / mainPage / load / loadLinks
    MultiSourcePuller.kt        parallel-pull engine, timeouts, sorting
    TmdbService.kt              TMDB metadata (+ optional SIMKL)
    ScreenscapeExtractor.kt     screenscape.me crypto, no WebView
    HostExtractors.kt           caches + global source registry
    MultimoviesPlugin.kt        entrypoint
```

Pushing to `main` triggers CI, which builds and force-pushes the `.cs3` and
`plugins.json` to the `builds` branch.

## License

MIT. See [LICENSE](LICENSE).

This plugin does not host content; it aggregates public embed hosts. Use at
your own risk and only with content you have rights to.
