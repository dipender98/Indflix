# Indflix — CloudStream Repository

CloudStream 3 repository providing the **Multimovies** provider.  
Multimovies scrapes `https://multimovies.motorcycles` (Dooplay theme) and resolves
video sources through a parallel, timeout-bounded puller that tries reliable
servers first.

## Install

1. Open CloudStream → **Extensions → Add repository**
2. Paste this link:

```
https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json
```

3. After the repo refreshes, enable **Multimovies** in the provider list.

## Features

- Search, home sections, detail/load, and episode loading (Dooplay theme).
- Real embed resolution via `admin-ajax.php` (`doo_player_ajax`) for every
  listed server (GDMIRROR, Cineverse, Nxsha, etc.).
- **Source priority** — reliable/fast servers are tried first.
- **Parallel pulling** — all sources launched at once.
- **Per-source timeout ≈ 30 s** — one dead host cannot block the others.

## Repository structure

```
build.gradle.kts                Root gradle config (AGP 9.1.x, Kotlin 2.3.x, pinned CS gradle plugin)
settings.gradle.kts             Auto-includes subdirs with a build.gradle.kts
repo.json                       CloudStream repository manifest → builds/plugins.json
.github/workflows/build.yml     Builds .cs3 + plugins.json and force-pushes to the `builds` branch
Multimovies/
  build.gradle.kts              Module config: cloudstream { name = "Multimovies" }
  src/main/AndroidManifest.xml
  src/main/kotlin/com/multimovies/
    ├─ MultimoviesProvider.kt   MainAPI: search / mainPage / load / loadLinks + SOURCE_PRIORITY
    ├─ MultiSourcePuller.kt     Parallel + timeout + priority engine
    └─ MultimoviesPlugin.kt     @CloudstreamPlugin registration
```

## Tuning

All knobs live in `MultimoviesProvider.kt`:

```kotlin
companion object {
    const val SOURCE_TIMEOUT_MS = 30_000L
    val SOURCE_PRIORITY = listOf(
        "GDMIRROR - Recommended",
        "Cineverse",
        "Nxsha",
        "screenscape.me",
        "VidZee",
        "vixsrc.to",
        "CinemaOS",
        "vidlink.pro"
    )
}
```

Reorder `SOURCE_PRIORITY` to change which source wins.  
Change `SOURCE_TIMEOUT_MS` to tighten/loosen the per-source budget.

## Build & publish

This repo uses the standard CloudStream **`builds`-branch** model.

- Push to `main` → GitHub Actions builds `Multimovies.cs3` + `plugins.json`
  and force-pushes them to the **`builds`** branch, alongside `repo.json`.
- CloudStream reads `repo.json` → `builds/plugins.json` and lists the plugin.

Local build (needs Android SDK + JDK 17):

```bash
./gradlew make            # builds Multimovies.cs3
./gradlew makePluginsJson # generates plugins.json
# Output: Multimovies/build/Multimovies.cs3 and build/plugins.json
```

## Developer notes

- Module layout is set up for future providers (e.g. `OTT/`, `NetMirror/`).
  Add a new subdir with a `build.gradle.kts` and `settings.gradle.kts` will
  auto-include it.
- Do **not** host or redistribute copyrighted content. This is a technical
  demonstration of source aggregation only.
