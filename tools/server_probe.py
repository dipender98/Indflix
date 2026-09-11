#!/usr/bin/env python3
"""
server_probe.py — live health check for every IndStream farm server.

Replicates each resolver's request chain from StreamEngine.kt (Sept 2026) and
reports which servers actually return playable streams, for both a movie and a
TV episode. Run from anywhere:

    python tools/server_probe.py            # movie + tv suites
    python tools/server_probe.py movie      # movie only
    python tools/server_probe.py tv         # tv only

"deep" validation: every returned stream URL is fetched (m3u8 master parsed
for variants/audio, MP4 probed with a Range GET) so OK means *playable*, not
just "API answered".
"""

import base64
import concurrent.futures as futures
import json
import re
import sys
import time
import urllib3

import requests

urllib3.disable_warnings()

UA_CHROME = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
TIMEOUT = 12
RETRIES = 2

TITLE_MOVIE = dict(kind="movie", tmdb=27205, imdb="tt1375666",
                   title="Inception", year=2010, season=0, episode=0)
TITLE_TV = dict(kind="tv", tmdb=1399, imdb="tt0944947",
                title="Game of Thrones", year=2011, season=1, episode=1)
# MovieBox library alternative verified via /subject/search rows.
TITLE_TV_MB = dict(kind="tv", tmdb=108978, imdb="tt9288034",
                   title="Reacher", year=2022, season=1, episode=1)
# Indian-dub matrix (user spec 2026-09-11): one Telugu, one Tamil, one Hindi
# TV original — ids resolved via TMDB search + /external_ids (Sept 2026).
TITLE_TE = dict(kind="movie", tmdb=579974, imdb="tt8178634",
                title="RRR", year=2022, season=0, episode=0)
TITLE_TA = dict(kind="movie", tmdb=937020, imdb="tt11663228",
                title="Jailer", year=2023, season=0, episode=0)
TITLE_TV_FM = dict(kind="tv", tmdb=93352, imdb="tt9544034",
                   title="The Family Man", year=2019, season=1, episode=1)


def sess():
    s = requests.Session()
    s.verify = False
    return s


def ok_headers(referer=None):
    h = {"User-Agent": UA_CHROME}
    if referer:
        h["Referer"] = referer
    return h


def get_with_retry(fn, tries=RETRIES):
    """Retry wrapper for flaky intermediaries (enc-dec.app, vercel 429)."""
    last = None
    for i in range(tries + 1):
        try:
            r = fn()
            if r.status_code == 429:
                last = r
            else:
                return r
        except requests.RequestException as e:
            last = e
        if i < tries:
            time.sleep(1.0 + i)
    if isinstance(last, requests.Response):
        return last
    raise last


# ────────────────────────── deep validation ──────────────────────────

def check_m3u8(url, headers):
    """Fetch an HLS master; return (ok, variants, audio_langs, hindi)."""
    try:
        r = sess().get(url, headers=headers, timeout=TIMEOUT, stream=True)
        text = r.raw.read(65536, decode_content=True).decode("utf-8", "replace")
    except Exception:
        return (False, 0, [], False)
    if not text.lstrip().startswith("#EXTM3U"):
        return (False, 0, [], False)
    variants = len(re.findall(r"#EXT-X-STREAM-INF", text))
    langs = re.findall(r'LANGUAGE="([^"]+)"', text, re.I) + \
        re.findall(r'NAME="([^"]*Hindi[^"]*)"', text, re.I)
    hindi = bool(re.search(r'LANGUAGE="hi["-]|Hindi', text, re.I))
    return (True, variants, sorted(set(langs)), hindi)


def check_media(url, headers):
    """Range-GET a direct MP4/url; return (ok, http_status)."""
    h = dict(headers)
    h["Range"] = "bytes=0-2047"
    try:
        r = sess().get(url, headers=h, timeout=TIMEOUT, stream=True)
        return (r.status_code in (200, 206), r.status_code)
    except Exception:
        return (False, 0)


def deep_ok(url, is_hls, headers, label=""):
    if is_hls:
        ok, variants, langs, hindi = check_m3u8(url, headers)
        tag = "playable" if ok else "DEAD"
        extra = f" {variants} variants" + (f", audio: {langs}" if langs else "")
        if hindi:
            extra += " [HINDI]"
        return ok, f"{tag}{extra}"
    ok, status = check_media(url, headers)
    return ok, f"{'playable' if ok else 'DEAD'} (HTTP {status})"


# ────────────────────────── VidLink (TMDB, secretbox token) ──────────────────────────

VIDLINK_KEY = bytes.fromhex(
    "c75136c5668bbfe65a7ecad431a745db68b5f381555b38d8f6c699449cf11fcd")

import nacl.bindings as _nb  # PyNaCl: crypto_secretbox == XSalsa20-Poly1305


def vidlink_token(media_id):
    ts = int(time.time()) + 480
    msg = media_id.encode() + ts.to_bytes(8, "big")
    box = _nb.crypto_secretbox(msg, b"\x00" * 24, VIDLINK_KEY)
    return base64.urlsafe_b64encode(b"\x00" * 24 + box).decode().rstrip("=")


def test_vidlink(t):
    tok = vidlink_token(str(t["tmdb"]))
    if t["kind"] == "movie":
        api = f"https://vidlink.pro/api/b/movie/{tok}?multiLang=1"
        page = f"https://vidlink.pro/movie/{t['tmdb']}"
    else:
        api = f"https://vidlink.pro/api/b/tv/{tok}/{t['season']}/{t['episode']}?multiLang=1"
        page = f"https://vidlink.pro/tv/{t['tmdb']}/{t['season']}/{t['episode']}"
    h = {"User-Agent": UA_CHROME, "Accept": "*/*",
         "Accept-Language": "en-US,en;q=0.9",
         "Origin": "https://vidlink.pro", "Referer": page}
    r = sess().get(api, headers=h, timeout=TIMEOUT)
    root = r.json()
    if root is None:  # API answers 200 with a bare `null` (e.g. RRR, 2026-09-11)
        return ("MISS", "api returned null body (no multiLang source)", 0, None)
    if "error" in root or "code" in root:
        return ("FAIL", f"api error: {str(root)[:80]}", 0, None)
    stream = root.get("stream") or {}
    streams = []
    qualities = stream.get("qualities") or {}
    for k, q in qualities.items():
        hgt = int(k) if str(k).isdigit() else 0
        if hgt >= 720 and q.get("url"):
            streams.append((f"{hgt}p", q["url"], False, q.get("headers") or {}))
    playlist = stream.get("playlist") or root.get("url")
    if playlist:
        streams.append(("master", playlist, True, {}))
    if not streams:
        return ("MISS", f"no qualities/playlist (keys={list(stream.keys())})", 0, None)
    label, url, is_hls, qh = streams[0]
    ph = {"User-Agent": "ExoPlayer"}
    for k in ("referer", "origin"):
        if qh.get(k):
            ph[k.capitalize()] = qh[k]
    ok, detail = deep_ok(url, is_hls, ph, label)
    return ("OK" if ok else "DEAD", f"{len(streams)} streams; first [{label}]: {detail}",
            len(streams), streams)


# ────────────────────────── VaPlayer (IMDB) ──────────────────────────

def test_vaplayer(t):
    api = (f"https://streamdata.vaplayer.ru/api.php?imdb={t['imdb']}&type=movie"
           if t["kind"] == "movie" else
           f"https://streamdata.vaplayer.ru/api.php?imdb={t['imdb']}&type=tv"
           f"&season={t['season']}&episode={t['episode']}")
    r = sess().get(api, headers=ok_headers("https://nextgencloudfabric.com/"), timeout=TIMEOUT)
    root = r.json()
    code = str(root.get("status_code"))  # API returns "200" as a STRING
    if code == "404":
        return ("MISS", "not in catalog (404)", 0, None)
    if code != "200":
        return ("FAIL", f"status_code={code}", 0, None)
    urls = (root.get("data") or {}).get("stream_urls") or []
    if not urls:
        return ("MISS", "200 but no stream_urls", 0, None)
    ok, detail = deep_ok(urls[0], True, ok_headers("https://nextgencloudfabric.com/"))
    return ("OK" if ok else "DEAD", f"{len(urls)} masters; first: {detail}", len(urls), urls)


