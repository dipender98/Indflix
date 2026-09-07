#!/usr/bin/env python3
"""
mm_probe.py — live health check for Multimovies' id-keyed GLOBAL sources
(the ones the fast-start path now launches FIRST, before the dooplayer
embed pulls).

    python tools/mm_probe.py            # movie + tv suites

Cineverse/NHD are dooplayer paths (need the Cloudflare-solved site) so they
are not probed here — check them from app logs.

Replicates each source's wire protocol from ExternalSources.kt:
  - Nxsha      CryptoJS-AES (OpenSSL Salted__, EVP_BytesToKey-MD5) envelopes
               on nxsha.space /api/servers + /api/sources
  - 111Movies  api.shows.st JSON (source.url = adaptive HLS master)
  - VidEm      videm.xyz signed Q token -> api.php play -> /_stream HLS
  - 2embed/VidSrc(vsembed)  embed page -> iframe unwrap -> m3u8 sniff
"""

import base64
import concurrent.futures as futures
import hashlib
import json
import re
import secrets
import string
import sys
import time

import requests
import urllib3
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.padding import PKCS7

urllib3.disable_warnings()

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
TIMEOUT = 12
B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

TITLE_MOVIE = dict(kind="movie", tmdb="27205", imdb="tt1375666", season=None, episode=None)
TITLE_TV = dict(kind="tv", tmdb="1399", imdb="tt0944947", season=1, episode=1)


def sess():
    s = requests.Session()
    s.verify = False
    return s


# ────────────── CryptoJS AES (OpenSSL Salted__, EVP_BytesToKey MD5) ──────────────

def _evp_kdf(password: bytes, salt: bytes, key_len=32, iv_len=16):
    out, block = b"", b""
    while len(out) < key_len + iv_len:
        block = hashlib.md5(block + password + salt).digest()
        out += block
    return out[:key_len], out[key_len:key_len + iv_len]


def cryptojs_decrypt(b64str: str, passphrase: str):
    # Kotlin decodeData() converts base64URL -> standard BEFORE the lenient
    # decode; filtering without converting mangles url-safe payloads.
    std = b64str.replace("-", "+").replace("_", "/")
    valid = "".join(c for c in std if c in B64)
    raw = base64.b64decode(valid + "=" * ((4 - len(valid) % 4) % 4))
    if raw[:8] == b"Salted__":
        salt, ct = raw[8:16], raw[16:]
    else:
        salt, ct = b"", raw
    key, iv = _evp_kdf(passphrase.encode(), salt)
    dec = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
    padded = dec.update(ct) + dec.finalize()
    unp = PKCS7(128).unpadder()
    return (unp.update(padded) + unp.finalize()).decode("utf-8", "replace")


def cryptojs_encrypt(plain: str, passphrase: str):
    salt = secrets.token_bytes(8)
    key, iv = _evp_kdf(passphrase.encode(), salt)
    padder = PKCS7(128).padder()
    padded = padder.update(plain.encode()) + padder.finalize()
    enc = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
    return base64.b64encode(b"Salted__" + salt + enc.update(padded) + enc.finalize()).decode()


NXSHA_PASSPHRASE = "S8x!Jk4ZP1uG8$my"  # matches Kotlin "S8x!Jk4ZP1uG8\$my"


def nxsha_api(endpoint, payload):
    body = dict(payload)
    body["_req_ts"] = int(time.time() * 1000)
    body["_req_salt"] = "".join(secrets.choice(string.ascii_lowercase + string.digits) for _ in range(10))
    q = cryptojs_encrypt(json.dumps(body), NXSHA_PASSPHRASE)
    q = q.replace("+", "-").replace("/", "_").replace("=", "")
    r = sess().get(f"{endpoint}?q={q}", headers={"User-Agent": UA, "Accept": "*/*"}, timeout=TIMEOUT)
    env = r.json()
    return json.loads(cryptojs_decrypt(env["_hash"], NXSHA_PASSPHRASE))


# ────────────── deep validation (same semantics as server_probe.py) ──────────────

def check_m3u8(url, headers):
    try:
        r = sess().get(url, headers=headers, timeout=TIMEOUT, stream=True)
        text = r.raw.read(65536, decode_content=True).decode("utf-8", "replace")
    except Exception:
        return (False, 0, False)
    if not text.lstrip().startswith("#EXTM3U"):
        return (False, 0, False)
    variants = len(re.findall(r"#EXT-X-STREAM-INF", text))
    hindi = bool(re.search(r'LANGUAGE="hi["-]|Hindi', text, re.I))
    return (True, variants, hindi)


