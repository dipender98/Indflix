#!/usr/bin/env python3
"""
link_status.py — live link-resolution status for CineVood posts.
Mirrors the provider pipeline: wp-json post -> download groups -> 720p floor
-> gate classification. Reports per post: total links, links surviving the
quality floor, gate vs direct, and per-gate outcome from THIS machine
(CF-MANAGED here = the app's CloudflareKiller WebView job; a CLI cannot solve
it). Run:  python tools/link_status.py [base] [slug ...]
"""
import json
import re
import ssl
import sys
import urllib.error
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
CTX = ssl.create_default_context(); CTX.check_hostname = False; CTX.verify_mode = ssl.CERT_NONE
HD = re.compile(r"(?i)\b(720|1080|2160)\s*p\b")
GATE = re.compile(r"mobilejsr\.rest|/genx[a-z]*\d{3,}", re.I)
FILEHOST = re.compile(r"(?i)gdflix|drive\.google|usercontent|hubcloud|zipdisk|gofile|pixeldrain")

SLUGS = [
    "download-toxic-a-fairytale-for-grown-ups-2026-hindi-movie",
    "download-chumbak-season-1-hindi-netflix-complete-series-480p-720p-1080p",
    "download-heroes-season-1-4-hindi-english-series-480p-720p-1080p-web-dl",
]


def get(url, timeout=25):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            return r.status, r.geturl(), r.read(400000).decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        body = e.read(4096).decode("utf-8", "ignore") if e.fp else ""
        return e.code, url, body
    except Exception as e:
        return 0, url, type(e).__name__ + ":" + str(e)[:60]


def classify_gate(url):
    st, final, body = get(url)
    if st == 200 and not GATE.search(final):
        kind = "RESOLVED -> " + final[:60]
        live = FILEHOST.search(final) is not None or st == 200
        return ("OPEN" if live else "OPEN?"), kind
    if st in (403, 503) or "_cf_chl_opt" in body or "Just a moment" in body:
        return "CF-BLOCK", "Cloudflare managed challenge (app WebView solves)"
    if st == 0:
        return "TLS/NET", body[:60]
    return f"HTTP-{st}", final[:60]


def post_status(base, slug):
    st, _, body = get(f"{base}/wp-json/wp/v2/posts?slug={slug}&_embed")
    if st != 200:
        return {"slug": slug, "json": f"HTTP-{st}", "links": []}
    try:
        o = json.loads(body)[0]
    except Exception as e:
        return {"slug": slug, "json": f"parse:{e}", "links": []}
    content = o.get("content", {}).get("rendered", "")
    labels = re.findall(r"mfx-quality-title[^>]*>([^<]{5,200})<", content)
    hrefs = re.findall(r'mfx-download-link[^>]*href="([^"]+)"', content)
    pairs = list(zip(labels, hrefs))
    hd = [(l, h) for (l, h) in pairs if HD.search(l)]
    return {"slug": slug, "json": "OK", "title": o.get("title", {}).get("rendered", "")[:70],
            "links": pairs, "hd": hd}


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "https://moviesflixi.com"
    slugs = sys.argv[2:] or SLUGS
    print(f"== CineVood link status on {base} ==\n")
    grand_total = grand_hd = grand_resolved = grand_blocked = 0
    for slug in slugs:
        r = post_status(base, slug)
        if r["json"] != "OK":
            print(f"[{slug[:60]}]  JSON: {r['json']}  -> skipped (post not on this mirror)")
            print()
            continue
        links = r["links"]
        hd = r["hd"]
        gates = sorted({h for _, h in links if GATE.search(h)})
        print(f"[{r['title'] or slug}]")
        print(f"  links in post : {len(links)}  (quality-floor >=720p: {len(hd)})")
        for lb, h in links:
            tag = "HD-KEEP " if HD.search(lb) else "FLOOR-DROP"
            kind = "gate" if GATE.search(h) else "direct"
            print(f"    {tag}  [{kind:6}] {lb[:80]}")
        resolved = blocked = 0
        for g in gates:
            kind, note = classify_gate(g)
            if kind == "CF-BLOCK":
                blocked += 1
            elif kind == "OPEN":
                resolved += 1
            print(f"    GATE {g[:58]:<58} {kind:<9} {note[:52]}")
        direct_links = [h for _, h in links if not GATE.search(h)]
        for d in direct_links:
            st, final, _ = get(d)
            state = "OK" if st == 200 else f"HTTP-{st}"
            print(f"    DIRECT {d[:56]:<56} {state}")
            if st == 200:
                resolved += 1
        grand_total += len(links); grand_hd += len(hd)
        grand_resolved += resolved; grand_blocked += blocked
        print(f"  SUMMARY: total={len(links)} hd={len(hd)} resolved-here={resolved} "
              f"cf-blocked-here={blocked} (cf = app WebView solve)\n")
    print(f"== TOTALS: links={grand_total} after-quality-floor={grand_hd} "
          f"resolved-from-cli={grand_resolved} blocked-by-CF-from-cli={grand_blocked} ==")


if __name__ == "__main__":
    main()
