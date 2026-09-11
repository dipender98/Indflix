#!/usr/bin/env python3
"""
cv_probe.py — health probe for the CineVood provider targets.

Checks, in order:
  1. every mirror seed answers on /wp-json/ (origin reachable, not ISP-blocked)
  2. wp-json list forms: posts?search=, categories, media?include=
  3. a real post page + its gate URL (mobilejsr.rest) reachability class
     (200 vs CF managed challenge vs TLS-blocked)

Exit code 0 when the primary search path (wp-json search) works from this
machine; 1 otherwise. Run before/after provider releases or when the site
"breaks":
    python tools/cv_probe.py                      # full health sweep
    python tools/cv_probe.py --post <slug>        # one post: groups/imdb/gates
    python tools/cv_probe.py --gate <gate-url>    # classify a single gate URL
"""
import ssl
import sys
import time
import urllib.error
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
MIRRORS = [
    "https://cinevood.loan",
    "https://cinevoods.com",
    "https://cinevood.ltd",
    "https://cinevoodc.ltd",
    "https://moviesflixi.com",
]
GATE_SAMPLE = "https://mobilejsr.rest/genxfm784776504721/"

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def head(url, timeout=12):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            body = r.read(4096)
            return r.status, round(time.time() - t0, 2), body.decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, round(time.time() - t0, 2), (e.read(2048).decode("utf-8", "ignore") if e.fp else "")
    except Exception as e:
        return 0, round(time.time() - t0, 2), type(e).__name__ + ": " + str(e)[:80]


def classify(status, body):
    if status == 200:
        return "OK"
    if "_cf_chl_opt" in body or "Just a moment" in body:
        return "CF-MANAGED"
    if status == 0:
        return "BLOCKED/TLS"
    return f"HTTP-{status}"


def gate_probe(url):
    st, t, b = head(url, timeout=20)
    tag = classify(st, b)
    print(f"{url[:70]:<70} {tag:<14} {t:>6.2f}s")
    sys.exit(0 if tag == "OK" else 1)


def post_probe(slug, base="https://cinevood.loan"):
    import re
    print(f"== post probe: {slug} on {base} ==")
    st, t, body = head(
        f"{base}/wp-json/wp/v2/posts?slug={slug}&_embed", timeout=20
    )
    print(f"wp-json status {st} ttfb={t}s bytes={len(body)}")
    if st != 200:
        print("RESULT: JSON FAIL")
        sys.exit(1)
    try:
        arr = json.loads(body)
        o = arr[0]
    except Exception as e:
        print("RESULT: JSON parse fail", e)
        sys.exit(1)
    content = o.get("content", {}).get("rendered", "")
    embedded = "_embedded" in o and bool(o.get("_embedded"))
    imdb = (re.search(r"imdb\.com/title/(tt\d+)", content) or [None, None])[1]
    labels = re.findall(r"mfx-quality-title[^>]*>([^<]{5,160})<", content)
    gates = sorted(set(re.findall(r'https?://[^"\']*?/genx[a-z]*\d+', content)))
    hd = sum(1 for lb in labels if re.search(r"(?i)\b(720|1080|2160)\s*p\b", lb))
    print(f"title      : {o.get('title', {}).get('rendered', '')[:80]}")
    print(f"featured   : {embedded}")
    print(f"imdb       : {imdb}")
    print(f"groups     : {len(labels)} raw, {hd} hd(>=720p)")
    for lb in labels[:8]:
        print(f"  - {lb[:90]}")
    print(f"gate hosts : {gates[:3]}")
    ok = st == 200 and len(labels) > 0 and hd > 0
    print("RESULT:", "post healthy (groups + hd + gate present)" if ok
          else "POST DEGRADED (no groups or no hd links)")
    sys.exit(0 if ok else 1)


def main():
    argv = sys.argv
    if "--gate" in argv:
        gate_probe(argv[argv.index("--gate") + 1])
    if "--post" in argv:
        i = argv.index("--post")
        slug = argv[i + 1]
        base = argv[argv.index("--base") + 1] if "--base" in argv else MIRRORS[0]
        post_probe(slug, base)
    ok_search = False
    print("== mirrors (wp-json probe) ==")
    for m in MIRRORS:
        st, t, b = head(m + "/wp-json/", timeout=10)
        print(f"{m:<28} {classify(st, b):<14} {t:>6.2f}s")

    base = MIRRORS[0]
    print("\n== json forms on", base, "==")
    for name, path in [
        ("posts?search", "/wp-json/wp/v2/posts?search=dhamaal&per_page=2&_fields=id,link,title"),
        ("posts?categories", "/wp-json/wp/v2/posts?categories=8&per_page=2&_fields=id,link"),
        ("categories", "/wp-json/wp/v2/categories?per_page=2&_fields=id,slug"),
        ("post?slug&_embed", "/wp-json/wp/v2/posts?slug=download-toxic-a-fairytale-for-grown-ups-2026-hindi-movie&_embed"),
    ]:
        st, t, b = head(base + path)
        tag = classify(st, b)
        if name == "posts?search" and st == 200 and b.lstrip().startswith("["):
            ok_search = True
        print(f"{name:<20} {tag:<14} {t:>6.2f}s")

    print("\n== gate (expected CF-MANAGED; solved in-app by CloudflareKiller) ==")
    st, t, b = head(GATE_SAMPLE)
    print(f"{'mobilejsr gate':<20} {classify(st, b):<14} {t:>6.2f}s")

    print("\nRESULT:", "search path healthy" if ok_search else "SEARCH PATH DOWN — check mirrors")
    sys.exit(0 if ok_search else 1)


if __name__ == "__main__":
    main()
