# Indflix — CloudStream Extension

A CloudStream 3 provider/streamer that scrapes **Multimovies**
(`https://multimovies.motorcycles`) and pulls its video **sources** with a
reliable-first, parallel, timeout-bounded strategy.

This repo is a fork/test of the CloudStream plugin template (TestPlugins) used
for **cloutstream**. The extension lives in `Indflix/src/main/kotlin/com/indflix/`.

## Install (extension link)

Add this repository in CloudStream → **Extensions → Add repository** and paste:

```
https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json
```

Or use the repository manifest (same content):

```
https://github.com/dipender98/Indflix/blob/builds/repo.json
```

The `repo.json` points at `builds/plugins.json`, which is generated and force-pushed
to the **`builds` branch** by GitHub Actions on every push (see `.github/workflows/build.yml`).
The compiled `indflix.cs3` plugin lives alongside it on that branch.

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
(`.github/workflows/build.yml`), following the standard CloudStream
`builds`-branch model (same as recloudstream/TestPlugins):

1. Ensure **Settings → Actions → General** has `Allow all actions` and
   `Read and write permissions` (the workflow force-pushes to the `builds` branch).
2. Push to `main` → the workflow builds `indflix.cs3` + `plugins.json` and
   force-pushes them to the **`builds`** branch, alongside `repo.json`.
3. The extension is now installable at the link in [Install](#install-extension-link).

Local build (needs Android SDK + JDK 17):

```bash
./gradlew make            # builds indflix.cs3
./gradlew makePluginsJson # generates plugins.json
# output: Indflix/build/indflix.cs3 and build/plugins.json
```

## Disclaimer

Technical demo of source aggregation only. Does not host content. Streaming
copyrighted material may violate the source site's ToS and applicable law.
