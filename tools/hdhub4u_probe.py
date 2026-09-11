#!/usr/bin/env python3
"""
hdhub4u_probe.py — live contract checker for the HDHub4u CloudStream plugin.

Re-validates every wire shape the Kotlin provider (com.hdhub4u) depends on,
so a site rotation / host rename is caught here BEFORE it shows up as "no
links" in the app. Run with no args for the full sweep, or point at one post:

    python tools/hdhub4u_probe.py
    python tools/hdhub4u_probe.py --recheck https://newN.hdhub4u.<tld>/<slug>/
    python tools/hdhub4u_probe.py --live-url            # just resolve domain

VERIFIED CONTRACTS (2026-09-11):
  * live domain: base64 JSON {h,c} off the host APIs below (Referer not needed);
    `c` = current live URL like https://new5.hdhub4u.cl/?utm=mn1
  * card grid: `li.thumb` -> figure img[src] + figcaption a p  (home, category,
    and search-shell all reuse it; live WP `?s=` is DEAD, it echoes the home page)
  * search: site's own typesense proxy https://search.pingora.fyi/collections/
    post/documents/search  (REQUIRES a live-site Referer else CF 403). Fields:
    post_title, post_thumbnail, permalink, imdb_id, category[], post_date.
  * category list: /category/<slug>/page/N/
  * series: single post, per-episode `<h4>EPISODE n</h4>` groups of Drive/
    Instant/WATCH anchors (season packs sit before the first EP heading).
  * link hosts on a post (all appear as <h[n]> or <td><a> with a labelled text):
      hubdrive.tips       Drive indexer  -> POST /ajax.php?ajax=direct-download {id}
      hdstream4u.com      watch (HLS)    -> packed script links={"hls2":"..m3u8.."}
      hubcdn.sbs          instant (.mkv) -> script var reurl ?r=b64 -> dl/?link=URL
      greenmountmotors    ad redirector  -> unresolvable server-side, SKIP
      catimages.org/image.tmdb/imdb.com  -> posters/meta, not a link
"""

import argparse
import base64
import io
import json
import re
import sys
import time

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import requests
import urllib3

urllib3.disable_warnings()

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
TIMEOUT = 15
B64CHUNK = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

HOST_APIS = [
    "https://h4.suncdn.org/host",
    "https://dns.pingora.fyi/v2/host",
    "https://points.topapi.com/host",
    "https://ml.theapi.org/host",
    "https://cdn.hub4u.cloud/host",
]
TYPESENSE = "https://search.pingora.fyi/collections/post/documents/search"
SKIP_HOSTS = re.compile(
    r"(?:^|\.)(?:catimages\.org|image\.tmdb\.org|imdb\.com|whatsapp\.com|wa\.me|t\.me)$")


def sess():
    s = requests.Session()
    s.verify = False
    s.headers["User-Agent"] = UA
    return s


def b64(s):
    s = re.sub(r"[^" + B64CHUNK + r"]", "", s.replace("-", "+").replace("_", "/"))
    s += "=" * (-len(s) % 4)
    return base64.b64decode(s)


def hour_stamp():
    t = time.gmtime()
    return 1_000_000 * t.tm_year + 10_000 * t.tm_mon + 100 * t.tm_mday + t.tm_hour


def resolve_live(s):
    for api in HOST_APIS:
        try:
            r = s.get(api + "?v=%d" % (hour_stamp() % 100 + 1), timeout=TIMEOUT)
            if not r.ok:
                continue
            c = b64(r.json().get("c", "")).decode("utf-8", "replace") if r.ok else ""
        except Exception:
            try:
                r = s.get(api, timeout=TIMEOUT)
                c = b64(r.json().get("c", "")).decode("utf-8", "replace")
            except Exception:
                continue
        m = re.search(r"https?://[a-z0-9.-]*hdhub4u\.[a-z]{2,12}", c)
        if m:
            print(f"[domain] {api} -> {m.group(0)}")
            return m.group(0)
    return None


