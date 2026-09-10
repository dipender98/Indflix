#!/usr/bin/env python3
"""
site_survey.py — shallow-shallow probe of 20 candidate movie sites, then a
shallow-deep probe of the 5 best. For every site: origin status, TTFB, Cloudflare
managed-challenge detection, WordPress/wp-json availability, dual/multi-audio
evidence, library depth (max /page/N/ seen on home), and file-host/watch-server
markers. Deep probe (top 5): wp-json posts endpoint, one real post page, the
external hosts the post links to.

Usage:
    python tools/site_survey.py           # shallow pass, auto-top-5 deep pass
    python tools/site_survey.py --sites   # print the list only
"""

import concurrent.futures as futures
import json
import re
import ssl
import sys
import time
import urllib.request
import urllib.error
from collections import Counter

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
TIMEOUT = 12
CAP = 700_000

# (name, url, network note) — 20 candidates, all domains from live 2026-09 search hits
SITES = [
    ("cinevood.loan",        "https://cinevood.loan/",            "CineVood network A"),
    ("cinevoods.com",        "https://cinevoods.com/",            "CineVood network A"),
    ("cinevood.bingo",       "https://cinevood.bingo/",           "CineVood network B"),
    ("scloudx.lol",          "https://scloudx.lol/",              "scloud family"),
    ("mvhub24.com",          "https://mvhub24.com/",              "MVhub BD (dooplay)"),
    ("moviesflixi.com",      "https://moviesflixi.com/",          "CineVood-clone theme"),
    ("mlsbd.click",          "https://mlsbd.click/",              "MLSBD.NET"),
    ("mlfbd.best",           "https://mlfbd.best/",               "MLSBD family"),
    ("movieshb.com",         "https://movieshb.com/",             "Moviesflix HD clone"),
    ("xflixbd.org",          "https://xflixbd.org/",              "Xflixbd BD"),
    ("hdhubflix.xyz",        "https://hdhubflix.xyz/",            "hdhub4u clone"),
    ("hdhub.cfd",            "https://hdhub.cfd/",                "hdhub4u clone"),
    ("vegamoviese.co",       "https://vegamoviese.co/",           "vegamovies family"),
    ("vegamoviee.com",       "https://vegamoviee.com/",           "vegamovies family"),
    ("vegamoviess.cfd",      "https://vegamoviess.cfd/",          "vegamovies family"),
    ("bollygold.com",        "https://bollygold.com/",            "bolly4u/zilla clone"),
    ("hdmoviezclub.com",     "https://hdmoviezclub.com/",         "hdmovie2 clone"),
    ("morimoflix.xyz",       "https://tgmovies.morimoflix.xyz/",  "TG-Movies (gdflix)"),
    ("multimovies",          "https://multimovies.motorcycles/",  "repo official (dooplay)"),
    ("ofilmywap.pet",        "http://www.ofilmywap.pet/",         "filmywap clone"),
]

FILE_HOSTS = ["gdflix", "gdrivepro", "hubcloud", "filepress", "multicloudlinks",
              "vik1ngfile", "pixeldrain", "gofile", "drive.google", "zipdisk",
              "streamtape", "upcloud", "mixdrop", "dood.", "vidsrc", "videasy",
              "vidlink", "vaplayer", "vidrock", "movcube", "vidmoly", "vidhide",
              "filelions", "lulustream", "bloggerplay"]
WATCH_MARKS = ["dt_tabs", "dooplay", "dt_player", "embed", "iframe", "vidsrc",
               "videasy", "vidlink", "vaplayer", "vidrock", "jwplayer", "plyr",
               "video.js", "server_", "Host Server"]

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
CTX.set_ciphers("DEFAULT")


def fetch(url, timeout=TIMEOUT):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Accept": "text/html,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            body = r.read(CAP)
            return {"status": r.status, "ttfb": round(time.time() - t0, 2),
                    "final": r.geturl(), "server": r.headers.get("Server", ""),
                    "html": body.decode("utf-8", "ignore")}
    except urllib.error.HTTPError as e:
        return {"status": e.code, "ttfb": round(time.time() - t0, 2),
                "final": url, "server": e.headers.get("Server", "") if e.headers else "",
                "html": e.read(CAP).decode("utf-8", "ignore") if e.fp is None else ""}
    except Exception as e:
        return {"status": 0, "ttfb": round(time.time() - t0, 2), "final": url,
                "err": type(e).__name__ + ": " + str(e)[:60], "html": ""}