# ────────────────────────── VidRock (TMDB, AES-GCM) ──────────────────────────

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

VIDROCK_KEY = bytes.fromhex(
    "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f")


def vidrock_decrypt(payload):
    data = base64.b64decode(payload.replace("-", "+").replace("_", "/") +
                            "=" * ((4 - len(payload) % 4) % 4))
    nonce, ct = data[:12], data[12:]
    return AESGCM(VIDROCK_KEY).decrypt(nonce, ct, None).decode("utf-8")


def test_vidrock(t):
    api = (f"https://vidrock.ru/api/movie/{t['tmdb']}/" if t["kind"] == "movie"
           else f"https://vidrock.ru/api/tv/{t['tmdb']}/{t['season']}/{t['episode']}/")
    h = {"User-Agent": UA_CHROME, "Origin": "https://vidrock.ru",
         "Referer": "https://vidrock.ru/"}
    root = sess().get(api, headers=h, timeout=TIMEOUT).json()
    streams = []
    for name, sd in (root.items() if isinstance(root, dict) else []):
        enc = sd.get("url") or ""
        if not enc or enc in ("error", "null"):
            continue
        try:
            url = vidrock_decrypt(enc)
        except Exception as e:
            return ("FAIL", f"decrypt failed for {name}: {e}", 0, None)
        lang = sd.get("language") or ""
        is_hls = ".m3u8" in url or sd.get("type") == "hls"
        streams.append((f"{name} {lang}".strip(), url, is_hls))
    if not streams:
        return ("MISS", f"no servers (root keys={list(root)[:5]})", 0, None)
    label, url, is_hls = streams[0]
    ok, detail = deep_ok(url, is_hls, {"User-Agent": UA_CHROME, "Referer": "https://vidrock.ru/"})
    return ("OK" if ok else "DEAD", f"{len(streams)} servers; first [{label}]: {detail}",
            len(streams), streams)


# ────────────────────────── Videasy Hindi (TMDB, mvm1 cipher) ──────────────────────────

def _mix(x):
    x &= 0xFFFFFFFF
    x ^= x >> 16
    x = (x * 2246822507) & 0xFFFFFFFF
    x ^= x >> 13
    x = (x * 3266489909) & 0xFFFFFFFF
    x ^= x >> 16
    return x


def _fnv1a(s):
    t = 2166136261
    for ch in s:
        t = ((t ^ ord(ch)) * 16777619) & 0xFFFFFFFF
    return _mix(t)


def _rotl(v, t):
    t &= 31
    if t == 0:
        return v & 0xFFFFFFFF
    return ((v << t) | (v >> (32 - t))) & 0xFFFFFFFF


class _VState:
    __slots__ = ("slots", "acc")

    def __init__(self, seed, media_id):
        self.slots = {}
        wm = _mix((media_id ^ 0x9E3779B9) & 0xFFFFFFFF)
        a = _mix((_fnv1a(seed) ^ wm) & 0xFFFFFFFF)
        for e in range(8):
            idx = a % 61
            a = _rotl((a + 0x9E3779B9) & 0xFFFFFFFF, 7 + (7 & e))
            self.slots[idx] = a ^ _mix(a)
            a = _mix((a + idx) & 0xFFFFFFFF)
        self.acc = _mix(0xA5A5A5A5 ^ a)

    def keystream(self, counter):
        o = self.acc
        n = o % 61
        in_r = n in self.slots
        d = self.slots.get(n, 0)
        a = d ^ ((0x9E3779B9 * (counter + 1)) & 0xFFFFFFFF)
        l = (o | a) if in_r else (o ^ a)
        l2 = _rotl((l + o) & 0xFFFFFFFF, 31 & n) ^ _rotl(o, 31 & ((n * 7) & 0xFFFFFFFF))
        o = _mix((l2 + 0x9E3779B9) & 0xFFFFFFFF)
        self.slots[n] = o
        self.acc = o
        return o


