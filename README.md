## About

Indflix is a [CloudStream](https://github.com/recloudstream/cloudstream)
provider plugin for movies, TV series, anime, and cartoons. It races
multiple independent stream servers per title and exposes
**multi-language audio** — Hindi, English, Tamil, Telugu, dubbed, dual-audio.

## Install

CloudStream → **Settings** → **Extensions** → **Add repository**:

```
https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json
```

## Features

- ⚡ **Instant play** — servers are probed in real time and the link most
  likely to start playing immediately is picked first (fast HLS streams,
  no waiting on slow sources); the rest fill in in the background
- 🎬 **Multi-server streaming** — 16 servers including VidLink, VaPlayer,
  VidRock, MovieBox, Videasy Hindi, 8Stream, MP4Hydra, VidZee, VixSrc
  and more
- 🌐 **Multi-language audio** — Hindi, English, Tamil, Telugu, Bengali,
  dual-audio; each stream is labelled with its actual audio language
- 📺 **Full media support** — Movies, TV series, anime, anime movies,
  cartoons
- 🎯 **Quality gate** — streams below 720p filtered out; up to 4K

## License

[GPL-3.0](LICENSE) · [Third-party notices](NOTICE)

## DMCA

This is a **provider plugin** for the open-source
[CloudStream](https://github.com/recloudstream/cloudstream) app.
It does **not** host, upload, or redistribute any copyrighted media.
All stream URLs are resolved at runtime from third-party APIs
independent of this project.

These extensions function like an ordinary browser that fetches
video files from the internet. The content accessed is not hosted
by this repository or the CloudStream app. It is the sole
responsibility of the user to comply with their country's or
state's laws. If you believe content is violating intellectual
property, please contact the actual file hosts — not the owners
of this repository.

If you are a copyright holder and believe that **source code** in
this repository infringes your copyright, open an
[issue](https://github.com/dipender98/Indflix/issues).

## Credits

- [CloudStream](https://github.com/recloudstream/cloudstream) · [recloudstream](https://github.com/recloudstream)
- [CSX CineStream](https://github.com/SaurabhKaperwan/CSX) · [CSX Utils](https://github.com/SaurabhKaperwan/Utils)
- [CloudstreamExtensions](https://github.com/CloudstreamExtensions)
- [enc-dec.app](https://enc-dec.app) decryption endpoints