def probe_shallow(site):
    name, url, note = site
    r = fetch(url)
    h = r["html"]
    out = {"name": name, "note": note, "origin": url, "status": r["status"],
           "ttfb": r.get("ttfb"), "err": r.get("err", ""), "server": r.get("server", "")}
    hl = h.lower()
    out["cf_managed"] = ("_cf_chl_opt" in h) or ("just a moment" in hl)
    out["wp"] = ("wp-content" in hl) or ("api.w.org" in hl)
    out["generator"] = (re.search(r'name="generator" content="([^"]+)"', h) or [0, ""])[1]
    out["dual"] = len(re.findall(r"dual[\s-]*audio", hl))
    out["multi"] = len(re.findall(r"multi[\s-]*audio", hl))
    pgs = [int(x) for x in re.findall(r'/page/(\d+)/?', h)]
    out["pagemax"] = max(pgs) if pgs else 0
    out["cats"] = len(set(re.findall(r'href="[^"]*?/(?:category|genre)/([\w-]+)/', h)))
    out["hosts"] = ",".join(sorted({m for m in FILE_HOSTS if m in hl})[:6])
    out["watch"] = ",".join(sorted({m for m in WATCH_MARKS if m in hl})[:6])
    return out


REST = "/?rest_route=/wp/v2/posts&per_page=1&_fields=id,link"


def probe_deep(s):
    name, url = s["name"], s["origin"]
    d = {"name": name}
    rr = fetch(url.rstrip("/") + REST)
    d["rest"] = f'{rr["status"]} {rr["ttfb"]}s'
    home = fetch(url)
    posts = []
    if home["html"]:
        seen = set()
        for href in re.findall(r'href="([^"#?]+)"', home["html"]):
            if href in seen or not href.startswith("http"):
                continue
            try:
                path = re.match(r'https?://[^/]+(/.+)', href).group(1)
            except AttributeError:
                continue
            seg = path.strip("/").split("/")
            if len(seg) < 1 or seg[0] in ("category", "categories", "genre", "genres",
                                          "tag", "tags", "feed", "page", "author",
                                          "search", "wp-login.php", "sitemap_index.xml"):
                continue
            if any(x in path for x in ("wp-content", "wp-json", "wp-includes", ".xml", ".png", ".webp")):
                continue
            if re.search(r'/\d{4}/\d{2}/|/-20\d\d|download|movie|watch|/p/', path, re.I) and len(path) > 18:
                seen.add(href)
                posts.append(href)
        posts = posts[:3]
    d["post_candidates"] = posts
    for purl in posts[:2]:
        pr = fetch(purl)
        ph = pr["html"]
        hosts = Counter(u for u in re.findall(r'https?://([a-zA-Z0-9.-]+\.[a-z]{2,})', ph)
                        if u != url.split("/")[2])
        d["post"] = {"url": purl, "status": pr["status"], "ttfb": pr["ttfb"],
                     "ext_hosts": hosts.most_common(8),
                     "dl_markers": sorted({m for m in FILE_HOSTS if m in ph.lower()}),
                     "watch_markers": sorted({m for m in WATCH_MARKS if m in ph}),
                     "iframe_n": ph.lower().count("iframe")}
        break
    return d


class Tee:
    def __init__(self, *f): self.f = f
    def write(self, s):
        for x in self.f: x.write(s)
    def flush(self):
        pass


def main():
    report_path = "tools/site_survey_report.txt"
    sys.stdout = Tee(sys.__stdout__, open(report_path, "w", encoding="utf-8"))
    shallow = []
    with futures.ThreadPoolExecutor(max_workers=10) as ex:
        futs = {ex.submit(probe_shallow, s): s[0] for s in SITES}
        for f in futures.as_completed(futs):
            shallow.append(f.result())
    shallow.sort(key=lambda x: x["name"])

    print("SHALLOW — 20 sites (home probe)\n")
    hdr = f'{"site":<20}{"HTTP":>5}{"t(s)":>6}{"CFchal":>7}{"WP":>3}{"dual":>5}{"multi":>6}{"pgmax":>6}{"cats":>5}  filehosts / watch'
    print(hdr); print("-" * len(hdr))
    for s in shallow:
        print(f'{s["name"]:<20}{s["status"]:>5}{s["ttfb"]:>6}{str(s["cf_managed"]):>7}'
              f'{str(s["wp"]):>3}{s["dual"]:>5}{s["multi"]:>6}{s["pagemax"]:>6}{s["cats"]:>5}  '
              f'{s["hosts"]}{"|watch:" + s["watch"] if s["watch"] else ""}')

    def score(s):
        if s["status"] not in (200,) or s["cf_managed"]:
            return -100
        return (2 * s["dual"] + 3 * s["multi"] + min(s["pagemax"], 500) + 20 * s["wp"]
                + 10 * bool(s["hosts"]) + 8 * bool(s["watch"]))
    top = [s for s in shallow if s["status"] == 200]
    top.sort(key=lambda s: -score(s))
    if "--deep" in sys.argv:
        names = sys.argv[sys.argv.index("--deep") + 1:]
        chosen = [s for s in shallow if s["name"] in names]
    else:
        chosen = [s for s in top[:6] if s["name"] != "multimovies"][:5]
    print("\nDEEP — best", len(chosen), [c["name"] for c in chosen], "\n")
    with futures.ThreadPoolExecutor(max_workers=5) as ex:
        for d in ex.map(probe_deep, chosen):
            print(json.dumps(d, default=str)[:1200], "\n")


if __name__ == "__main__":
    if "--sites" in sys.argv:
        for n, u, note in SITES:
            print(f"{n:<22}{u:<42}{note}")
    else:
        main()