def videasy_decrypt(b64, seed, media_id):
    b64 = b64.strip().replace("\\/", "/")
    raw = base64.b64decode(b64.replace("-", "+").replace("_", "/") +
                           "=" * ((4 - len(b64) % 4) % 4))
    st = _VState(seed, media_id)
    out = bytearray()
    counter = 0
    for i in range(0, len(raw), 4):
        v = st.keystream(counter)
        counter += 1
        for shift in (0, 8, 16, 24):
            if i + (shift // 8) >= len(raw):
                break
            out.append(raw[i + shift // 8] ^ ((v >> shift) & 0xFF))
    if bytes(out[:4]) != b"mvm1":
        raise ValueError(f"bad header {bytes(out[:4])!r}")
    return bytes(out[4:]).decode("utf-8", "replace")


def test_videasy_hindi(t):
    API = "https://api.speedracelight.com"
    vh = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
          "Accept": "*/*", "Origin": "https://player.videasy.net",
          "Referer": "https://player.videasy.net/"}
    seed = get_with_retry(
        lambda: sess().get(f"{API}/seed?mediaId={t['tmdb']}", headers=vh, timeout=TIMEOUT)
    ).json().get("seed")
    if not seed:
        return ("FAIL", "no seed", 0, None)
    q = (f"mediaType={'tv' if t['kind'] == 'tv' else 'movie'}&tmdbId={t['tmdb']}"
         f"&imdbId={t['imdb']}&year={t['year']}&enc=2&seed={seed}&title={t['title']}")
    if t["kind"] == "tv":
        q += f"&seasonId={t['season']}&episodeId={t['episode']}"
    enc = get_with_retry(
        lambda: sess().get(f"{API}/hdmovie/sources-with-title?{q}", headers=vh, timeout=15)
    ).text.strip()
    if not enc:
        return ("MISS", "empty response", 0, None)
    if enc.startswith("{") or enc.startswith("<"):
        err = re.search(r'"message":"([^"]+)"', enc)
        return ("UPSTREAM", f"scraper error: {err.group(1)[:60] if err else enc[:60]}", 0, None)
    root = json.loads(videasy_decrypt(enc, seed, t["tmdb"]))
    sources = [s for s in root.get("sources", []) if s.get("url")]
    hindi = [s for s in sources if s.get("quality", "").lower() == "hindi"]
    if not sources:
        return ("MISS", "no sources at all", 0, None)
    if not hindi:
        return ("MISS", f"sources but no Hindi label: {[s['quality'] for s in sources]}", 0, None)
    ok, detail = deep_ok(hindi[0]["url"], True, vh)
    return ("OK" if ok else "DEAD",
            f"{len(sources)} sources, {len(hindi)} Hindi; first: {detail}",
            len(hindi), hindi)


# ────────────────────────── MovieBox (title) ──────────────────────────

_MB_BASE = "https://h5-api.aoneroom.com"
_MB_REF = "https://fmoviesunblocked.net/"


def test_moviebox(t):
    sx = sess()
    xuser = sx.get(f"{_MB_BASE}/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
                   headers=ok_headers(), timeout=TIMEOUT).headers.get("x-user")
    token = (json.loads(xuser).get("token") if xuser else None)
    if not token:
        return ("FAIL", "no x-user token", 0, None)
    base_h = {"User-Agent": UA_CHROME,
              "X-Client-Info": '{"timezone":"Asia/Kolkata"}',
              "Accept-Language": "en-US,en;q=0.5", "Accept": "application/json",
              "Referer": _MB_BASE, "Connection": "keep-alive",
              "Authorization": f"Bearer {token}"}
    r = sx.post(f"{_MB_BASE}/wefeed-h5api-bff/subject/search", headers=base_h,
                json={"keyword": t["title"], "page": 1, "perPage": 24,
                      "subjectType": 1 if t["kind"] == "movie" else 2},
                timeout=TIMEOUT)
    data = r.json().get("data") or {}
    items = (data.get("data") or data).get("items") or []

    sfx = re.compile(r"\s+S(\d+)(?:\s*-\s*S?(\d+))?$", re.I)
    brk = re.compile(r"[\[(]([^\])]+)[\])]", re.I)
    norm = lambda s: re.sub(r"[^a-z0-9]", "", s.lower())
    tn = norm(t["title"])
    subjects = []
    rows_seen = []
    for it in items:
        sid, raw = it.get("subjectId"), it.get("title", "")
        if not sid:
            continue
        rows_seen.append(raw)
        m = sfx.search(raw)
        # Range END (matches StreamEngine.movieboxSeasonEnd): "S1-S4" -> 4,
        # NOT the old digit-filter "S1-S4" -> 14 that defeated the skip guard.
        season_end = int(m.group(2) or m.group(1)) if m else 0
        audio = next((g for g in brk.findall(raw)
                      if any(c.isalpha() for c in g) and not any(c.isdigit() for c in g)), None)
        clean = norm(re.sub(r"\s*\d{4}", "",
                            re.sub(r"\s*[\(\[][^)\]]*[\)\]]", "", sfx.sub("", raw)).strip()))
        if clean == tn or (len(tn) >= 4 and clean.startswith(tn)):
            subjects.append((sid, season_end, audio))
    if not subjects:
        return ("MISS", f"no match for '{t['title']}' in {len(items)} rows: {rows_seen[:3]}", 0, None)

    streams = []
    for sid, season_end, audio in subjects[:4]:
        if t["kind"] == "tv" and 1 <= season_end < t["season"]:
            continue
        det = sx.get(f"https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id={sid}",
                     headers=ok_headers(), timeout=TIMEOUT).json()
        dp = (((det.get("data") or {}).get("items") or [{}])[0].get("subject") or {}).get("detailPath", "")
        if not dp:
            continue
        rh = dict(base_h)
        rh["Referer"] = f"{_MB_REF}spa/videoPlayPage/movies/{dp}?id={sid}&type=/movie/detail"
        rh["Origin"] = _MB_REF.rstrip("/")
        params = f"subjectId={sid}" + (f"&se={t['season']}&ep={t['episode']}" if t["kind"] == "tv" else "") + f"&detailPath={dp}"
        out = {}
        for name, ep in (("download", "/subject/download?"), ("play", "/subject/play?")):
            try:
                out[name] = (sx.get(f"{_MB_BASE}/wefeed-h5api-bff{ep}{params}",
                                    headers=rh, timeout=TIMEOUT).json().get("data") or {})
            except Exception:
                out[name] = {}
        sub_data = out["play"].get("data") or out["play"]
        for arr, tag in ((out["download"].get("data", out["download"]).get("downloads"), ""),
                         (sub_data.get("streams"), ""), (sub_data.get("dash"), " dash")):
            for s in arr or []:
                if s.get("vipLocked") or not s.get("url"):
                    continue
                res = str(s.get("resolutions") or s.get("resolution") or 0)
                streams.append((f"{res}p{tag} {audio or ''}".strip(), s["url"],
                                ".m3u8" in s["url"] or tag == " dash"))
    if not streams:
        return ("MISS", f"{len(subjects)} subjects matched but no usable streams", 0, None)
    ok, detail = deep_ok(streams[0][1], streams[0][2],
                         {"User-Agent": UA_CHROME, "Referer": _MB_REF})
    return ("OK" if ok else "DEAD", f"{len(subjects)} subjects, {len(streams)} streams; first: {detail}",
            len(streams), streams)


# ────────────────────────── 8Stream (IMDB, movies only) ──────────────────────────

def test_8stream(t):
    if t["kind"] != "movie":
        return ("SKIP", "movies only (no episode targeting)", 0, None)
    base = "https://8-stream-api.vercel.app"
    h = ok_headers(f"{base}/")
    r = get_with_retry(lambda: sess().get(f"{base}/api/v1/mediaInfo?id={t['imdb']}",
                                          headers=h, timeout=TIMEOUT))
    if r.status_code == 429:
        return ("RATE-LIM", "vercel security checkpoint (429) — indeterminate from this IP", 0, None)
    try:
        root = r.json()
    except Exception:
        return ("FAIL", f"non-JSON ({r.status_code}): {r.text[:60]}", 0, None)
    data = root.get("data") or {}
    key, playlist = data.get("key"), data.get("playlist") or []
    if not key or not playlist:
        return ("MISS", "no key/playlist", 0, None)
    streams = []
    for entry in playlist:
        try:
            rr = get_with_retry(
                lambda e=entry: sess().post(f"{base}/api/v1/getStream", headers=h,
                                            json={"file": e["file"], "key": key}, timeout=TIMEOUT))
            link = (rr.json().get("data") or {}).get("link") or ""
            if link.startswith("http"):
                streams.append((entry.get("title", "Multi"), link, ".m3u8" in link))
        except Exception:
            continue
    if not streams:
        return ("FAIL", "getStream returned nothing", 0, None)
    ok, detail = deep_ok(streams[0][1], streams[0][2], {"User-Agent": UA_CHROME, "Referer": f"{base}/"})
    return ("OK" if ok else "DEAD", f"{len(streams)} language streams; first: {detail}",
            len(streams), streams)


# ────────────────────────── VidUp / VidCore (enc-dec pipeline) ──────────────────────────

def test_encdec(host, variant, t):
    ref = f"https://{host}/"
    page = (f"{ref}movie/{t['tmdb']}" if t["kind"] == "movie"
            else f"{ref}tv/{t['tmdb']}/{t['season']}/{t['episode']}")
    page_text = sess().get(page, headers=ok_headers(ref), timeout=TIMEOUT).text
    m = re.search(r'\\"(?:en|token)\\":\\"([^\\]+)\\"', page_text)
    if not m:
        return ("FAIL", "no token in page", 0, None)
    import urllib.parse
    enc = get_with_retry(
        lambda: sess().get(f"https://enc-dec.app/api/enc-{variant}?text={urllib.parse.quote(m.group(1))}",
                           timeout=TIMEOUT))
    try:
        res = enc.json().get("result") or {}
    except Exception:
        return ("FAIL", f"enc-dec enc failed ({enc.status_code}): {enc.text[:60]}", 0, None)
    servers_url, stream_base = res.get("servers"), res.get("stream")
    csrf = res.get("token") or ""  # enc-dec.app now often returns EMPTY token; header optional
    if not (servers_url and stream_base):
        return ("FAIL", f"enc result incomplete: {list(res.keys())}", 0, None)
    sh = ok_headers(ref)
    if csrf:
        sh["X-CSRF-Token"] = csrf
    srv_enc = sess().post(servers_url, headers=sh, timeout=TIMEOUT).text
    dec = get_with_retry(
        lambda: sess().post(f"https://enc-dec.app/api/dec-{variant}",
                            headers={"Content-Type": "application/json", "User-Agent": UA_CHROME},
                            json={"text": srv_enc.strip()}, timeout=TIMEOUT))
    try:
        srv_arr = dec.json().get("result") or []
    except Exception:
        return ("FAIL", f"dec failed ({dec.status_code}): {dec.text[:60]}", 0, None)
    streams = []
    for srv in srv_arr:
        sdata = srv.get("data") or ""
        if not sdata:
            continue
        try:
            stream_enc = sess().post(f"{stream_base}/{sdata}", headers=sh, timeout=TIMEOUT).text
            dec2 = get_with_retry(
                lambda se=stream_enc: sess().post(
                    f"https://enc-dec.app/api/dec-{variant}",
                    headers={"Content-Type": "application/json", "User-Agent": UA_CHROME},
                    json={"text": se.strip()}, timeout=TIMEOUT))
            r3 = dec2.json().get("result") or {}
            url = r3.get("url") or ""
            if url.startswith("http"):
                q = "4K" if "4K" in (srv.get("description") or "") or "Premier" in srv.get("name", "") else "HD"
                streams.append((f"{srv.get('name', 'S')} {q}", url, True))
        except Exception:
            continue
    if not streams:
        return ("MISS", f"{len(srv_arr)} sub-servers, no m3u8", 0, None)
    ok, detail = deep_ok(streams[0][1], True, {"User-Agent": UA_CHROME, "Referer": ref})
    return ("OK" if ok else "DEAD", f"{len(streams)} streams; first: {detail}", len(streams), streams)


def test_vidup(t):
    return test_encdec("vidup.to", "vidup", t)


def test_vidcore(t):
    return test_encdec("vidcore.io", "vidcore", t)


# ────────────────────────── Allmovieland (IMDB, Hindi) ──────────────────────────

def test_allmovieland(t):
    """Mirrors the v14 (Sept 2026 audit) resolver: two card-markup shapes +
    slug-verified title fallback, IMDb-preferred player-src walk with
    'Video Not Found' skips (library miss, not host failure), STRICT
    season/episode tree match — never a silent first-entry fallback."""
    import urllib.parse
    s = sess()
    hosts = ["https://allmovieland.art", "https://allmovieland.one"]
    CARD = re.compile(
        r'<a\s+class="new-short__title--link"\s+href="(https?://allmovieland\.[a-z]+/[^"]+\.html)'
        r'|href="(https?://allmovieland\.[a-z]+/[^"]+\.html)"\s*>\s*<h3 class="new-short__title')

    def find_card(html, title=None):
        for mm in CARD.finditer(html):
            url = mm.group(1) or mm.group(2)
            if not title:
                return url
            tn = re.sub(r"[^a-z0-9]", "", title.lower())
            slug = re.sub(r"^\d+-", "", url.rsplit("/", 1)[-1][:-5])
            slug = re.sub(r"[^a-z0-9]", "", slug.lower())
            if slug == tn or slug.startswith(tn) or (len(tn) >= 4 and tn in slug):
                return url
        return None

    card_url = None
    host_used = None
    for host in hosts:
        try:
            search = s.get(f"{host}/?do=search&subaction=search&story={t['imdb']}",
                           headers=ok_headers(f"{host}/"), timeout=12).text
        except Exception:
            continue
        host_used = host
        card_url = find_card(search)
        if card_url:
            break
    if not card_url and t.get("title"):
        host_used = host_used or hosts[0]
        try:
            q = urllib.parse.quote(t["title"])
            by_title = s.get(f"{host_used}/?do=search&subaction=search&story={q}",
                             headers=ok_headers(f"{host_used}/"), timeout=12).text
            card_url = find_card(by_title, t["title"])
        except Exception:
            pass
    if not card_url:
        return ("MISS", "no card (IMDB-search empty shells AND title-miss)", 0, None)

    card = s.get(card_url, headers=ok_headers(card_url), timeout=15)
    card_html = card.text
    dom = re.search(r"AwsIndStreamDomain\s*=\s*'([^']+)'", card_html)
    srcs = list(dict.fromkeys(v for v in re.findall(r"src:\s*'([^']+)'", card_html) if v))
    print(f"      card={card_url.rsplit('/', 1)[-1]} player srcs={srcs[:4]}")
    if not (dom and srcs):
        return ("FAIL", "no player domain/srcs in card", 0, None)
    base = dom.group(1).rstrip("/")
    ordered = [x for x in srcs if t["imdb"] in x] + [x for x in srcs if t["imdb"] not in x]
    f = k = None
    play_url = None
    for cand in ordered[:2]:
        play_url = f"{base}/play/{cand}"
        try:
            ph = s.get(play_url, headers=ok_headers(card_url), timeout=10).text
        except Exception:
            continue
        if not ph.strip() or "video not found" in ph.lower():
            continue
        f = re.search(r"""["']?file["']?\s*[:=]\s*["']([^"']+)["']""", ph)
        k = re.search(r"""["']?key["']?\s*[:=]\s*["']([^"']+)["']""", ph)
        if f and k:
            break
        f = k = None
    if not (f and k):
        return ("MISS", "no playable entry behind card player src(s) (library), host fine", 0, None)
    file = f.group(1).replace("\\/", "/")
    file_url = file if file.startswith("http") else (
        base + file if file.startswith("/playlist/") else f"{base}/playlist/{file}")
    cdn = file_url.split("/playlist/")[0]
    ph = ok_headers(play_url)
    ph["X-Csrf-Token"] = k.group(1)
    pl = s.get(file_url, headers=ph, timeout=15)
    try:
        entries = pl.json()
    except Exception:
        return ("FAIL", "playlist not JSON", 0, None)

    def collect_leaves(arr, depth=0):
        leaves = []
        for e in arr:
            if not isinstance(e, dict):
                continue
            nested = e.get("folder")
            if isinstance(nested, list) and depth < 3:
                leaves += collect_leaves(nested, depth + 1)
            elif e.get("file"):
                leaves.append((e.get("title") or "Multi", e["file"]))
        return leaves

    def is_season(e, season):
        kids = e.get("folder") or []
        if not kids or any(k2.get("file") for k2 in kids if isinstance(k2, dict)):
            return False
        title = e.get("title", "")
        word = re.search(r"season|сезон", title, re.I) or re.search(rf"\bs{season}\b", title, re.I)
        return bool((word and re.search(rf"\b{season}\b", title)) or str(e.get("id")) == str(season))

    def is_episode(e, season, episode):
        kids = e.get("folder") or []
        if not kids:
            return False
        return (str(e.get("episode")) == str(episode)
                or str(e.get("id")) == f"{season}-{episode}"
                or re.search(rf"(?i)(?:^|[^a-z0-9])(?:episode|ep\.?|e\.?)[.\s_:=-]*0*{episode}(?!\d)", e.get("title", ""))
                or re.search(rf"(?i)s\d+e\s*0*{episode}(?!\d)", e.get("title", "")))

    if t["kind"] == "tv":
        so = next((e for e in entries if is_season(e, t["season"])), None)
        eps = (so.get("folder") or []) if so else (entries if t["season"] == 1 else [])
        ep_obj = next((e for e in eps if isinstance(e, dict) and is_episode(e, t["season"], t["episode"])), None)
        print(f"      tree: seasons={[e.get('title') for e in entries][:4]} ep-picked={ep_obj.get('title') if ep_obj else None!r}")
        if ep_obj is None:
            return ("MISS", f"STRICT picker: no exact S{t['season']}E{t['episode']} (v14 never falls back to first)", 0, None)
        lang_entries = collect_leaves(ep_obj.get("folder") or [])
    else:
        lang_entries = [(e.get("title") or "Multi", e["file"])
                        for e in entries if isinstance(e, dict) and e.get("file")]

    streams = []
    for lang, lf in lang_entries:
        lf = lf.replace("\\/", "/")
        url = lf if lf.startswith("http") else f"{cdn}/playlist/{lf}.txt"
        m8 = s.get(url, headers=ph, timeout=15).text.strip().replace("\\/", "/")
        if m8.startswith("http"):
            streams.append((lang, m8, True))
    if not streams:
        return ("MISS", f"{len(lang_entries)} entries, no m3u8", 0, None)
    hindi = [x for x in streams if "hindi" in x[0].lower()]
    ok, detail = deep_ok(streams[0][1], True, ph)
    hindi_note = f", {len(hindi)} Hindi" if hindi else ""
    return ("OK" if ok else "DEAD", f"{len(streams)} languages{hindi_note}; first: {detail}",
            len(streams), streams)


# ────────────────────────── VidNest (TMDB fan-out, 7 subs) ──────────────────────────

VN_ALPHABET = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="


def vn_decode(inp):
    rev = {c: i for i, c in enumerate(VN_ALPHABET)}
    pad = (4 - len(inp) % 4) % 4
    inp += "=" * pad
    out = bytearray()
    for i in range(0, len(inp), 4):
        c0 = rev.get(inp[i], 64)
        c1 = rev.get(inp[i + 1], 64)
        c2 = 64 if inp[i + 2] == "=" else rev.get(inp[i + 2], 64)
        c3 = 64 if inp[i + 3] == "=" else rev.get(inp[i + 3], 64)
        if c0 == 64 or c1 == 64:
            return None
        out.append(((c0 << 2) | (c1 >> 4)) & 0xFF)
        if c2 != 64:
            out.append((((c1 & 15) << 4) | (c2 >> 2)) & 0xFF)
        if c3 != 64:
            out.append((((c2 & 3) << 6) | c3) & 0xFF)
    return bytes(out).decode("utf-8", "replace")


def vn_is_error_page(text):
    """Same classifier as StreamEngine.vidnestIsErrorPage: Cloudflare 502
    bodies are host-DOWN, not 'answered'."""
    t0 = text.lstrip()
    if not t0:
        return False
    if t0.startswith("<"):
        return True
    low = t0.lower()
    return ('"error_name"' in low) or ("bad gateway" in low) or ('"cloudflare"' in low) or ("error 50" in low)


def test_vidnest(t):
    s = sess()
    vh = ok_headers("https://vidnest.fun/")
    vh["Origin"] = "https://vidnest.fun"
    subs = ("moviebox", "allmovies", "klikxxi", "onehd", "hollymoviehd", "purstream", "vidlink")
    streams, answered, downs = [], 0, []
    for sub in subs:
        url = (f"https://new.vidnest.fun/{sub}/movie/{t['tmdb']}" if t["kind"] == "movie"
               else f"https://new.vidnest.fun/{sub}/tv/{t['tmdb']}/{t['season']}/{t['episode']}")
        try:
            raw = s.get(url, headers=vh, timeout=12).text
        except Exception:
            downs.append(sub)
            continue
        if not raw.strip() or vn_is_error_page(raw):
            downs.append(sub)
            continue
        answered += 1
        try:
            root = json.loads(raw)
            if root.get("encrypted") and root.get("data"):
                root = json.loads(vn_decode(root["data"]))
        except Exception:
            continue
        pick = lambda o: o.get("link") or o.get("url") or o.get("file") or ""
        arr = (root.get("url") or root.get("streams") or root.get("sources") or [])
        if isinstance(arr, dict):
            arr = [arr]
        for e in arr:
            if isinstance(e, dict) and str(pick(e)).startswith("http"):
                streams.append((str(e.get("lang") or e.get("language") or sub),
                                pick(e), ".m3u8" in pick(e)))
        one = root.get("data", {}).get("stream", {}).get("playlist") if sub == "vidlink" else root.get("url")
        if isinstance(one, str) and one.startswith("http") and not streams:
            streams.append((sub, one, ".m3u8" in one))
    if not streams:
        status = "DOWN" if answered == 0 else "MISS"
        return (status, f"{answered}/{len(subs)} answered, down: {downs or '—'}", 0, None)
    ok, detail = deep_ok(streams[0][1], streams[0][2], vh)
    return ("OK" if ok else "DEAD",
            f"{answered}/{len(subs)} answered, {len(streams)} streams; first: {detail}",
            len(streams), streams)


# ────────────────────────── NHD (TMDB, page-keyed extraction API) ──────────────────────────

NHD_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")


def test_nhd(t):
    """Mirrors resolveNhd: page → var API_KEY/API_PATH → extraction JSON
    (playUrl / audioTracks[]) with the NHD UA."""
    id_ = str(t["tmdb"])
    page_url = (f"https://nhdapi.com/movie/{id_}" if t["kind"] == "movie"
                else f"https://nhdapi.com/tv/{id_}/{t['season']}/{t['episode']}")
    h = {"User-Agent": NHD_UA, "Referer": "https://nhdapi.com/"}
    page = sess().get(page_url, headers=h, timeout=TIMEOUT)
    if page.status_code in (403, 503) and "cloudflare" in page.text[:2000].lower():
        return ("FAIL", f"page HTTP {page.status_code} (cloudflare)", 0, None)
    key = re.search(r'var\s+API_KEY\s*=\s*"([^"]+)"', page.text)
    if not key:
        return ("MISS", "no API_KEY in page (service down or shape changed)", 0, None)
    path = re.search(r'var\s+API_PATH\s*=\s*"([^"]+)"', page.text)
    api_path = path.group(1) if path else (f"/api/movie/{id_}" if t["kind"] == "movie" else f"/api/tv/{id_}")
    api = f"https://nhdapi.com{api_path}?key={key.group(1)}"
    r = sess().get(api, headers={"User-Agent": NHD_UA, "Referer": page_url}, timeout=TIMEOUT)
    try:
        root = r.json()
    except Exception:
        return ("FAIL", f"extraction non-JSON ({r.status_code})", 0, None)
    if not root.get("success"):
        return ("MISS", f"extraction success=false: {str(root)[:80]}", 0, None)
    streams = []
    for tr in root.get("audioTracks") or []:
        url = tr.get("url") or ""
        if url.startswith("http"):
            label = tr.get("label") or tr.get("name") or "Audio"
            streams.append((label, url, ".m3u8" in url or root.get("kind") == "hls"))
    if not streams and root.get("playUrl"):
        streams.append(("Default", root["playUrl"], root.get("kind") != "mp4"))
    if not streams:
        return ("MISS", f"no playUrl/audioTracks (keys={list(root)[:6]})", 0, None)
    ok, detail = deep_ok(streams[0][1], streams[0][2], {"User-Agent": NHD_UA})
    langs = sorted({s[0] for s in streams})
    return ("OK" if ok else "DEAD", f"{len(streams)} tracks {langs[:5]}; first: {detail}",
            len(streams), streams)


# ────────────────────────── generic embed harvester ──────────────────────────

STREAM_URL_RE = re.compile(r"https?://[^\s\"'<>\\]+?\.(?:m3u8|mp4)[^\s\"'<>\\]*", re.I)
IFRAME_RE = re.compile(r"""<iframe[^>]+?src=["']([^"']+)["']""", re.I)


def test_embed_generic(base_url, referer, label, unwrap=2):
    """Generic embed-page probe: fetch page → unwrap iframes (up to `unwrap`
    hops) → harvest direct m3u8/mp4 URLs → deep-validate the first. Mirrors
    the generic pipeline a plain (non-isJsonApi) ServerSpec rides."""
    s = sess()
    cur, cur_url = None, base_url
    for hop in range(unwrap + 1):
        try:
            r = s.get(cur_url, headers=ok_headers(referer), timeout=TIMEOUT)
        except Exception as e:
            return ("DOWN", f"{type(e).__name__}: {str(e)[:80]}", 0, None)
        text = r.text or ""
        if hop == 0 and r.status_code in (403, 503):
            return ("FAIL", f"HTTP {r.status_code} ({text[:60].strip()})", 0, None)
        cur = text
        m = IFRAME_RE.search(cur)
        if not m:
            break
        import urllib.parse
        nxt = urllib.parse.urljoin(cur_url, m.group(1))
        if not nxt.startswith("http"):
            break
        cur_url = nxt
    urls = list(dict.fromkeys(STREAM_URL_RE.findall(cur or "")))
    if not urls:
        body = (cur or "")[:80].replace("\n", " ").strip()
        return ("MISS", f"no direct stream urls ({len(cur or '')}B): {body}", 0, None)
    first = urls[0]
    ok, detail = deep_ok(first, ".m3u8" in first.lower(), ok_headers(referer))
    return ("OK" if ok else "DEAD", f"{len(urls)} urls; first: {detail}", len(urls), urls)


def test_vidcore_org(t):
    """vidcore.org — DIFFERENT host from the farm's vidcore.io (no enc-dec
    pipeline; a plain TMDB-keyed embed)."""
    url = (f"https://vidcore.org/embed/movie/{t['tmdb']}" if t["kind"] == "movie"
           else f"https://vidcore.org/embed/tv/{t['tmdb']}/{t['season']}/{t['episode']}")
    return test_embed_generic(url, "https://vidcore.org/", "vidcore.org")


def test_vidphantom(t):
    url = (f"https://vidphantom.com/movie/{t['tmdb']}" if t["kind"] == "movie"
           else f"https://vidphantom.com/tv/{t['tmdb']}/{t['season']}/{t['episode']}")
    return test_embed_generic(url, "https://vidphantom.com/", "vidphantom")


def test_embed_api(t):
    url = (f"https://player.embed-api.stream/?id={t['tmdb']}" if t["kind"] == "movie"
           else f"https://player.embed-api.stream/?id={t['tmdb']}&s={t['season']}&e={t['episode']}")
    return test_embed_generic(url, "https://player.embed-api.stream/", "embed-api")


# ────────────────────────── VidZee (TMDB multi-server JSON) ──────────────────────────

def test_vidzee(t):
    """player.vidzee.wtf/api/server?id={tmdb} — sr=1..10 sub-servers carry
    per-language sources ({link,name,language}). Probes sr=1..4 and harvests
    any http links with their language fields."""
    h = {"User-Agent": UA_CHROME, "Referer": "https://player.vidzee.wtf/"}
    base = (f"https://player.vidzee.wtf/api/server?id={t['tmdb']}" if t["kind"] == "movie"
            else f"https://player.vidzee.wtf/api/server?id={t['tmdb']}&ss={t['season']}&ep={t['episode']}")
    streams = []
    for sr in range(1, 5):
        try:
            r = sess().get(f"{base}&sr={sr}", headers=h, timeout=TIMEOUT)
            if r.status_code != 200:
                continue
            root = r.json()
        except Exception:
            continue
        def walk(node):
            if isinstance(node, dict):
                link = node.get("link") or node.get("url") or node.get("file")
                if isinstance(link, str) and link.startswith("http"):
                    streams.append((node.get("language") or node.get("name") or f"sr{sr}",
                                    link, ".m3u8" in link))
                for v in node.values():
                    walk(v)
            elif isinstance(node, list):
                for v in node:
                    walk(v)
        walk(root)
    if not streams:
        return ("MISS", "no sr sub-server returned links (API likely 404)", 0, None)
    ok, detail = deep_ok(streams[0][1], streams[0][2], {"User-Agent": UA_CHROME, "Referer": "https://core.vidzee.wtf/"})
    langs = sorted({s[0] for s in streams})
    return ("OK" if ok else "DEAD", f"{len(streams)} sources {langs[:5]}; first: {detail}",
            len(streams), streams)


# ────────────────────────── MP4Hydra (title-keyed info2 POST) ──────────────────────────

def test_mp4hydra(t):
    """mp4hydra.org /info2?v=8 — title-slug multipart POST returning
    per-quality HLS sources. Best-effort revival probe: any 200 JSON with
    source URLs counts; a 'Back soon' page is DOWN."""
    h = {"User-Agent": UA_CHROME, "Referer": "https://mp4hydra.org/"}
    try:
        home = sess().get("https://mp4hydra.org/", headers=h, timeout=TIMEOUT).text
    except Exception as e:
        return ("DOWN", f"{type(e).__name__}: {str(e)[:80]}", 0, None)
    if "back soon" in home.lower() or "maintenance" in home.lower():
        return ("DOWN", "maintenance page still up", 0, None)
    slug = re.sub(r"[^a-z0-9]+", "-", t["title"].lower()).strip("-")
    streams = []
    for payload in (
        {"v": "8", "title": t["title"]},
        {"v": "8", "slug": slug},
        {"v": "8", "s": slug},
    ):
        try:
            r = sess().post("https://mp4hydra.org/info2?v=8", headers=h, data=payload, timeout=TIMEOUT)
            if r.status_code != 200:
                continue
            try:
                root = r.json()
            except Exception:
                continue
            def walk(node):
                if isinstance(node, dict):
                    link = node.get("url") or node.get("file") or node.get("link")
                    if isinstance(link, str) and link.startswith("http"):
                        streams.append((str(node.get("title") or node.get("quality") or "src"),
                                        link, ".m3u8" in link))
                    for v in node.values():
                        walk(v)
                elif isinstance(node, list):
                    for v in node:
                        walk(v)
            walk(root)
            if streams:
                break
        except Exception:
            continue
    if not streams:
        return ("MISS", "no /info2 payload variant returned sources", 0, None)
    ok, detail = deep_ok(streams[0][1], streams[0][2], h)
    return ("OK" if ok else "DEAD", f"{len(streams)} sources; first: {detail}", len(streams), streams)


# ────────────────────────── CastleTV (api.hlowb.com, AES-128-CBC app API) ──────────────────────────

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding as _pad


def _cbc_decrypt(ct: bytes, key: bytes, iv: bytes) -> bytes:
    d = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
    p = _pad.PKCS7(algorithms.AES.block_size).unpadder()
    return p.update(d.update(ct) + d.finalize()) + p.finalize()


CASTLE_BASE = "https://api.hlowb.com"
CASTLE_HDRS = {
    "User-Agent": "okhttp/4.9.3", "Accept": "application/json",
    "Accept-Language": "en-US,en;q=0.9", "Connection": "Keep-Alive",
    "Referer": CASTLE_BASE,
}


def _castle_key(security_key_b64: str) -> bytes:
    kb = base64.b64decode(security_key_b64) + b"T!BgJB"
    return (kb[:16].ljust(16, b"\x00"))


def _castle_get_json(s, url, sec_key):
    r = s.get(url, headers=CASTLE_HDRS, timeout=TIMEOUT)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    text = r.text.strip()
    try:
        j = json.loads(text)
        cipher = j["data"].strip() if isinstance(j.get("data"), str) else text
    except Exception:
        cipher = text
    plain = _cbc_decrypt(base64.b64decode(cipher), _castle_key(sec_key),
                         _castle_key(sec_key)).decode("utf-8", "replace")
    # bigint ids (16+ digits) would lose precision as JS numbers — quote them
    return json.loads(re.sub(r"([:{[,]\s*)(\d{16,})", r'\1"\2"', plain))


def test_castletv(t):
    """Mirrors the TMDB-Embed-API castletv port: getSecurityKey → title search
    (AES-128-CBC) → movie detail → episode tracks[] (per-language!) → getVideo2.
    Title-keyed like the Kotlin farm's MovieBox (no castle-side TMDB lookup),
    so the probe searches by `title year`."""
    s = sess()
    try:
        r = s.get(f"{CASTLE_BASE}/v0.1/system/getSecurityKey/1?channel=IndiaA"
                  f"&clientType=1&lang=en-US", headers=CASTLE_HDRS, timeout=TIMEOUT)
        sec = r.json()["data"] if r.status_code == 200 else None
    except Exception:
        sec = None
    if not sec:
        return ("FAIL", f"getSecurityKey HTTP {r.status_code}", 0, None)
    kw = f"{t['title']} {t['year']}"
    sr = _castle_get_json(s, f"{CASTLE_BASE}/film-api/v1.1.0/movie/searchByKeyword"
                          f"?channel=IndiaA&clientType=1&keyword={requests.utils.quote(kw)}"
                          "&lang=en-US&mode=1&packageName=com.external.castle&page=1&size=30", sec)
    rows = (sr.get("data") or sr).get("rows") or []
    title_lc = t["title"].lower()
    match = next((x for x in rows
                  if title_lc in (x.get("title") or x.get("name") or "").lower()), None)
    if not match:
        return ("MISS", f"no search rows for '{kw}' (rows={len(rows)})", 0, None)
    mid = str(match.get("id") or match.get("redirectId") or match.get("redirectIdStr") or "")
    det = _castle_get_json(s, f"{CASTLE_BASE}/film-api/v1.9.9/movie?channel=IndiaA"
                           f"&clientType=1&lang=en-US&movieId={mid}"
                           "&packageName=com.external.castle", sec)
    dd = det.get("data") if isinstance(det.get("data"), dict) else det
    eps = dd.get("episodes") or []
    if t["kind"] == "tv":
        seasons = dd.get("seasons") or []
        se = next((x for x in seasons if x.get("number") == t["season"]
                   and x.get("movieId")), None)
        if se and str(se["movieId"]) != mid:
            mid = str(se["movieId"])
            det = _castle_get_json(s, f"{CASTLE_BASE}/film-api/v1.9.9/movie?channel=IndiaA"
                                   f"&clientType=1&lang=en-US&movieId={mid}"
                                   "&packageName=com.external.castle", sec)
            dd = det.get("data") if isinstance(det.get("data"), dict) else det
            eps = dd.get("episodes") or []
    ep = (next((e for e in eps if e.get("number") == t["episode"]), None)
          if t["kind"] == "tv" else (eps[0] if eps else None))
    if not ep:
        return ("MISS", f"no episode S{t['season']}E{t['episode']}", 0, None)
    tracks = [tr for tr in (ep.get("tracks") or []) if tr.get("existIndividualVideo")] \
        or (ep.get("tracks") or [])
    body = {"mode": "1", "appMarket": "GuanWang", "clientType": "1",
            "woolUser": "false",
            "apkSignKey": "ED0955EB04E67A1D9F3305B95454FED485261475",
            "androidVersion": "13", "movieId": mid, "episodeId": str(ep["id"]),
            "isNewUser": "true", "resolution": "3",
            "packageName": "com.external.castle"}
    streams = []
    for tr in (tracks or [None]):
        b = dict(body)
        if tr:
            b["languageId"] = str(tr.get("languageId"))
        h = dict(CASTLE_HDRS)
        h["Content-Type"] = "application/json"
        try:
            rr = s.post(f"{CASTLE_BASE}/film-api/v2.0.1/movie/getVideo2?clientType=1"
                        "&packageName=com.external.castle&channel=IndiaA&lang=en-US",
                        headers=h, data=json.dumps(b), timeout=TIMEOUT)
            text = rr.text.strip()
            try:
                j = json.loads(text)
                ct = j["data"].strip() if isinstance(j.get("data"), str) else text
            except Exception:
                ct = text
            v = _castle_get_json_post(ct, sec)
        except Exception:
            continue
        vd = v.get("data") if isinstance(v.get("data"), dict) else v
        label = (tr.get("languageName") or tr.get("abbreviate") or "Shared") if tr else "Shared"
        for x in (vd.get("videos") or []):
            u = x.get("url") or vd.get("videoUrl")
            if u and u.startswith("http"):
                streams.append((label, u, ".m3u8" in u))
        if not (vd.get("videos") or []) and vd.get("videoUrl", "").startswith("http"):
            streams.append((label, vd["videoUrl"], ".m3u8" in vd["videoUrl"]))
    if not streams:
        return ("MISS", f"no getVideo2 streams (tracks={len(tracks)})", 0, None)
    ok, detail = deep_ok(streams[0][1], streams[0][2], {"User-Agent": UA_CHROME})
    langs = sorted({x[0] for x in streams})
    return ("OK" if ok else "DEAD", f"{len(streams)} streams {langs[:8]}; first: {detail}",
            len(streams), streams)


def _castle_get_json_post(cipher_b64, sec_key):
    plain = _cbc_decrypt(base64.b64decode(cipher_b64), _castle_key(sec_key),
                         _castle_key(sec_key)).decode("utf-8", "replace")
    return json.loads(re.sub(r"([:{[,]\s*)(\d{16,})", r'\1"\2"', plain))


# ────────────────────────── OneTouchTV (api3.devcorp.me, AES-256-CBC) ──────────────────────────

OTT_KEY = b"im72charPasswordofdInitVectorStm"
OTT_IV = b"im72charPassword"
OTT_HDRS = {"User-Agent": UA_CHROME, "Referer": "https://onetouchtv.xyz/"}


def _ott_fetch(s, path):
    r = s.get(f"https://api3.devcorp.me{path}", headers=OTT_HDRS, timeout=TIMEOUT)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    txt = re.sub(r"\s+", "", (r.text or "").strip())
    txt = txt.replace("-_.", "/").replace("@", "+")
    txt += "=" * (-len(txt) % 4)
    plain = _cbc_decrypt(base64.b64decode(txt), OTT_KEY, OTT_IV).decode("utf-8", "replace")
    return (json.loads(plain) or {}).get("result")


def test_onetouchtv(t):
    """Mirrors the TMDB-Embed-API onetouchtv port: /vod/search?keyword → best
    title match → /vod/{id}/detail → episode (number match, season in title) →
    /vod/{id}/episode/{playId} → sources[] ({name,quality,url}). src.name
    carries the language when the host serves per-language entries."""
    s = sess()
    try:
        results = _ott_fetch(s, f"/vod/search?keyword={requests.utils.quote(t['title'])}") or []
    except Exception as e:
        return ("FAIL", f"search: {type(e).__name__}: {str(e)[:60]}", 0, None)
    tl = t["title"].lower()
    want = "movie" if t["kind"] == "movie" else None
    cands = [r_ for r_ in results if tl in (r_.get("title") or "").lower()]
    if want:
        cands = [r_ for r_ in cands if (r_.get("type") or "").lower() == want] or cands
    if t["kind"] == "tv":
        s1 = [r_ for r_ in cands
              if int(r_.get("year") or 0) == t["year"]
              and f"season {t['season']}" not in (r_.get("title") or "").lower()]
        cands = s1 or cands
    match = next((r_ for r_ in cands if str(r_.get("year")) == str(t["year"])), None) \
        or (cands[0] if cands else None)
    if not match:
        return ("MISS", f"no search match for '{t['title']}' (hits={len(results)})", 0, None)
    try:
        detail = _ott_fetch(s, f"/vod/{match['id']}/detail") or {}
    except Exception as e:
        return ("FAIL", f"detail: {type(e).__name__}", 0, None)
    eps = detail.get("episodes") or []
    if not eps:
        return ("MISS", f"detail has no episodes ({match['title']!r})", 0, None)
    tgt = (eps[0] if t["kind"] == "movie"
           else next((e for e in eps if int(e.get("episode") or -1) == t["episode"]), None))
    if not tgt:
        return ("MISS", f"no ep{t['episode']} in {len(eps)} eps", 0, None)
    try:
        ep = _ott_fetch(s, f"/vod/{match['id']}/episode/{tgt.get('playId')}") or {}
    except Exception as e:
        return ("FAIL", f"episode: {type(e).__name__}", 0, None)
    streams = []
    for src in ep.get("sources") or []:
        u = src.get("url")
        if u and u.startswith("http"):
            streams.append((src.get("name") or src.get("quality") or "src",
                            u, ".m3u8" in u))
    if not streams:
        return ("MISS", f"no sources for {match['title']!r} ep={tgt.get('name')}", 0, None)
    ok, detail_s = deep_ok(streams[0][1], streams[0][2],
                           {"User-Agent": UA_CHROME, "Referer": "https://api3.devcorp.me/"})
    names = sorted({x[0] for x in streams})
    return ("OK" if ok else "DEAD",
            f"{len(streams)} sources {names[:8]}; first: {detail_s}", len(streams), streams)


# ────────────────────────── StreamFlix (api.streamflix.app data.json → MP4 CDNs) ──────────────────────────

def test_streamflix(t):
    """TMDB-keyed catalogue: /data.json items carry {tmdb, movielink}; mirrors
    come from config-streamflixapp.json download[]. Direct MP4 files (dual-audio
    when the upload is a Hindi dub)."""
    s = sess()
    try:
        items = s.get("https://api.streamflix.app/data.json",
                      headers={"User-Agent": UA_CHROME}, timeout=20).json()["data"]
        bases = list(dict.fromkeys(s.get(
            "https://api.streamflix.app/config/config-streamflixapp.json",
            headers={"User-Agent": UA_CHROME}, timeout=10).json().get("download") or []))
    except Exception as e:
        return ("FAIL", f"catalogue: {type(e).__name__}: {str(e)[:60]}", 0, None)
    match = next((x for x in items if x.get("tmdb") == str(t["tmdb"])), None)
    if not match:
        return ("MISS", f"tmdb {t['tmdb']} not in catalogue ({len(items)} items)", 0, None)
    if t["kind"] == "tv":
        import requests as rq
        fb = (f"https://chilflix-410be-default-rtdb.asia-southeast1.firebasedatabase.app"
              f"/Data/{match.get('moviekey')}/seasons/{t['season']}/episodes.json")
        try:
            eps = rq.get(fb, timeout=15).json() or {}
        except Exception:
            eps = {}
        ep = eps.get(str(t["episode"] - 1)) or eps.get(str(t["episode"])) or \
            next((v for v in eps.values() if isinstance(v, dict)), None)
        link = (ep or {}).get("link")
    else:
        link = match.get("movielink")
    if not link:
        return ("MISS", "no movielink/episode link", 0, None)
    streams = [(f"sf{i}", bases[i] + link, False) for i in range(len(bases))
               if bases[i].startswith("http")]
    if not streams:
        return ("MISS", "no download CDN bases", 0, None)
    ok, detail = deep_ok(streams[0][1], False, {"User-Agent": UA_CHROME})
    return ("OK" if ok else "DEAD", f"{len(streams)} mirrors; first: {detail}",
            len(streams), streams)


# ────────────────────────── NetMirror (net27.cc embed-tmdb + NewTV mirrors) ──────────────────────────

NM_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
         "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")


def test_netmirror(t):
    """Two paths (mirrors the netmirror port): 1) Netflix direct JSON API
    net27.cc/api/embed-tmdb/{tmdb}[?type=tv&se=&ep=] → streams[]/mp4/captions;
    2) NewTV OTT mirror discovery: rotated mobiledetect* domains → /checknewtv.php
    → api base → search.php → post.php → player.php → video_link."""
    s = sess()
    base_url = f"https://net27.cc/api/embed-tmdb/{t['tmdb']}"
    if t["kind"] == "tv":
        base_url += f"?type=tv&se={t['season']}&ep={t['episode']}"
    try:
        r = s.get(base_url, headers={"Accept": "application/json", "Referer": "https://net27.cc/",
                                    "User-Agent": NM_UA}, timeout=20)
        data = r.json() if r.status_code == 200 else {}
    except Exception:
        data = {}
    streams = []
    if data.get("ok") is True:
        for st in data.get("streams") or []:
            if st.get("url"):
                streams.append((f"Netflix {st.get('resolution') or ''}p".strip(),
                                st["url"], ".m3u8" in st["url"]))
        if not streams and data.get("mp4"):
            streams.append(("Netflix mp4", data["mp4"], False))
    # NewTV path (hotstar/prime capture): domain rotation → token_hash api
    if not streams:
        api = None
        for dom in ["https://mobiledetects.com", "https://mobidetect.art",
                    "https://mobidetect.cc", "https://mobidetect.vip"]:
            try:
                rr = s.get(dom + "/checknewtv.php", headers={
                    "X-Requested-With": "NetmirrorNewTV v1.0", "Ott": "nf",
                    "User-Agent": NM_UA, "Accept": "application/json"}, timeout=10)
                th = rr.json().get("token_hash")
                if th:
                    import base64 as b64m
                    api = b64m.b64decode(th).decode().rstrip("/")
                    break
            except Exception:
                continue
        if api:
            nh = {"X-Requested-With": "NetmirrorNewTV v1.0", "Ott": "hs",
                  "User-Agent": NM_UA, "Accept": "application/json"}
            try:
                sr = s.get(f"{api}/newtv/search.php?s={requests.utils.quote(t['title'])}",
                           headers=nh, timeout=15).json()
                first = (sr.get("searchResult") or [None])[0]
                if first and first.get("id"):
                    pid = first["id"]
                    if t["kind"] == "tv":
                        pr = s.get(f"{api}/newtv/post.php?id={pid}",
                                   headers={**nh, "Lastep": "", "Usertoken": ""},
                                   timeout=15).json()
                        eps = pr.get("episodes") or []
                        tmap = {int(e["ep"]): e["id"] for e in eps
                                if isinstance(e, dict) and str(e.get("ep") or "").isdigit()}
                        pid = tmap.get(t["episode"], pid)
                    pl = s.get(f"{api}/newtv/player.php?id={pid}",
                               headers={**nh, "Usertoken": ""}, timeout=15).json()
                    if pl.get("video_link"):
                        streams.append(("NewTV", pl["video_link"],
                                        ".m3u8" in pl["video_link"]))
            except Exception:
                pass
    if not streams:
        return ("MISS", f"no netflix streams / no newtv link (net27={data.get('ok')})",
                0, None)
    hdrs = {"User-Agent": NM_UA, "Referer": "https://videodownloader.site/"} \
        if streams[0][0].startswith("Netflix") else {"User-Agent": NM_UA, "Referer": api + "/"}
    ok, detail = deep_ok(streams[0][1], streams[0][2], hdrs)
    return ("OK" if ok else "DEAD", f"{len(streams)} streams; first: {detail}",
            len(streams), streams)


# ────────────────────────── runner ──────────────────────────

# (name, fn, alt-title-per-kind overrides)
SERVERS = [
    ("VidLink", test_vidlink, None),
    ("VaPlayer", test_vaplayer, None),
    ("VidRock", test_vidrock, None),
    ("Videasy Hindi", test_videasy_hindi, None),
    # MovieBox's search library doesn't carry Game of Thrones (fuzzy rows only),
    # so the TV suite probes it with an in-library show instead.
    ("MovieBox", test_moviebox, {"tv": TITLE_TV_MB}),
    ("8Stream", test_8stream, None),
    ("VidUp", test_vidup, None),
    ("VidCore", test_vidcore, None),
    ("VidNest", test_vidnest, {"tv": TITLE_TV_MB}),
    ("Allmovieland", test_allmovieland, {"tv": TITLE_TV_MB}),
    # ── 2026-09-11 multi-language expansion candidates ──
    ("NHD", test_nhd, None),
    ("VidCoreOrg", test_vidcore_org, None),
    ("VidPhantom", test_vidphantom, None),
    ("EmbedAPI", test_embed_api, None),
    ("VidZee", test_vidzee, None),
    ("MP4Hydra", test_mp4hydra, None),
    # ── 2026-09-11 round-2 candidates (TMDB-Embed-API v1.3.0 ports). Result:
    #    NETMIRROR ADDED to the farm (playable 6/6, 360→1080p + captions);
    #    CastleTV = preview-clip-only on the free tier (permissionDenied),
    #    OneTouchTV = single UNLABELLED muxed "loklok" playlist (nothing to
    #    label per-title), StreamFlix = catalogue ok but every mirror dead.
    #    HDGharTV deliberately excluded — user-confirmed dead.
    ("CastleTV", test_castletv, {"tv": TITLE_TV_MB}),
    ("OneTouchTV", test_onetouchtv, {"tv": TITLE_TV_MB}),
    ("StreamFlix", test_streamflix, None),
    ("NetMirror", test_netmirror, None),
]

# Title matrix: baseline movie + TV, plus the Indian-dub set. MovieBox keeps
# its in-library TV alternative; every other suite probes every title.
TITLE_MATRIX = [TITLE_MOVIE, TITLE_TE, TITLE_TA, TITLE_TV, TITLE_TV_FM]

# Servers whose API/library is known to answer only for a subset of the
# matrix (same idea as the MovieBox tv override): title-alternatives per kind.
TITLE_ALTS = {
    "MovieBox": {"tv": TITLE_TV_MB},
    "VidNest": {"tv": TITLE_TV_MB},
    "Allmovieland": {"tv": TITLE_TV_MB},
}


def run_one(name, fn, alts, t):
    if alts and t["kind"] in alts:
        t = alts[t["kind"]]
    t0 = time.time()
    try:
        status, detail, n, _ = fn(t)
    except Exception as e:
        status, detail, n = "DOWN", f"{type(e).__name__}: {str(e)[:100]}", 0
    return (name, t["title"] if t["kind"] == "movie" else t["title"], t["kind"], status, time.time() - t0, n, detail)


def main():
    which = sys.argv[1:] or ["movie", "tv"]
    titles = []
    if "movie" in which:
        titles += [t for t in TITLE_MATRIX if t["kind"] == "movie"]
    if "tv" in which:
        titles += [t for t in TITLE_MATRIX if t["kind"] == "tv"]
    jobs = [(name, fn, alts, t) for t in titles for (name, fn, alts) in SERVERS]
    results = []
    with futures.ThreadPoolExecutor(max_workers=12) as ex:
        futs = [ex.submit(run_one, n, f, a, t) for (n, f, a, t) in jobs]
        for f in futures.as_completed(futs):
            results.append(f.result())

    for t in titles:
        print(f"\n=== {t['title']} ({t['kind']}"
              + (f" S{t['season']}E{t['episode']}" if t['kind'] == 'tv' else "") + ") ===")
        print(f"{'SERVER':<15} {'STATUS':<9} {'TIME':>6} {'#':>3}  DETAIL")
        print("-" * 110)
        for name, title, kind, status, secs, n, detail in sorted(
                [r for r in results if r[2] == t["kind"] and r[1] == t["title"]], key=lambda r: r[0]):
            print(f"{name:<15} {status:<9} {secs:5.1f}s {n:>3}  {detail}")

        ok = [r[0] for r in results if r[2] == t["kind"] and r[1] == t["title"] and r[3] == "OK"]
        print(f"\nWORKING: {len(ok)}/{len(SERVERS)} -> {', '.join(ok) if ok else 'NONE'}")


if __name__ == "__main__":
    main()
