# Rejected Servers (IndStream)

Working/live servers are defined in `ServerRegistry.kt` (`ServerFarm.allServers`).
This file lists servers that were researched and intentionally NOT added, with the
reason. Machine-readable mirror: `RejectedServers` in `RejectedServers.kt`
(`RejectedServers.ids`, `RejectedServers.find`, `RejectedServers.isRejected`).

Before proposing or adding a new server, check this list. Re-add only with fresh
live-probe evidence that the recorded issue is fixed.

| ID | Name | Category | Reason | Evidence | Removed in |
|---|---|---|---|---|---|
| vixsrc | VixSrc | not-reachable | Cloudflare bot challenge on whole domain | CF challenge on embed pages | v43 |
| videasy | Videasy | not-reachable | Upstream flappy (answers then empty windows); removed as dead | Empty stream windows within 2 min | v46/v62 |
| vidrock | VidRock | removed-dead | Removed (dead source) |  | v63 |
| vidrock-net | VidRock Net | removed-dead | Removed (dead source) |  | v63 |
| vidfast | VidFast | removed-dead | Embed batch removed (dead embed sources) |  | v63 |
| autoembed | AutoEmbed | removed-dead | Embed batch removed (dead embed sources) |  | v63 |
| vidphantom | VidPhantom | removed-dead | Embed batch removed (dead embed sources) |  | v63 |
| vsembed | VSEmbed | removed-dead | Embed batch removed (dead embed sources) |  | v63 |
| twoembed | 2Embed | removed-dead | Embed batch removed (dead embed sources) |  | v63 |
| twoembed-skin | 2Embed Skin | removed-dead | Embed batch removed (dead embed sources) |  | v63 |
| vidsrcme | VidSrc.me | removed-dead | Embed batch removed (dead embed sources) |  | v63 |
| vidsrc-pm | VidSrc.pm | removed-dead | Embed batch removed (dead embed sources) |  | v63 |
| rive | Rive | removed-dead | Embed batch removed (dead embed sources) |  | v63 |
| vidsrcto | VidSrc.to | removed-dead | Embed batch removed (dead embed sources) |  | v63 |
| nhd | NHD | not-reachable | Upstream movie pipeline returns 404 | playUrl 404 upstream | v46 |
| vidapi | VidAPI | not-reachable | DNS dead (vaplayer.ru) | NXDOMAIN | v46 |
| zxcstreams | ZXCStreams | not-reachable | Sub-server hosts NXDOMAIN | sub DNS NXDOMAIN | v46 |
| vidsrc-to | VidSrc.to | not-reachable | Cloudflare bot challenge on embed pages | CF challenge | v46 |
| nontongo | Nontongo | not-reachable | Cloudflare bot challenge on embed pages | CF challenge | v46 |
| braflix | Braflix | removed-dead | Removed (dead source) |  | v91 |
| onetouchtv | OneTouchTV | removed-dead | Dropped |  | v92 |
| vidcore | VidCore | removed-dead | Retired dead enc-dec upstream | enc-dec dead | v48 |
| vidcore-api | VidCore API | not-reachable | Soft miss only; upstream dead | soft miss | v48 |
| castletv | CastleTV | not-suitable | Free tier yields index_preview clips only | index_preview clips only | v65 |
| streamflix | StreamFlix | not-reachable | Catalogue ok but all mirrors dead | all mirrors dead | v65 |
| dudefilms | DudeFilms | removed-dead | Replaced by KatMovieHD (dead) |  | v93 |
| multimovies | Multimovies | removed-dead | Dropped (dead/blocked) |  | v93 |
| rivestream | Rivestream | removed-dead | Dropped (dead) |  | v93 |
| katmoviehd | KatMovieHD | removed-dead | Dropped (dead) |  | v94 |
| vaplayer | VaPlayer | not-reachable | API dead | API dead | v68 |
| videm | VidEm | not-suitable | IMDB-keyed, no working resolver |  |  |
| myflixer-hindi | MyFlixer Hindi | removed-dead | Not added (dead) |  |  |
| primesrc | PrimeSrc | removed-dead | Not added (dead) |  |  |
| vidup | VidUp | removed-dead | Removed (dead) |  | v63 |
| allmovieland | AllMovieLand | not-suitable | Unusable provider | unusable provider | v76 |
| vidnest | VidNest | removed-dead | Removed (dead upstream) |  |  |
| mp4hydra | MP4Hydra | removed-dead | Removed (dead) |  |  |
| vidzee | VidZee | removed-dead | Removed (dead) |  |  |
| streamprovider | StreamProvider | removed-dead | Removed (dead) |  |  |
| 8stream | 8Stream | not-reachable | Shared Vercel instance hard 429/403; unfixable client-side | 429/403 on Vercel | v12 |
| dahmermovies | DahmerMovies | removed-dead | Dropped as dead | dead | v25 |
| vidzy | VidZy | not-suitable | Ad-tainted fstream host | ad-tainted | v41 |
| showbox | ShowBox | removed-dead | Removed (dead) |  |  |
| vidspark | VidSpark | removed-dead | Removed (dead) |  |  |
| hdmovie2 | HDMovie2 | removed-dead | Removed (dead) |  |  |
| olamovies | OlaMovies | removed-dead | Removed (dead) |  |  |
| ezvidapi | EzVidApi | not-reachable | Cloudflare 502 | CF 502 | v65 |
| hdghartv | HDGharTV | not-suitable | Dead; skipped per user | dead | v65 |
| newtv | NewTV | removed-dead | Alternate feed dropped (netmirror -> net79) | alternate feed dropped | v78 |
| dahmer | Dahmer | removed-dead | Alias of dahmermovies (dead) |  |  |
| vidking | VidKing | removed-dead | Removed from Multimovies (dead) |  | v32 |
| 111movies | 111Movies | removed-dead | Blocked; dropped from Multimovies |  | v43 |
| vidsync | VidSync | removed-dead | Dropped as dead from Multimovies |  | v44 |

