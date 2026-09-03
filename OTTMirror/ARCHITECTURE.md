# OTTMirror module structure

The module is organized by responsibility so new providers and stream sources can be added without expanding a shared root file.

```text
src/main/kotlin/com/ottmirror/
  plugin/   CloudStream entrypoint and catalog provider
  core/     reusable HTTP, TMDB, manifest, and title-matching services
  stream/   server registry, health state, and resolution orchestration
  sources/  third-party source adapters and source-specific crypto
```

## Placement rules

- Add CloudStream registration or catalog behavior under `plugin/`.
- Add stateless or reusable integrations under `core/`.
- Add server selection, health, and stream emission behavior under `stream/`.
- Add a new external provider under `sources/` and keep its credentials/crypto local to that adapter.
- Keep pure parsers and matching helpers free of network calls so they remain JVM-testable.
- Name files after their primary responsibility, for example `ServerRegistry.kt`, `StreamEngine.kt`, and `VidLinkSource.kt`.

The public service objects remain stable during this organization pass, so package moves do not change resolver behavior or plugin APIs.
