# Debugging Plan — IndStream Live-Server Pipeline

> **IMPLEMENTATION STATUS (2026-09-10): FIXED — shipped as IndStream v14 /
> Multimovies v12.** All root causes below were closed and unit-tested
> (`EpisodePickerTest`, updated `ServerFarmHindiTest` — 150+ tests green,
> both `.cs3` artifacts built). Live verification with `tools/server_probe.py`
> (updated: VidNest suite added, Allmovieland suite now mirrors v14 logic,
> MovieBox season parse at parity): Inception movie → 7/10 servers OK incl.
> Allmovieland via the NEW card path (5.4s, 4 languages, playable); Reacher
> TV → MovieBox OK 4.5s, VidNest OK (502-subs now correctly counted down),
> Allmovieland correctly a NO-STRIKE `MISS — library` instead of a wrong
> episode / hard strike. The one open field question (who surfaced "no link"
> at 7–8 s — app cancel vs dead first link) now answers itself from logcat:
> every tap prints `TAP#n …` with per-server arrival times, replay age,
> farm-join events and a CANCELLED line if the app kills loadLinks early.
> Fix-by-fix map: RC-A/B/C → F1-F3 (card regex, slug-verified title
> fallback, IMDb-preferred player-src walk, strict tree picker — no
> first-entry fallback, "Video Not Found" = clean miss); RC-D → F5 (502-page
> classification + episode-collapse guard); RC-E → F4 (`S1-S4`→4); F6/F7/F8
> → live-window join/tail for TV + single-flight farm + IMDB-miss ≠ host
> failure + crash ≠ timeout (no strike) + 55s/60s budget-fit kills + replay
> liveness sweep (F9). Multimovies mirrors the replay live-tail and
> single-flight (`liveFarms`/`tailLiveFarm`).

**Scope:** server appearing, load speed, live change-server-list window, episode
correctness. Symptoms reported by the user (2026-09-10, IndStream v13 on device):

| # | Symptom |
|---|---------|
| S1 | First tap of Reacher S1E1: player buffered ~7–8 s → "no link". |
| S2 | Immediate re-tap: suddenly MANY servers appear. |
| S3 | Allmovieland plays a **different episode** (same reported for VidNest/"vidnet"). |
| S4 | MovieBox (flagship) must ALWAYS show up — needs full visibility into why/when it doesn't. |

All file:line anchors are from the current HEAD (commit `0e96afe`).

---

## 1. Pipeline map (what to instrument)

```
IndStreamPlugin.kt
  load(L184)             TV: no prewarm (movies only, L202-204)
  loadLinks(L261)        replay branch L306-352 | live branch L354-442
    cacheKey L294        tmdbId|type|season|episode (FastStartCache.key)
    warmFarms L98        ONLY registered by prewarm() (L464) — movies only
    farmDone L370        detached farm; pushes via callback while window open
    45 s first-stream gate L403 | LIVE_FILL 90 s gate L427
StreamEngine.kt
  resolveRealtime(L164)  sem=16, per-server kill = timeoutSec (L188)
  emit(L246)             live path probes masters BEFORE push (≤3 s head)
  resolveMovieBox(L1221) bearer+search+detail+dl/play; seasonSuffix L1321
  resolveVidnest(L1841)  7 subs, 502-page handling L1873-1884
  resolveAllmovieland(L2320) card regex L2354, play src L2366,
                           series-tree picker L2427-2446
  FastStartCache(L2669)  5-min TTL merged list; LIVE urls replay w/o liveness probe
ServerRegistry.kt        specs+timeouts L53-349; HealthMonitor L368 (5 strikes→5 min trip)
tools/server_probe.py    existing live harness (Reacher already encoded as TITLE_TV_MB)
```

---

## 2. Confirmed root causes (live-verified 2026-09-10, Reacher tmdb 108978 / tt9288034)

Each was reproduced with read-only network probes from this machine; raw output
in Appendix A.

### RC-A — Allmovieland card regex is dead against current site markup
Live markup: `<a class="new-short__title--link" href="…/10556-reacher.html"><h3 class="new-short__title hover-op">`.
Resolver regex (`StreamEngine.kt:2355`) requires `new-short__title"` — closing quote
immediately after the word. **Never matches now** → the server can no longer find a
card for anything → "Allmovieland absent" (or stale-cache-only appearances).

