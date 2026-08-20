# Indflix — CloudStream Extension

A CloudStream 3 provider/streamer that scrapes **Multimovies**
(`https://multimovies.motorcycles`) and pulls its video **sources** with a
reliable-first, parallel, timeout-bounded strategy.

This repo is a fork/test of the CloudStream plugin template (TestPlugins) used
for **cloutstream**. The extension lives in `Indflix/src/main/kotlin/com/indflix/`.

## Install (extension link)

Add this repository in CloudStream → **Extensions → Add repository** and paste:

```
https://dipender98.github.io/Indflix/repo.json
```

Or open this link from the CloudStream app to auto-add it:

```
https://dipender98.github.io/Indflix/repo.json
```

The `repo.json` is published to **GitHub Pages** automatically on every push
(see `.github/workflows/build.yml`). It lists `plugins.json`, which in turn
points at the compiled `indflix.cs3` plugin.

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
.github/workflows/build.yml   GitHub Actions: builds the provider APK on push
build.gradle.kts              CloudStream gradle plugin config (root, applies to subprojects)
settings.gradle.kts           Auto-includes every dir with a build.gradle.kts
gradle.properties             JVM/gradle flags
gradle/wrapper/               Gradle wrapper (jar committed for CI/local builds)
Indflix/
  ├─ build.gradle.kts        cloudstream { pluginName="indflix", pluginClassName="IndflixProvider" }
  ├─ src/main/AndroidManifest.xml
  └─ src/main/kotlin/com/indflix/
       ├─ IndflixProvider.kt  MainAPI: search/mainPage/load/loadLinks + SOURCE_PRIORITY
       ├─ MultiSourcePuller.kt parallel + timeout + priority engine
       └─ IndflixPlugin.kt    @CloudstreamPlugin registration
repo.json                     CloudStream repository manifest (→ plugins.json on Pages)
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

## Build & publish

This repo builds and publishes the extension via GitHub Actions
(`.github/workflows/build.yml`):

1. Ensure **Settings → Actions → General** has `Allow all actions` and
   `Read and write permissions`.
2. Enable **Settings → Pages → Build and deployment → Source: GitHub Actions**.
3. Push to `main` → the workflow builds `indflix.cs3` + `plugins.json`, writes
   `repo.json`, and deploys them to GitHub Pages.
4. The extension is now installable at the link in [Install](#install-extension-link).

Local build (needs Android SDK + JDK 17):

```bash
./gradlew make            # builds indflix.cs3
./gradlew makePluginsJson # generates plugins.json
# output: Indflix/build/indflix.cs3 and build/plugins.json
```

## Disclaimer

Technical demo of source aggregation only. Does not host content. Streaming
copyrighted material may violate the source site's ToS and applicable law.