## 2026 provider sweep (researched, not added)

| ID | Name | Category | Reason | Evidence | Removed in |
|---|---|---|---|---|---|
| vidsrc-cc | Vidsrc.cc | not-reachable | HTTP 522 origin down | 522 | 2026-sweep |
| vidsrc-xyz | Vidsrc.xyz | not-reachable | DNS failure | NXDOMAIN | 2026-sweep |
| vidsrc-in | Vidsrc.in | not-reachable | DNS failure | NXDOMAIN | 2026-sweep |
| vidsrc-vip | Vidsrc.vip | not-reachable | DNS failure | NXDOMAIN | 2026-sweep |
| vidsrc-pm-host | Vidsrc.pm | not-reachable | HTTP 520 | 520 | 2026-sweep |
| vidsrc-net-host | Vidsrc.net | not-reachable | TLS failure / connection reset | TLS reset | 2026-sweep |
| vidsrc-wtf | Vidsrc.wtf | not-reachable | Redirects to parked viduki.net | parked | 2026-sweep |
| viduki | Viduki.net | not-reachable | Parked domain | parked | 2026-sweep |
| vidsrc-buzz | Vidsrc.buzz | not-reachable | Connection reset from this region | reset | 2026-sweep |
| vidora-me | Vidora.me | not-reachable | HTTP 503 | 503 | 2026-sweep |
| vidora-io | Vidora.io | not-reachable | Hostinger parked page | parked | 2026-sweep |
| hexa-family | Hexa.* | not-reachable | DNS failure / 404 across all fronts | NXDOMAIN/404 | 2026-sweep |
| hexa-su | Hexa.su | unverified | Alive SPA but JS-only; 4K unverified | SPA | 2026-sweep |
| vidfast-pro | Vidfast.pro | not-suitable | Clone of vaplayer app; same 1080p pool | clone | 2026-sweep |
| vidfast-vc | Vidfast.vc | not-suitable | Same as vidfast.pro | clone | 2026-sweep |
| filmpire-sc | Filmpire.sc | not-suitable | Shell only | shell | 2026-sweep |
| flixpire-sc | Flixpire.sc | not-suitable | Shell only | shell | 2026-sweep |
| movienova-sc | Movienova.sc | not-suitable | Shell only | shell | 2026-sweep |
| movielysium-net | Movielysium.net | not-suitable | Shell only | shell | 2026-sweep |
| moviepire-sc | Moviepire.sc | not-suitable | Connection reset on root | reset | 2026-sweep |
| cinemaflix-one | Cinemaflix.one | not-suitable | Redirects /embed to root | redirect | 2026-sweep |
| cinegram-tv | Cinegram.tv | not-suitable | Redirects /embed to root | redirect | 2026-sweep |
| moviesapi-club | MoviesAPI.club | not-reachable | DNS failure | NXDOMAIN | 2026-sweep |
| moviesapi-to | MoviesAPI.to | not-reachable | 403 Cloudflare | 403 CF | 2026-sweep |
| vidapi-xyz | VidAPI.xyz | not-reachable | 404 on both shapes | 404 | 2026-sweep |
| frembed-com | Frembed.com | not-reachable | Connect timeout | timeout | 2026-sweep |
| autoembed-cc | AutoEmbed.cc | not-reachable | DNS failure | NXDOMAIN | 2026-sweep |
| multiembed-mov | MultiEmbed.mov | not-reachable | 403 Cloudflare aggregator | 403 CF | 2026-sweep |
| vidjoy-pro | VidJoy.pro | not-suitable | Ad-redirect junk | ad redirect | 2026-sweep |
| 2embed-cc | 2Embed.cc | not-suitable | Aggregator linking to vidsrc.buzz | aggregator | 2026-sweep |
| vidzy-org | VidZy.org | unverified | Unverified clone; not cracked | unverified | 2026-sweep |
| vidplay-cc | VidPlay.cc | not-reachable | HTTP 403 | 403 | 2026-sweep |
| vidplay-pro | VidPlay.pro | not-reachable | HTTP 456 | 456 | 2026-sweep |
| vidplay-site | VidPlay.site | not-reachable | Parked (instantfwding) | parked | 2026-sweep |
| vidplay-lol | VidPlay.lol | not-reachable | Cloudflare verification wall | CF verify | 2026-sweep |
| vidplay-vip | VidPlay.vip | not-reachable | DNS failure | NXDOMAIN | 2026-sweep |
| vidplay-one | VidPlay.one | not-reachable | DNS failure | NXDOMAIN | 2026-sweep |
| vidplay-io | VidPlay.io | not-reachable | TLS failure | TLS | 2026-sweep |
| vidbinge | VidBinge | not-reachable | DNS/TLS failure across fronts | NXDOMAIN/TLS | 2026-sweep |

## vidsrc.sh / vaplayer engine (researched, not added)

The full integration spec lives in the provider research report. Key points:
- 1080p ceiling only (no real 4K anywhere reachable); honest 1080p label required.
- Requires wasm decryptor (~7.2 KB, ~18 i32 opcodes) — implementation task.
- Per-IP mint quota → CST captcha gate after ~15-20 rapid resolves; must pace + cache 24h tokens.
- Title-mismatch risk (Inside Out 2 mapped to a different film) — cross-check data.title.
- Not added yet: pending decision on whether to implement the wasm interpreter.