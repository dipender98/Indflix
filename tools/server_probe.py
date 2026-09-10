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
]


def run_one(name, fn, alts, t):
    if alts and t["kind"] in alts:
        t = alts[t["kind"]]
    t0 = time.time()
    try:
        status, detail, n, _ = fn(t)
    except Exception as e:
        status, detail, n = "DOWN", f"{type(e).__name__}: {str(e)[:100]}", 0
    return (name, t["kind"], status, time.time() - t0, n, detail)


def main():
    which = sys.argv[1:] or ["movie", "tv"]
    titles = [TITLE_MOVIE] * ("movie" in which) + [TITLE_TV] * ("tv" in which)
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
        for name, kind, status, secs, n, detail in sorted(
                [r for r in results if r[1] == t["kind"]], key=lambda r: r[0]):
            print(f"{name:<15} {status:<9} {secs:5.1f}s {n:>3}  {detail}")

        ok = [r[0] for r in results if r[1] == t["kind"] and r[2] == "OK"]
        print(f"\nWORKING: {len(ok)}/{len(SERVERS)} -> {', '.join(ok) if ok else 'NONE'}")


if __name__ == "__main__":
    main()