### RC-B — Allmovieland is the proven wrong-episode source (two mechanisms)
1. **Play-config pick**: the resolver takes the FIRST `src:'…'` on the card
   (`L2366`). For Reacher's card that src is `tt9288030` — a *different id* than the
   show's IMDB (`tt9288034`). The player host (`laika422mon.com`) answered
   **`ERROR. Video Not F…und`** (287 B page) → parse path `no file in play page`
   (`L2382`). On hosts where that wrong entry exists, it serves whatever video the
   stray src points at → **different episode/show plays**.
2. **Silent tree fallbacks** (`L2427-2446`): season match fails → `?: playlist.optJSONObject(0)`;
   episode match (`"episode"` / `"id"`==`"$season-$episode"`) fails → `?: seasonFolder.optJSONObject(0)`.
   Any field-name drift in the series tree makes every episode request resolve to
   season-1/episode-1 — no log, no error. The `\b$season\b` title regex can also
   false-match ("Season 1 Specials" matching season 1 of a different cluster, etc.).
3. Additionally the "no file" outcome throws `IllegalStateException` → resolveOne
   never reaches its soft-miss path → **hard breaker strikes for a library miss**.

### RC-C — Allmovieland IMDB story-search returns zero results upstream
`?do=search&subaction=search&story=tt9288034` → 200, 17 KB, no cards, not even a
"Result not found" block; title search finds `10556-reacher.html`. The IMDB-keyed
entry point is no longer viable for this title (at least) → title-keyed fallback
with year + type verification is needed (probe: `.one` fallback is never tried when
`.art` *answers* — `L2341-2350` breaks on any non-null HTML).

### RC-D — VidNest: proven episode collapse + mislabelled health
- `moviebox` sub returned **the identical mp4 URL for S1E1 and S2E1** (same file,
  `macdn.aoneroom.com/other/2026-09-04/b164fbfb…mp4?mb=1`), while S1E3 was a 502.
  The aggregator ignores/collapses se/ep → wrong-episode streams enter the farm under
  one "VidNest" label.
- All three requests to `allmovies` sub returned **Cloudflare 502 JSON bodies**
  (`{"title":"Error 502: Bad gateway","error_name":"origin_bad_gateway"}`). A 502
  body is NOT blank → `resolveVidnest` (`L1869-1884`) counts it as `answered=true`
  (host-up) when it is host-down. Health signal for exactly the symptom users see
  ("server present, streams wrong/dead") is inverted.

### RC-E — MovieBox season-suffix parse collapse (affects coverage skip + clean-miss)
`seasonSuffix.value.filter { it.isDigit() }.toInt()` on `" S1-S4"` → **14**
(`S1-S16` → **116**). Verified live, Appendix A. Consequences:
- skip-guard `seasonEnd in 1 until season` (L1369) never fires → the resolver asks
  aoneroom for episodes outside the subject's stated coverage; whatever the API
  returns (its own fallback) enters the list → wrong-episode risk for later seasons;
- the "all subjects too-old" clean-miss check (L1469-1473) is corrupted in both
  directions.
**Note**: MovieBox itself handles E1/E3/S2E1 correctly (distinct signed URLs) and was
**fast** end-to-end: bearer 0.7 s + search + per-subject chain ≈ 1.1–2 s. Good news for
S4; RC-E fix + the instrumentation below will make its "show up every time" auditable.

### RC-F — Live-window hole: TV never tails the in-flight farm (`warmFarms` movie-only)
`warmFarms` is populated ONLY by `prewarm()` (`IndStreamPlugin.kt:464`), which runs
only for movies (`L204`). The Play-tap farm (`farmDone`, L370) is never registered.
So on a **re-tap while the first tap's detached farm is still resolving** (exactly
"instantly replayed"): replay pushes the PARTIAL cache, finds `warmFarms[cacheKey]==null`
(L326), and returns immediately (L351) → every later arrival lands in cache but is
**lost from the live list** (the CSX rule at L93-98). TV sessions freeze at the partial
set until a *third* tap. This asymmetry is precisely what the S2 report looks like
from the user side.