def deep(url, is_hls, headers):
    if is_hls:
        ok, variants, hindi = check_m3u8(url, headers)
        return ok, f"playable {variants} variants" + (" [HINDI]" if hindi else "")
    h = dict(headers)
    h["Range"] = "bytes=0-2047"
    try:
        r = sess().get(url, headers=h, timeout=TIMEOUT, stream=True)
        return r.status_code in (200, 206), f"HTTP {r.status_code}"
    except Exception:
        return False, "DEAD"


def sniff(text):
    norm = text.replace("\\/", "/").replace("\\\"", "\"")
    for pat in (r"https?://[^\s\"'<>\\]+\.m3u8[^\s\"'<>\\]*",
                r"https?://[^\s\"'<>\\]+\.mp4[^\s\"'<>\\]*"):
        m = re.search(pat, norm)
        if m:
            return m.group(0).strip("\"'")
    return None


# ────────────── sources ──────────────

def src_nxsha(t):
    base = "https://nxsha.space"
    servers_json = nxsha_api(f"{base}/api/servers", {
        "tmdbId": t["tmdb"], "imdb_id": t["imdb"], "type": t["kind"],
        "season": t["season"], "episode": t["episode"]})
    raw = servers_json.get("servers") or []
    servers = [s for s in raw if isinstance(s, dict) and s.get("web_support", True)
               and not s.get("isDisable", False)]
    if not servers:
        return ("MISS", f"no servers (keys={list(servers_json)[:5]})", 0, "")
    servers.sort(key=lambda s: (0 if "nitro" in (str(s.get("name", "")) + str(s.get("scraper", ""))).lower()
                                else 1, s.get("high_priority", 9) or 9, s.get("position", 9) or 9))
    total, hindi_n, first_url = 0, 0, ""
    for srv in servers[:4]:
        try:
            sj = nxsha_api(f"{base}/api/sources", {
                "ex_lang": False, "provider": srv.get("scraper", ""),
                "tmdbId": t["tmdb"], "imdb_id": t["imdb"], "type": t["kind"],
                "season": t["season"], "episode": t["episode"]})
        except Exception:
            continue
        for s in sj.get("sources") or []:
            if not isinstance(s, dict) or not str(s.get("url", "")).startswith("http"):
                continue
            total += 1
            if "hindi" in str(s.get("quality", "")).lower():
                hindi_n += 1
            if not first_url and not s.get("isEmbed"):
                first_url = s["url"]
    if total == 0:
        return ("MISS", f"{len(servers)} servers, 0 sources", 0, "")
    ok, detail = deep(first_url, ".m3u8" in first_url, {"User-Agent": UA, "Referer": f"{base}/"})
    return ("OK" if ok else "DEAD",
            f"{len(servers)} servers, {total} sources ({hindi_n} Hindi); first: {detail}",
            total, first_url)


def src_111movies(t):
    if t["kind"] == "movie":
        api = f"https://api.shows.st/movie?id={t['imdb']}&mode=json"
    else:
        # 111Movies TV is TMDB-keyed
        api = f"https://api.shows.st/tv?id={t['tmdb']}&season={t['season']}&episode={t['episode']}&mode=json"
    r = sess().get(api, headers={"User-Agent": UA, "Referer": "https://player.vidlove.cc/"}, timeout=TIMEOUT)
    root = r.json()
    src = root.get("source") or {}
    url = src.get("url") or ""
    if not url:
        return ("MISS", f"no source.url (root keys={list(root)[:5]})", 0, "")
    ok, detail = deep(url, True, {"User-Agent": UA, "Referer": "https://player.vidlove.cc/"})
    return ("OK" if ok else "DEAD", f"master: {detail}", 1, url)


def src_videm(t):
    if t["kind"] == "movie":
        page_url = f"https://videm.xyz/embed/movie/{t['imdb']}"
    else:
        page_url = f"https://videm.xyz/embed/tv/{t['imdb']}/{t['season']}/{t['episode']}"
    text = sess().get(page_url, headers={"User-Agent": UA, "Referer": "https://videm.xyz/"}, timeout=TIMEOUT).text
    q_start = text.find("Q = {")
    if q_start < 0:
        return ("FAIL", "no Q object on page", 0, "")
    depth, i, in_str, esc = 0, q_start + 4, False, False
    for i in range(q_start + 4, len(text)):
        c = text[i]
        if esc:
            esc = False
            continue
        if in_str:
            if c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                break
    q_json = text[q_start + 4:i + 1]
    q = json.loads(q_json)
    token = q.get("t") or ""
    if not token:
        return ("FAIL", "Q has no token", 0, "")
    servers = ((q.get("ssr") or {}).get("servers")) or []
    streams = []
    for sv in servers:
        ref = sv.get("ref") or ""
        if not ref:
            continue
        import urllib.parse
        r = sess().get(f"https://videm.xyz/api.php?a=play&ref={urllib.parse.quote(ref)}&t={urllib.parse.quote(token)}",
                       headers={"User-Agent": UA, "Referer": "https://videm.xyz/"}, timeout=TIMEOUT).json()
        p = r.get("url") or ""
        if p:
            streams.append("https://videm.xyz" + p if p.startswith("/") else p)
    if not streams:
        return ("MISS", f"{len(servers)} refs, no play urls", 0, "")
    ok, detail = deep(streams[0], True, {"User-Agent": UA})
    return ("OK" if ok else "DEAD", f"{len(streams)} streams; first: {detail}", len(streams), streams[0])


