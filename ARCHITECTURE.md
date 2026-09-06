# Indflix architecture

Indflix contains two independently installable CloudStream plugins. They share the repository and build conventions, but their provider behavior remains isolated.

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
  build/                           generated repository/plugin metadata
  gradle/                           Gradle wrapper
```

## Rules for future changes

- Keep `Multimovies` and `IndStream` as separate installable artifacts. Do not merge provider policies or TMDB clients without compatibility tests.
- Both modules use the same flat layout — one package, files named after one primary responsibility. No `plugin/`, `core/`, `sources/`, `stream/` subfolders.
- Unit tests for both modules live in `src/test/kotlin/Test/` (package `Test`), importing `com.multimovies` or `com.indstream` classes.
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
```

Attributions and third-party licenses live in [`NOTICE`](NOTICE). The
top-level [`README.md`](README.md) is the user-facing install/usage page.