### RC-G — No single-flight per title: consecutive taps launch the whole farm twice
Cold + still-empty cache (slow farm) → `cached==null` → a second full 15-server
farm launch, doubling bandwidth while the player is already streaming (hurts the
"fast while video plays side-by-side" goal and the upstream rate limits the last
commit already fought TMDB 429s over).

### RC-H — IMDB-keyed servers are penalised by TMDB hiccups
`metaDeferred` is capped at 3 s (L289); the repo's own last commit documents
"transient TMDB 429/latency". On a miss, `imdbDeferred` → null → VaPlayer returns
`imdbId required` (StreamEngine L1691) → `failServer(...)` **hard strike** (L521).
5 taps of a TMDB-429 streak trips VaPlayer (+allmovieland via emptyList L2327 →
soft, OK) for 5 min → "server sometimes comes sometimes doesn't". A *missing id* is
not a server failure.

### RC-J — Timeout-budget invariant is violated (slow-but-alive chains canned + hard-failed)
`resolveRealtime` kills at `timeoutSec` and records a hard failure (L188-202).
- Allmovieland worst-case serial: search 8 s ×2 hosts + card 6 + play 6 + playlist 6
  + lang 5 = **39 s vs 30 s kill** (the comment at ServerRegistry L271-274 claims
  "fits"; the host-fallback double-search breaks it).
- MovieBox auth-retry path: bearer 8 + search 15 + fresh bearer 8 + search 12 +
  detail 8 + dl/play 8 = **59 s vs 40 s kill**.
Every canned chain = 1 breaker strike ⇒ flaky appearance/disappearance (S4).

### RC-K — Replay serves stale signed URLs with zero liveness checks
Replay emits `cached` with `probeManifests=false` (L314) and a 5-min TTL
(L2670). Signed mp4/m3u8 URLs (aoneroom `?sign=…`, vidnest) commonly expire far
sooner ⇒ re-open shows a fat server list whose links die 1–2 s into buffering —
indistinguishable from S1 in the field. (S1's 7–8 s is most plausibly a recorded
link that died, with no alternates recorded yet.)

### RC-L — Open: who killed the first attempt at 7–8 s?
The plugin's own floors are 45 s (`FAST_START_MAX_MS`) / 90 s (`LIVE_FILL_MS`);
a 7–8 s "no link" CANNOT be produced by plugin gates. Hypotheses (must be proven
with Phase-1 logcat, do not guess-fix):
- L1: the app shows its own error because **loadLinks' coroutine was cancelled**
  (app fork timeout / user left via back — verify with job-completion cause logging).
- L2: ≥1 link WAS pushed (t≈2–5 s: vidlink/moviebox/vidrock/videasy can answer
  that fast), player auto-selected it, it **stalled ~7–8 s, failed, and no other
  links had been recorded yet** → "no playable links" toast. The 90 s window was
  still open — pushes continued arriving but after the error dialog the user left.
- L3: replay path (`cached!=null` from a prior title visit) emitted cached links
  that are all dead (RC-K).

---

## 3. Phase 1 — Instrumentation patch (apply FIRST, no behavior change)

Add a shared tap correlation tag so every line strings together:
`Log.i("IndStream", "TAP#$n …")` with an AtomicInteger in `IndStreamProvider`.

**IndStreamPlugin.loadLinks**
1. Entry line: `TAP#n start data=$data s=$season e=$episode cold=${cached==null}`.
2. Replay: cache age `cacheAgeMs`, `warmJob=${warm?.isActive}`, tail summary line.
3. Live: register the play-path farm in `warmFarms` (this also FIXES RC-F — keep it
   behind the same key + `invokeOnCompletion` cleanup already written).
4. Single-flight (RC-G, behavior-safe): if `cacheKey` in-flight farm exists, DON'T
   launch a second — subscribe its arrivals instead (the window-poll code from the
   replay branch already does exactly this).
5. Per batch: `TAP#n +${fresh.size} from $serverId at ${ms}s` (inside the
   `onBatch`, L372-387). Also log every `callback` throw (dead app-side channel).
