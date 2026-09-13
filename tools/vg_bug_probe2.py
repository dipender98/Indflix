"""Dump ordered H/anchor sequence + chip texts around download blobs on a post,
and check a movie post's embed chain + vcloud token follow-up."""
import io, re, sys, urllib.parse, base64, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
S = requests.Session(); S.verify = False
S.headers.update({"User-Agent": UA})
SITE = "https://new2.vegamovies.futbol"

def seq(url, label):
    t = S.get(url, timeout=25).text
    print("=" * 80); print(label, url)
    pat = r'<h([1-6])[^>]*>(.{0,180}?)</h\1>|<a [^>]*href="(https?://[^"]*genxfm[^"]*)"[^>]*>(.{0,60}?)</a>'
    for m in re.finditer(pat, t, re.S):
        lvl, ht, href, txt = m.group(1), m.group(2), m.group(3), m.group(4)
        if lvl:
            ht = re.sub(r"<[^>]+>", "", ht).strip()
            print(f"  H{lvl}: {ht[:110]}")
        else:
            chip = re.sub(r"<[^>]+>", "", txt).strip()
            print(f"     A[{chip[:30]}] {href[:80]}")
    return t

def movie_chain(term):
    j = S.get(f"{SITE}/ts-search.php?q={urllib.parse.quote(term)}&page=1",
              headers={"Referer": SITE + "/"}, timeout=25).json()
    hit = None
    for h in j.get("hits", []):
        d = h["document"]
        cats = " ".join(d.get("category") or []).lower()
        if "movie" in cats or "movie" in d["post_title"].lower():
            hit = d; break
    if not hit: print("no movie hit"); return
    url = hit["permalink"]
    if url.startswith("/"): url = SITE + url
    print("MOVIE:", hit["post_title"][:70], url)
    t = S.get(url, timeout=25).text
    gates = re.findall(r'href="(https?://[^"]*genxfm[^"]*)"', t)
    print("  genxfm anchors:", len(gates))
    for g in gates[:3]:
        g = g.rstrip("/") + "/"
        gt = S.get(g, headers={"Referer": url}, timeout=25).text
        ti = re.search(r"<title>([^<]+)</title>", gt)
        f = re.findall(r'https://fastdl\.[a-z]+/embed(?:\.php)?\?download=\w+', gt)
        v = re.findall(r'https://vcloud\.[a-z]+/[a-z0-9]+', gt)
        print(f"GATE {g}  title={ti.group(1)[:60] if ti else '?'} fastdl={len(f)} vcloud={len(v)}")
        if f:
            et = S.get(f[0], headers={"Referer": "https://nexdrive.fit/"}, timeout=25).text
            m = re.search(r'https?://fastdl\.[a-z]+/dl\.php\?link=(https?://[^\s"\'<>\\]+)', et)
            m2 = re.search(r'dl\.php\?link=([^"\'<>\s\\]+)', et)
            print("   embed len", len(et), "reurl:", (m.group(1)[:80] if m else None),
                  "| param:", urllib.parse.unquote(m2.group(1))[:80] if m2 else None)
            cand = None
            if m: cand = m.group(1)
            elif m2:
                c = urllib.parse.unquote(m2.group(1))
                dm = re.search(r"https://video-downloads.googleusercontent.com/\S+", c)
                if dm: cand = dm.group(0)
            if cand:
                pr = S.get(cand, headers={"Referer": "https://fastdl.zip/", "Range": "bytes=0-1"},
                           timeout=20, stream=True)
                print("   direct:", pr.status_code, pr.headers.get("content-type"), pr.headers.get("accept-ranges"))
                pr.close()
        # first vcloud follow-up
        if v:
            vt = S.get(v[0], headers={"Referer": g}, timeout=25).text
            mm = re.search(r"atob\(\s*atob\(\s*['\"]([A-Za-z0-9+/=]+)['\"]", vt)
            print("   vcloud page len", len(vt))
            if mm:
                one = base64.b64decode(mm.group(1)).decode()
                two = base64.b64decode(one).decode()
                print("      token url:", two[:110])
                r2 = S.get(two, headers={"Referer": v[0]}, timeout=25, allow_redirects=False)
                loc = r2.headers.get("Location")
                b2 = r2.text
                mm3 = re.search(r"atob\(\s*atob\(\s*['\"]([A-Za-z0-9+/=]+)['\"]", b2)
                print("      follow:", r2.status_code, "loc:", (loc or "")[:90],
                      "again atob:", bool(mm3), "tg.me:", "telegram" if "telegram" in b2.lower() else "-")

mov = sys.argv[1] if len(sys.argv) > 1 else "Avengers Endgame"
seq("https://new2.vegamovies.futbol/download-squid-game-the-challenge-hindi-english-series-480p-720p-1080p-web-dl/",
    "SERIES SEQ")
movie_chain(mov)
