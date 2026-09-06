## Install

CloudStream → Settings → Extensions → Add repository:

```
https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json
```

## Project structure

This repository contains two separate CloudStream plugins: `Multimovies` and
`IndStream`. Both use a single flat package (`com.multimovies` and
`com.indstream` respectively). See [ARCHITECTURE.md](ARCHITECTURE.md) before
adding new files.

## License

GPL-3.0. See [LICENSE](LICENSE) for the full text and
[NOTICE](NOTICE) for third-party attributions (CSX CineStream,
CloudstreamExtensions, CloudStream).

## DMCA / takedown policy

Indflix is a **provider plugin** for the open-source
[CloudStream](https://github.com/recloudstream/cloudstream) app. It is a
discovery/aggregator layer only — it does **not** host, upload, cache,
scrape, store, mirror, or rebroadcast any video, audio, image, or other
copyrighted content itself. It resolves public JSON APIs and embed
player URLs of third-party hosts that are independent of this project,
and the stream URL is handed back to the user's CloudStream client for
direct playback from the original host.

### What this means in plain terms

- The repository contains no media files (video, audio, thumbnails, or
  subtitles) of any movie, series, anime, or other work.
- All media URLs returned by the plugins are fetched at runtime from
  the third-party hosts configured in
  [`IndStream/src/main/kotlin/com/indstream/ServerRegistry.kt`](IndStream/src/main/kotlin/com/indstream/ServerRegistry.kt)
  and the equivalent `Multimovies` source files. The project authors do
  not control, operate, or have access to those hosts.
- The plugins contain no DRM circumvention, no decryption of paid
  streaming services, and no copy-protection bypass.
- The plugins are released under [GPL-3.0](LICENSE) for the source
  code only. No copyrighted content is included in, distributed with,
  or required by the source code.

### How to send a takedown notice

If you are a copyright holder (or an authorized agent) and you believe
that **source code, metadata, or a static asset shipped in this
repository** infringes your copyright, send a written notice that
includes all 17 items of 17 U.S.C. § 512(c)(3) to the repository
maintainer:

- **GitHub**: open an issue at
  <https://github.com/dipender98/Indflix/issues> **or** use GitHub's
  built-in "Report" button on the offending file or line.
- **Email**: <dipender98@gmail.com> (PGP key available on request).

A valid notice must identify the work claimed, the allegedly
infringing material with enough detail to locate it (URL + commit
SHA + line numbers), your contact information, and a statement under
penalty of perjury that you are authorized to act for the owner.

> **Note for hosting platforms (GitHub, etc.)**: this repository does
> not host, link to, or distribute copyrighted media. DMCA notices
> that target *content returned at runtime from third-party hosts* —
> i.e. URLs or media files that never appear in this repo — are out
> of scope for § 512 takedowns against this codebase. Please direct
> those to the operators of the third-party host.

### Takedown response time

The maintainer aims to acknowledge a valid notice within **48 hours**
and to remove or rewrite the offending material within **7 business
days**. Counter-notices are handled per 17 U.S.C. § 512(g).

