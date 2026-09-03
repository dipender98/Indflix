# Indflix architecture

Indflix contains two independently installable CloudStream plugins. They share the repository and build conventions, but their provider behavior remains isolated.

```text
Indflix/
  Multimovies/                     Multimovies plugin artifact
    src/main/kotlin/com/multimovies/
      plugin/                      CloudStream entrypoint and provider workflow
      core/                        HTTP, TMDB, crypto, and pure ranking helpers
      sources/                     Third-party source APIs and protocol adapters
  OTTMirror/                       OTTMirror plugin artifact
    src/main/kotlin/com/ottmirror/
      plugin/                      CloudStream entrypoint and TMDB catalog provider
      core/                        HTTP, TMDB, manifest, and title helpers
      stream/                      server registry, health, and stream orchestration
      sources/                     third-party stream adapters and crypto
  build/                           generated repository/plugin metadata
  gradle/                           Gradle wrapper
```

## Rules for future changes

- Keep `Multimovies` and `OTTMirror` as separate installable artifacts. Do not merge provider policies or TMDB clients without compatibility tests.
- Put CloudStream registration and provider workflows in `plugin/`.
- Put reusable services and pure helpers in `core/`.
- Put stream selection, health, and link emission in `stream/`.
- Put external websites, APIs, extractors, and source-specific crypto in `sources/`.
- Name files after one primary responsibility. Avoid names such as `Core.kt`, `Utils.kt`, `Extractors.kt`, or numbered replacements such as `HttpKit2.kt`.
- Keep pure parsers and matching logic network-free so JVM unit tests can cover them.
- Preserve module namespace, plugin entry class, artifact name, and generated metadata unless a release migration is intentional.
- Treat `build/` as generated output. Source changes should be validated with the affected module's tests and `make` task.

## Validation

```text
./gradlew :Multimovies:testDebugUnitTest
./gradlew :Multimovies:make
./gradlew :OTTMirror:testDebugUnitTest
./gradlew :OTTMirror:make
```
