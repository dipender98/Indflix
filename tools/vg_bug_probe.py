"""Current-state probe (Sept 12 2026, user-reported bugs):
 1. Series: ep1 shows 480p, ep2 720p, ep3 1080p -> check gateway/link structure
    on a real series post + its genxfm pages.
 2. Movies not streaming -> verify fastdl embed -> dl.php -> googleusercontent
    chain still resolves.

Usage: e:/Project/Indflix/.venv/Scripts/python.exe tools/vg_bug_probe.py [search-term]
"""
import io, json, re, sys, urllib.parse
import requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
S = requests.Session(); S.verify = False
S.headers.update({"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})

SITE = "https://new2.vegamovies.futbol"

def get(u, ref=None, timeout=25):
    h = {"Referer": ref} if ref else {}
    return S.get(u, headers=h, timeout=timeout)

def search(q):
    u = f"{SITE}/ts-search.php?q={urllib.parse.quote(q)}&page=1"
    j = get(u, ref=SITE + "/").json()
    for h in j.get("hits", [])[:6]:
        d = h["document"]
        print("HIT:", d["post_title"][:80], "|", d["permalink"], "|cats:", d.get("category"))

def dump_post(url):
    t = get(url).text
    # walk headings + anchors in document order (like parseDetail)
    seq = re.findall(r'<h([1-6])[^>]*>(.{0,150}?)</h\1>|href="(https?://[^"]*genxfm[^"]*)"', t)
    print("--- headings/anchors of", url)
    for lvl, ht, href in seq:
        if lvl:
            ht = re.sub(r"<[^>]+>", "", ht).strip()
            if ht and len(ht) < 150:
                print(f"  H{lvl}: {ht[:120]}")
        elif href:
            print(f"     Anchor: {href[:90]}")
    return t

def dump_gateway(u):
    t = get(u, ref=SITE + "/").text
    title = re.search(r"<title>([^<]+)</title>", t)
    fastdl = re.findall(r'https://fastdl\.[a-z]+/embed(?:\.php)?\?download=\w+', t)
    vc = re.findall(r'https://vcloud\.[a-z]+/[a-z0-9]+', t)
    hub = re.findall(r'https://hubcloud\.[a-z]+/[^\s"\']+', t)
    print(f"GATEWAY {u}  title={title.group(1)[:70] if title else '?'}")
    print(f"   fastdl={len(fastdl)} vcloud={len(vc)} hubcloud={len(hub)}")
    for i, f in enumerate(fastdl[:25]):
        print(f"     fastdl[{i}]: …{f[-30:]}")
    for i, v in enumerate(vc[:25]):
        print(f"     vcloud[{i}]: {v}")
    return t, fastdl, vc

def resolve_fastdl(embed):
    t = get(embed, ref="https://nexdrive.fit/").text
    m = re.search(r'https?://fastdl\.[a-z]+/dl\.php\?link=(https?://[^\s"\'<>\\]+)', t)
    m2 = re.search(r'dl\.php\?link=([^"\'<>\s\\]+)', t)
    direct = None
    if m: direct = m.group(1)
    elif m2:
        cand = urllib.parse.unquote(m2.group(1))
        dm = re.search(r"https://video-downloads\.googleusercontent\.com/\S+", cand) or \
             re.search(r"https://video-downloads\.googleusercontent\.com/\S+", cand)
        direct = dm.group(0) if dm else None
    print("   EMBED", embed[-40:], "-> direct:", (direct or "NONE")[:90])
    if direct:
        try:
            r2 = S.get(direct, headers={"Referer": "https://fastdl.zip/", "Range": "bytes=0-1"},
                       timeout=20, stream=True)
            ct = r2.headers.get("content-type")
            print("      direct probe:", r2.status_code, ct, "resumable:", r2.headers.get("accept-ranges"))
            r2.close()
        except Exception as e:
            print("      direct probe ERR", str(e)[:80])

def resolve_vcloud(vurl):
    t = get(vurl, ref="https://nexdrive.fit/").text
    m = re.search(r"atob\(\s*atob\(\s*['\"]([A-Za-z0-9+/=]+)['\"]", t)
    if m:
        import base64
        try:
            one = base64.b64decode(m.group(1)).decode()
            two = base64.b64decode(one).decode()
            print("   VCLOUD", vurl, "double-atob ->", two[:120])
            return
        except Exception as e:
            print("   VCLOUD atob fail", str(e)[:60])
    m2 = re.search(r'href="(https?://hubcloud\.[a-z]+/[^"]+)"', t)
    print("   VCLOUD", vurl, "no atob; hubcloud href:", m2.group(1) if m2 else "none",
          "| tg:", "telegram" if "telegram" in t.lower() else "-", "| len", len(t))

def pick_gateway_links(t):
    """genxfm anchors that follow a per-episode/quality heading, in doc order."""
    return re.findall(r'href="(https?://[^"]*genxfm[^"]*)"', t)

if __name__ == "__main__":
    term = sys.argv[1] if len(sys.argv) > 1 else "Squid Game"
    mode = sys.argv[2] if len(sys.argv) > 2 else "series"
    print("== SEARCH:", term)
    u = f"{SITE}/ts-search.php?q={urllib.parse.quote(term)}&page=1"
    j = get(u, ref=SITE + "/").json()
    hit = None
    for h in j.get("hits", []):
        d = h["document"]
        cats = " ".join(d.get("category") or []).lower()
        is_ser = "series" in cats or "season" in d["post_title"].lower()
        if (mode == "series") == is_ser:
            hit = d
            break
    if not hit:
        print("no hit for", mode); sys.exit(0)
    url = hit["permalink"]
    if url.startswith("/"):
        url = SITE + url
    print("POST:", hit["post_title"][:80], "|", url)
    t = dump_post(url)
    gates = pick_gateway_links(t)
    print("  total genxfm anchors:", len(gates))
    # Expand the FIRST few distinct gateways to inspect per-episode structure.
    seen = set()
    for g in gates:
        g = g.rstrip("/") + "/"
        if g in seen: continue
        seen.add(g)
        if len(seen) > 4: break
        gt, fastdl, vc = dump_gateway(g)
        if fastdl:
            resolve_fastdl(fastdl[0])
        if vc:
            resolve_vcloud(vc[0])