6. `val tapJob = coroutineContext[Job]`; `tapJob.invokeOnCompletion { cause -> … }`
   → proves/disproves L1 (app cancellation ≈ 8 s) with millisecond evidence.
7. Exit lines distinguishing: `true(n live)` / `false(noFirstArrival 45s)` /
   `false(replay 0-emitted)`.

**StreamEngine.resolveRealtime**
8. Kill path (L197-202): log elapsed, `spec.timeoutSec`, and whether the failure
   was timeout vs crash-with-throwable vs clean-miss — pass the cause out.
9. IMDB wait per server: `vaplayer/allmovieland: imdbId=${…} after ${ms}ms` →
   quantifies the 3 s cold-meta penalty (RC-H).

**resolveAllmovieland**
10. Log: host that answered, `cardUrl`, **all** extracted `src:'…'` candidates + the
    chosen one, first 120 chars of the play page when no file/key is found (RC-B mechanism
    proven visible), and the FULL series-tree shape at D=1/D=2 (titles, ids, `episode`
    field presence) once per title; log `season=EXACT|FALLBACK`, `episode=EXACT|FALLBACK`.

**resolveVidnest**
11. Classify body: empty-ish / `error_name` present → `answered=false` + reason
    (keep stream behavior identical for now — log only; decide in Phase 3 RC-D fix).

**resolveMovieBox**
12. Log every search row + parsed `(end-season, tag)` after the regex is fixed; per
    subject `(se,ep)→first-URL md5` so episode collapse upstream is instantly visible.

**HealthMonitor**
13. `recordFailure` logs `failCount/threshold` + last reason on trip; trip/expiry at Log.i.

Device run: `.\gradlew :IndStream:make` → install generated artifact, then
`adb logcat -s IndStream:V MovieBox:V VidNest:V Allmovieland:V VaPlayer:V VidLink:V`
while reproducing: (a) cold Reacher S1E1, (b) re-tap within 5 s, (c) Reacher S1E3,
(d) switch servers mid-play, (e) Inception movie twice. Capture 3 runs.

**Pass/fail for the measurement:** we must be able to state, from logs alone,
exactly which link the app auto-played, why S1 failed at 7–8 s, and whether the
live list grew after the first batch.

---

## 4. Phase 2 — Live differential probe harness (PC-side, complements logcat)

Promote today's throwaway script into `tools/episode_collapse_probe.py`:
- Matrix per server: (S1,E1), (S1,E3), (S2,E1) → flag **IDENTICAL-URL collapse**,
  per-step latency table, HTTP-vs-parse-vs-library classification (RC-B, RC-D).
- `server_probe.py` gains the missing `test_vidnest` (7 subs each) and a `--title`
  flag (accepts tmdb/imdb/season/e so field reports map 1:1 to probes).
- CI-safe: keep all selectors parseable OFF-device; add the allmovieland tree JSON
  fixture dump under `src/test/resources/` for the Phase-3 unit tests.

---

## 5. Phase 3 — Fix backlog (only after Phase 1/2 confirm; one commit per RC)

