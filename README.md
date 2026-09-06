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

## DMCA

This repository is a **provider plugin** for the open-source
[CloudStream](https://github.com/recloudstream/cloudstream) app.
It does **not** host, upload, or redistribute any copyrighted media.
All stream URLs are resolved at runtime from third-party APIs
independent of this project.

If you are a copyright holder and believe that source code or a
static asset in this repo infringes your copyright, open an issue
at <https://github.com/dipender98/Indflix/issues> or email
<dipender98@gmail.com>. The maintainer aims to respond within
**48 hours** and remove infringing material within **7 business
days**.

## Acknowledgements

Indflix stands on the shoulders of a generous open-source community. With
thanks to:

- **[CloudStream](https://github.com/recloudstream/cloudstream)** — the
  open-source streaming app this repo plugs into. None of this would
  exist without it.
- **[recloudstream](https://github.com/recloudstream)** — for maintaining
  the CloudStream gradle plugin, the extension API, and the docs that
  made writing these plugins possible.
- **[CloudstreamExtensions](https://github.com/CloudstreamExtensions)** —
  the long-running community index of CloudStream plugins, which set
  the conventions (manifest format, repo layout, plugin entry shape) we
  follow here.
- **[CSX CineStream](https://github.com/SaurabhKaperwan/CSX)**
  (SaurabhKaperwan) — the upstream pattern we leaned on hardest while
  wiring up embed harvesters, the MovieBox resolver, and the
  server-fan-out patterns. The CSX `ProviderRegistry` and
  `ApiConstants` are an excellent reference for anyone writing
  CloudStream providers in Kotlin.
- **[CSX Utils](https://github.com/SaurabhKaperwan/Utils)** — the
  dynamic provider-URL manifest (`urls.json`) that several of CSX's
  embed hosts publish; a great example of a host-rotation pattern
  resilient to CDN changes.
- The **enc-dec.app / dec-meowtv / dec-videasy / dec-vidup** family of
  decryption endpoints — referenced from the CSX ecosystem and very
  useful while reverse-engineering the embedded players.
- Every developer who has filed an issue, opened a PR, or shared a
  debug log. Bug reports are the single biggest contribution to keep
  this plugin working.

If you maintain a CloudStream plugin, an open-source stream
aggregator, or a TMDB/IMDB metadata service and you'd like to be
listed here, open an issue — happy to credit upstream work.