CARDS_RE = re.compile(
    r'<li class="thumb[^"]*"[^>]*>.*?<img[^>]*\ssrc="([^"]*)"[^>]*>\s*'
    r'<a[^>]*href="([^"]*)"[^>]*>.*?<figcaption>\s*'
    r'<a[^>]*href="[^"]*"[^>]*>\s*<p>(.*?)</p>', re.S)


def cards(html):
    return [(m.group(1), m.group(2), html_unesc(m.group(3))) for m in CARDS_RE.finditer(html)]


def html_unesc(x):
    return (x.replace("&#039;", "'").replace("&quot;", '"')
             .replace("&amp;", "&").replace("&#038;", "&"))


def typesense(s, live, q, page=1, limit=10):
    r = s.get(TYPESENSE, timeout=TIMEOUT, headers={"Referer": live + "/" + "search.html"},
              params={"q": q, "query_by": "post_title", "sort_by": "sort_by_date:desc",
                      "limit": str(limit), "highlight_fields": "none", "page": str(page)})
    if r.status_code != 200:
        return None
    return r.json()


EP_HEAD_RE = re.compile(r"<h4[^>]*>\s*(?:<[^>]+>\s*)*EPi?SODE\s*(\d+)", re.I)
LABEL_RE = re.compile(r"<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", re.S)


def post_links(html, live):
    m = re.search(r"<main[^>]*page-body[^>]*>(.*?)</main>", html, re.S)
    scope = m.group(1) if m else html
    return [(html_unesc(re.sub(r"<[^>]+>", " ", lab)), href)
            for href, lab in LABEL_RE.findall(scope)
            if not SKIP_HOSTS.search(re.sub(r"^https?://", "", href).split("/")[0])
            and live not in href and "category/" not in href]


# ───────────────────────── per-host link resolvers ─────────────────────────

def _packer_unpack(p, a, c, k):
    """Dean-Edwards / P.A.C.K.E.R unpack — mirrors core JsUnpacker."""
    DIG = "0123456789abcdefghijklmnopqrstuvwxyz"
    def enc(n, base):
        if n == 0: return "0"
        out = ""
        while n:
            out = DIG[n % base] + out; n //= base
        return out
    key = {}
    while c:
        c -= 1
        key[enc(c, a)] = k[c] if (c < len(k) and k[c]) else enc(c, a)
    return re.sub(r"\b\w+\b", lambda m: key.get(m.group(0), m.group(0)), p)


def _packer_find_all(html):
    """Every eval(function(p,a,c,k,e,d){...}('P',A,C,'K...')) block packed
    through the Dean-Edwards/P.A.C.K.E.R scheme."""
    return re.finditer(
        r"eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'([^']*)'\.split\('\|'\)\)",
        html, re.S)


def resolve_hdstream(s, url):
    """hdstream4u.com/file/<code>: hls URLs only exist INSIDE the
    eval(P.A.C.K.E.R) player block — a plain scan of the raw page sees no
    m3u8 at all. Unpack every block, then read the links={"hlsN":"..."} map;
    hls2 is the signed master.m3u8 (verified playable, dual-audio), hls3 an
    unsigned .txt alt, hls4 when present. Also harvests the {file:".vtt",
    label:"English"} caption tracks the player declares."""
    html = s.get(url, timeout=TIMEOUT, headers={"Referer": "https://hdstream4u.com/"}).text
    streams, subs = [], []
    for mm in _packer_find_all(html):
        out = _packer_unpack(mm.group(1), int(mm.group(2)), int(mm.group(3)), mm.group(4).split("|"))
        out = out.replace("\\/", "/").replace("\\\"", "\"")
        links = dict(re.findall(r"hls(\d)[\"']?\s*:\s*[\"'](https://[^\"']+)", out))
        best = links.get("4") or links.get("2") or links.get("3")
        if best:
            streams.append(best)
        subs += re.findall(r'file:"(https://[^"]+\.vtt[^"]*)",label:"([^"]+)"', out)
    if not streams:
        m = re.search(r"https://[^\"'\s]+master\.m3u8[^\"'\s]*", html)
        if m:
            streams.append(m.group(0))
    return streams + [f"SUB {lab} {u}" for u, lab in subs]


