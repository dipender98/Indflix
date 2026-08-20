# Indflix — CloudStream Extension

A CloudStream 3 provider/streamer that scrapes **Multimovies**
(`https://multimovies.motorcycles`) and pulls its video **sources** with a
reliable-first, parallel, timeout-bounded strategy.

This repo is a fork/test of the CloudStream plugin template (TestPlugins) used
for **cloutstream**. The template's `ExampleProvider` module is kept as a
reference; the real extension lives in `src/main/kotlin/com/indflix/`.

## Features

- Search, main page sections, detail + episode loading (Dooplay theme).
- `loadLinks` extracts every "Video Sources" server and resolves it through
  CloudStream's extractor registry.
- **Source priority** — servers tried reliable/fast-first (`SOURCE_PRIORITY`).
- **Parallel pulling** — all sources launched concurrently (`apmap`).
- **Per-source timeout ≈ 30s** — each source isolated with `withTimeoutOrNull`
  so one dead host never blocks the rest.

## Project layout

```
.github/workflows/build.yml GitHub Actions: builds the provider APK on push
build.gradle / build.gradle.kts  CloudStream plugin (com.lagradost.cloudstream) config
settings.gradle(.kts)           Root project name "Indflix"
gradle.properties               pluginInterfaceVersion + JVM/gradle flags
gradle/wrapper/                 Gradle wrapper (jar committed for CI/local builds)
src/main/AndroidManifest.xml    Provider manifest (package com.indflix)
src/main/kotlin/com/indflix/
  ├─ IndflixProvider.kt     MainAPI: search/mainPage/load/loadLinks + SOURCE_PRIORITY
  ├─ MultiSourcePuller.kt   parallel + timeout + priority engine
  └─ IndflixPlugin.kt       @CloudstreamPlugin registration
```

## Tuning the source strategy

All knobs live in `IndflixProvider.kt`:

```kotlin
companion object {
    const val SOURCE_TIMEOUT_MS = 30_000L
    val SOURCE_PRIORITY = listOf("GDMIRROR - Recommended", "Cineverse", "Nxsha", ...)
}
```

Reorder `SOURCE_PRIORITY` to change which source wins; change
`SOURCE_TIMEOUT_MS` to tighten/loosen the per-source budget.

## Build & install

This repo builds itself via GitHub Actions (`.github/workflows/build.yml`):

1. Enable Actions in repo **Settings → Actions → General** (`Allow all actions`
   and `Read and write permissions`).
2. Push to `main` → the workflow builds `app-release.apk` (uploaded as the
   `app-release` artifact on the run).
3. Install the APK in CloudStream → Add repository → enable **Indflix**.

Local build (needs Android SDK + JDK 17 + gradle 8.9):

```bash
./gradlew assembleRelease
# output: app/build/outputs/apk/release/*.apk
```

## Disclaimer

Technical demo of source aggregation only. Does not host content. Streaming
copyrighted material may violate the source site's ToS and applicable law.
