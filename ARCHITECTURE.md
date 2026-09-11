# Indflix architecture

Indflix contains three independently installable CloudStream plugins. They share the repository and build conventions, but their provider behavior remains isolated.

```text
Indflix/
  Multimovies/                     Multimovies plugin artifact
    src/main/kotlin/com/multimovies/ flat layout (no subpackages)
      MultimoviesPlugin.kt         CloudStream entrypoint and provider workflow
      SharedServices.kt            HTTP, TMDB, crypto, and pure ranking helpers
      ExternalSources.kt           Third-party source APIs and protocol adapters
  IndStream/                       IndStream plugin artifact
    src/main/kotlin/com/indstream/ flat layout (no subpackages)
      IndStreamPlugin.kt           CloudStream entrypoint and TMDB catalog provider
      CoreServices.kt              HTTP, TMDB, manifest, and title helpers
      ServerRegistry.kt            server specs + health tracking
      StreamEngine.kt              resolution orchestration and link emission
      VidLinkSource.kt             VidLink encrypted-token API + NaCl crypto
      VideasySource.kt             Videasy Hindi API + mvm1 stream cipher
  HDHub4u/                         HDHub4u plugin artifact (site-only scraper)
    src/main/kotlin/com/hdhub4u/   flat layout (no subpackages)
      HDHub4uPlugin.kt             CloudStream entrypoint and HDHub4u provider
      CoreServices.kt              domain resolver, HTTP, site search, pure parsers
  tools/                           live probe harnesses (site contract checkers)
    hdhub4u_probe.py               HDHub4u wire-shape recheck (--recheck <post-url>)
    hdhub4u_e2e.py                 3-title search->load->links playable pipeline
  build/                           generated repository/plugin metadata
  gradle/                           Gradle wrapper
```

## HDHub4u module notes

- HDHub4u is 100% site-sourced by design (user spec): search = the site's own
  typesense index (`search.html` shell / `search.pingora.fyi`, requires a
  live-site Referer), catalogs = category pages, posters/metadata = the post
  page, links = the post's own Instant/Watch/Drive buttons. No TMDB, no
  external subtitle services (site-hosted VTT captions only).
- `hdhub4u.bi` is a JS gateway, not the site: the live domain comes from the
  gateway's base64 `{h,c}` host APIs (CoreServices.DomainResolver, ~6h TTL,
  rotated start order, seed fallback `hdhub4u.ag`). All site URLs pass through
  `liveUrl()` so stale-host URLs survive rotations.
- Link resolvers (network, CoreServices): hubcdn "Instant" -> reurl b64 chain
  -> direct R2 .mkv; hdstream4u "WATCH" -> getAndUnpack(P.A.C.K.E.R) -> HLS
  master + site VTT captions; hubdrive "Drive" -> `ajax.php?ajax=direct-download`
  (408 = site's own login gate, skipped); `?id=` b64 redirectors decoded when
  they yield a URL. Emission is arrival order; fixed files below 720p are
  dropped by `passesQualityFloor` (>=720p only, per user spec).
- Recheck the wire shapes after any site rotation with
  `python tools/hdhub4u_probe.py` and `python tools/hdhub4u_e2e.py`.

## Rules for future changes

- Keep `Multimovies`, `IndStream`, and `HDHub4u` as separate installable artifacts. Do not merge provider policies or TMDB clients without compatibility tests.
- Both modules use the same flat layout — one package, files named after one primary responsibility. No `plugin/`, `core/`, `sources/`, `stream/` subfolders.
- Unit tests for all modules live in `src/test/kotlin/Test/` (package `Test`), importing `com.multimovies`, `com.indstream`, or `com.hdhub4u` classes.
- Put CloudStream registration and provider workflows in the plugin entry file.
- Put reusable services and pure helpers in CoreServices.kt.
- Put stream selection, health, and link emission in StreamEngine.kt.
- Put external websites, APIs, extractors, and source-specific crypto in dedicated *Source.kt files.
- Name files after one primary responsibility. Avoid names such as `Core.kt`, `Utils.kt`, `Extractors.kt`, or numbered replacements such as `HttpKit2.kt`.
- Keep pure parsers and matching logic network-free so JVM unit tests can cover them.
- Preserve module namespace, plugin entry class, artifact name, and generated metadata unless a release migration is intentional.
- Treat `build/` as generated output. Source changes should be validated with the affected module's tests and `make` task.

## Validation

```text
./gradlew :Multimovies:testDebugUnitTest
./gradlew :Multimovies:make
./gradlew :IndStream:testDebugUnitTest
./gradlew :IndStream:make
./gradlew :HDHub4u:testDebugUnitTest
./gradlew :HDHub4u:make
```

Attributions and third-party licenses live in [`NOTICE`](NOTICE). The
top-level [`README.md`](README.md) is the user-facing install/usage page.