def resolve_hubcdn(s, url):
    """hubcdn.sbs/file/<id>: script var reurl ?r=b64 -> hubcdn/dl/?link=<final mkv>."""
    html = s.get(url, timeout=TIMEOUT, allow_redirects=False).text
    m = re.search(r'reurl\s*=\s*"([^"]+)"', html)
    if not m:
        return None
    q = re.search(r"[?&]r=([^&\"]+)", m.group(1))
    inner = b64(q.group(1)).decode("utf-8", "replace") if q else ""
    dl = re.search(r"[?&]link=([^&\"]+)", inner)
    return dl.group(1) if dl else inner or None


def resolve_hubdrive(s, url):
    """hubdrive.tips/file/<id>: POST /ajax.php?ajax=direct-download {id} -> drive link."""
    fid = re.search(r"/file/(\d+)", url).group(1)
    s.get(url, timeout=TIMEOUT)
    r = s.post("https://hubdrive.tips/ajax.php?ajax=direct-download",
               data={"id": fid}, timeout=TIMEOUT,
               headers={"Referer": url, "X-Requested-With": "XMLHttpRequest"})
    try:
        j = r.json()
    except Exception:
        return None
    if str(j.get("code")) != "200":
        return f"[{j.get('code')} {html_unesc(j.get('file',''))[:50]}]"
    d = j.get("data", {})
    return d.get("gd") if d.get("direct_dl") in ("drive", "cloud") \
        else f"https://drive.google.com/file/d/{d.get('gd')}/view"


RESOLVERS = {
    "hdstream4u.com": resolve_hdstream,
    "hubcdn.sbs": resolve_hubcdn,
    "hubcdn.io": resolve_hubcdn,
    "hubstream.art": lambda s, u: "(hubstream: needs browser; unprobed)",
    "hubdrive.tips": resolve_hubdrive,
}


def host_of(url):
    return re.sub(r"^https?://", "", url).split("/")[0].lower()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--recheck", metavar="POST_URL")
    ap.add_argument("--live-url", action="store_true")
    a = ap.parse_args()
    s = sess()

    live = resolve_live(s)
    if not live:
        print("FATAL: gateway APIs did not yield a live domain"); return 1
    if a.live_url:
        print(live); return 0
    print("LIVE:", live)

    if a.recheck:
        posts = [a.recheck]
    else:
        print("\n-- home/category/search cards --")
        hc = cards(s.get(live + "/", timeout=TIMEOUT).text)
        print(f"  home cards={len(hc)} first={hc[0][1] if hc else None}")
        cc = cards(s.get(live + "/category/bollywood-movies/", timeout=TIMEOUT).text)
        print(f"  category cards={len(cc)}")
        pg = cards(s.get(live + "/category/dual-audio/page/2/", timeout=TIMEOUT).text)
        print(f"  category page/2 cards={len(pg)}")
        ts = typesense(s, live, "avengers", limit=3)
        if ts and ts.get("hits"):
            d = ts["hits"][0]["document"]
            print(f"  typesense found={ts['found']} first={d['post_title'][:60]}")
        else:
            print("  typesense FAILED (403? Referer missing?)")
        season = next((c for c in hc if re.search(r"ALL Episodes|Season \d", c[2], re.I)), None)
        posts = [sc for sc in (hc[0][1] if hc else None, season[1] if season else None) if sc]

    for post in posts:
        if not post.startswith("http"):
            post = live + post
        print(f"\n-- post: {post}")
        html = s.get(post, timeout=TIMEOUT).text
        for lab, href in post_links(html, live)[:24]:
            h = host_of(href)
            res = RESOLVERS.get(h)
            if res:
                try:
                    out = res(s, href)
                except Exception as e:
                    out = f"ERR {type(e).__name__}: {e}"
                print(f"    [{lab[:24]:24}] {h:18} -> {str(out)[:110]}")
            else:
                print(f"    [{lab[:24]:24}] {h:18} (no resolver)")
        neps = len(set(EP_HEAD_RE.findall(html)))
        if neps:
            print(f"    (episode headings seen: {neps})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