def _embed_sniff(name, page_url, referer):
    r = sess().get(page_url, headers={"User-Agent": UA, "Referer": referer}, timeout=TIMEOUT)
    if r.status_code != 200:
        return ("FAIL", f"page HTTP {r.status_code}", 0, "")
    direct = sniff(r.text)
    if direct:
        ok, detail = deep(direct, ".m3u8" in direct, {"User-Agent": UA, "Referer": page_url})
        return ("OK" if ok else "DEAD", f"direct sniff: {detail}", 1, direct)
    m = re.search(r'<iframe[^>]+src="([^"]+)"', r.text)
    if not m:
        return ("MISS", "no stream, no iframe (JS-rendered?)", 0, "")
    inner = m.group(1)
    if inner.startswith("//"):
        inner = "https:" + inner
    r2 = sess().get(inner, headers={"User-Agent": UA, "Referer": page_url}, timeout=TIMEOUT)
    inner_stream = sniff(r2.text)
    if inner_stream:
        ok, detail = deep(inner_stream, ".m3u8" in inner_stream, {"User-Agent": UA, "Referer": inner})
        return ("OK" if ok else "DEAD", f"via iframe: {detail}", 1, inner_stream)
    return ("APP-ONLY", f"iframe {inner[:60]}… — CloudStream registry extracts it (no plain m3u8)", 0, "")


def src_2embed(t):
    page = (f"https://www.2embed.cc/embed/movie?imdb={t['imdb']}" if t["kind"] == "movie"
            else f"https://www.2embed.cc/embed/tv?imdb={t['imdb']}&s={t['season']}&e={t['episode']}")
    return _embed_sniff("2embed", page, "https://www.2embed.cc/")


def src_vidsrc(t):
    page = (f"https://vsembed.ru/embed/{t['imdb']}" if t["kind"] == "movie"
            else f"https://vsembed.ru/embed/{t['imdb']}/{t['season']}-{t['episode']}")
    return _embed_sniff("VidSrc", page, "https://vsembed.ru/")


SERVERS = [
    ("Nxsha", src_nxsha),
    ("111Movies", src_111movies),
    ("VidEm", src_videm),
    ("2embed", src_2embed),
    ("VidSrc", src_vidsrc),
]


def run_one(name, fn, t):
    t0 = time.time()
    try:
        status, detail, n, _ = fn(t)
    except Exception as e:
        status, detail, n = "DOWN", f"{type(e).__name__}: {str(e)[:100]}", 0
    return (name, t["kind"], status, time.time() - t0, n, detail)


def main():
    which = sys.argv[1:] or ["movie", "tv"]
    titles = [TITLE_MOVIE] * ("movie" in which) + [TITLE_TV] * ("tv" in which)
    jobs = [(n, f, t) for t in titles for (n, f) in SERVERS]
    results = []
    with futures.ThreadPoolExecutor(max_workers=8) as ex:
        futs = [ex.submit(run_one, n, f, t) for (n, f, t) in jobs]
        for f in futures.as_completed(futs):
            results.append(f.result())
    for t in titles:
        label = f"{t['kind']}" + (f" S{t['season']}E{t['episode']}" if t["season"] else "")
        print(f"\n=== Multimovies globals — tmdb={t['tmdb']} ({label}) ===")
        print(f"{'SOURCE':<12} {'STATUS':<9} {'TIME':>6} {'#':>3}  DETAIL")
        print("-" * 105)
        for name, kind, status, secs, n, detail in sorted(
                [r for r in results if r[1] == t["kind"]], key=lambda r: r[0]):
            print(f"{name:<12} {status:<9} {secs:5.1f}s {n:>3}  {detail}")
        ok = [r[0] for r in results if r[1] == t["kind"] and r[2] == "OK"]
        print(f"\nWORKING: {len(ok)}/{len(SERVERS)} -> {', '.join(ok) if ok else 'NONE'}")
        print("(Cineverse/NHD are dooplayer paths — Cloudflare-solved in-app, not probeable here)")


if __name__ == "__main__":
    main()
