"""CSX-style V-Cloud chain: vcloud.fit/<id> -> double-atob token page ->
buttons (FSLv2/R2/Direct). Range/206 playability probe of each button URL."""
import io, re, sys, base64, urllib.parse, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
S = requests.Session(); S.verify = False
S.headers.update({"User-Agent": UA})

def b64x2(v):
    return base64.b64decode(base64.b64decode(v)).decode(errors="replace")

def walk(vurl, label):
    print("=" * 80); print(label, vurl)
    t1 = S.get(vurl, timeout=25).text
    m = re.search(r"""var\s+url\s*=\s*atob\(\s*atob\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)\s*\)""", t1)
    if not m:
        m = re.search(r"""atob\(\s*atob\(\s*['"]([A-Za-z0-9+/=]+)['"]""", t1)
    if not m:
        print("  NO atob; body head:", re.sub(r"\s+", " ", t1[:200]))
        return
    tok = b64x2(m.group(1))
    print("  token URL:", tok[:100])
    r2 = S.get(tok, headers={"Referer": vurl}, timeout=25)
    h = r2.text
    print("  step2:", r2.status_code, "len", len(h), "ct", r2.headers.get("content-type", "")[:30])
    # CSX: document.select("h2 a.btn")
    pairs = []
    for a in re.finditer(r'<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>', h, re.S):
        href, txt = a.group(1), re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", a.group(2))).strip()
        if txt and ("Download" in txt or "Server" in txt or "File" in txt):
            pairs.append((href, txt))
    for href, txt in pairs[:10]:
        u = href if href.startswith("http") else urllib.parse.urljoin(str(r2.url), href)
        print(f"  BTN [{txt[:30]}] -> {u[:100]}")
        try:
            pr = S.get(u, headers={"Range": "bytes=0-65535"}, timeout=25, stream=True)
            cr = pr.headers.get("content-range"); acc = pr.headers.get("accept-ranges")
            ct = pr.headers.get("content-type")
            first = next(pr.iter_content(16), b"")
            cont = "MKV" if first[:4].hex() == "1a45dfa3" else ("mp4" if b"ftyp" in first[:12] else first[:8].hex())
            print(f"       GET Range -> {pr.status_code} ct={ct} cr={cr} acc={acc} start={cont}")
            pr.close()
        except Exception as e:
            print("       ERR", str(e)[:70])

# series S2 480 ep1 vcloud + movie Endgame 480 vcloud (fresh from gate)
walk("https://vcloud.fit/b88ugmhwbj0jiyc", "SERIES S2-480-EP1")
gt = S.get("https://nexdrive.fit/genxfm7847765350/", headers={"Referer": "https://new2.vegamovies.futbol/"}, timeout=25).text
vs = re.findall(r"https://vcloud\.[a-z]+\.[a-z]+/[A-Za-z0-9_\-]{6,}", gt)
if vs: walk(vs[0], "MOVIE-480")