| Fix | RC | Change | Validation |
|-----|----|--------|-----------|
| F1 | A | Card regex → `<a class="new-short__title--link" href="([^"]+)\.html"` (tolerate extra h3 classes). Keep old shape as OR-alternative. | live probe: Reacher + 5 titles find cards |
| F2 | B/C | IMDB-search empty → retry by TMDB `title (+year)`; verify returned card anchor title normalizes to the show; on player page containing `Video Not Found` → CleanMiss (no strike). | JVM fixture + live |
| F3 | B | Series picker: EXACT season AND episode required (fields pinned from tree dump); mismatch ⇒ CleanMiss + loud log. Remove silent `first()` fallback everywhere. | new unit test on captured tree; S1E1≠S1E3 URL diff in device log |
| F4 | E | `seasonSuffix`: parse start+end groups (`-?S?(\d+)$`) → `seasonEnd`; single `S3` → 3. | unit table: `S1-S4→4`, `S1-S16→16`, `S3→3` |
| F5 | D | VidNest: `error_name`/502 → not answered + per-sub reason log; drop same-aggregator URL seen for a different (se,ep). | collapse probe green |
| F6 | F, G | Play-path farm registered in `warmFarms`; replay tails it (TV included); single-flight per cacheKey. | device: 2nd tap list keeps growing after first batch |
| F7 | H | Missing IMDB id ⇒ soft outcome for all IMDB servers: no breaker strike; log `id-miss`. | unit + trip-count log audit |
| F8 | J | Enforce budget invariant: raise allmovieland `timeoutSec` 30→45 OR search-host loop capped at first responder deadline; MovieBox attempt-2 capped (skip fresh-bearer retry when total elapsed budget < remaining). New JVM test asserts every server's documented worst chain ≤ timeoutSec (extend `ServerFarmHindiTest`). | `./gradlew :IndStream:testDebugUnitTest` |
| F9 | K | Replay liveness: `emit(probeManifests=false)` + a cheap ≤300 ms parallel Range-GET HEAD sweep over cached direct URLs; drop dead ones, log survivors. Cache TTL 5 min signed-URL note kept; TTL per-server if needed. | re-open after 4 min yields zero instant-fail links |
| F10 | — | Keep the Phase-1 logs (they become the permanent S4/RC audit trail). | — |

Ship as **two releases**: v14 = Phase 1 instrumentation only (zero risk, gets field
data), v15 = fixes F1–F9. Bump version + `repo.json` per repo convention.

## 6. Multimovies (parallel audit, lower priority)
Same live-grow architecture with mirrored holes: `loadLinks` fast paths return with
**no live tail** of the in-flight prefetch (`MultimoviesPlugin.kt:1015-1041`);
`withDomainRetry(retryIf={!it})` re-runs the entire load on a false (double farm,
like RC-G). Apply F6/F7 equivalents after IndStream v15 proves out.

## 7. Acceptance criteria (definition of done)
1. Cold Reacher S1E1: server list GROWS LIVE during playback until farm end (or 90 s);
   every absent server has one log line explaining it (clean-miss | timeout | id-miss | floor-drop).
2. Re-tap never freezes the list: arrivals after the replay batch reach the live UI.
3. Zero silent wrong-episode links: every episode-keyed server either serves the
   requested (s,e) (URL-diff proven) or is absent — no fallback-to-first.
4. MovieBox present on every title where aoneroom has a match, incl. later seasons,
   and never counts a title/library issue as a host failure.
5. `:IndStream:testDebugUnitTest` + both `make` tasks green; `tools/episode_collapse_probe.py`
   green against Reacher, GoT, Inception.

---

## Appendix A — evidence captured 2026-09-10 (this machine, Reacher)
- MovieBox: bearer 0.7 s; rows `Reacher [Hindi] S1-S4` + `Reacher S1-S4` matched;
  E1/E3/S2E1 URL sets all distinct; 360p/480p downloads present (dropped by 720 floor);
  `seasonEnd` parsed as 14 (should be 4).
- Allmovieland: IMDB story-search → 200/no cards; title search → `10556-reacher.html`;
  card play-config first `src` = `tt9288030`; `laika422mon.com/play/tt9288030` →
  287 B "ERROR. Video Not Found"; resolver card-regex → **no match** against current markup.
- VidNest: `allmovies` → Cloudflare 502 JSON (all three eps); `moviebox` sub → S1E1
  URL == S2E1 URL (collapse), S1E3 → 502.

## Appendix B — hypothesis ledger for S1 (the 7–8 s mystery)
Ranked most-likely-first; Phase 1 lines 4/6/7/8 resolve it in one reproduction:
1. L2 — first pushed link died on playback; alternates not yet recorded.
2. RC-L1 — app-side cancellation at ~8 s (job-completion log settles it).
3. RC-K — replay of stale signed URLs from a previous session.
4. L3/emitted>0-but-floor: arrival batches fully consumed by the 720p floor
   (`floorDroppedByServer` log at StreamEngine L425 already prints the smoking gun).
5. RC-R: a first-arrival `emit()` probe stall (master fetch ≤3 s) delaying the push
   while the app-side spinner times out — measured by per-batch timestamps.
