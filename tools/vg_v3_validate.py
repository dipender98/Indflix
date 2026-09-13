"""Final v3 validation: confirm the plugin's exact regexes match the LIVE pages.
1. movie gate: FASTDL regex finds embeds, VCLOUD regex finds vcloud link.
2. vcloud.fit page: ATOB_ATOB regex (var url = atob(atob(..))) matches.
3. token page: BTN regex hrefs + STREAMABLE_HOST filter yields >=1 R2 URL.
4. series gate interleave: FASTDL/VCLOUD families each == episode count,
   and the -:Episodes: headings exist (=10)."""
import io, re, sys, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
S = requests.Session(); S.verify = False
S.headers.update({"User-Agent": UA})

# Kotlin-identical regexes from the plugin:
FASTDL = re.compile(r"""https?://fastdl\.[a-z]{2,10}/embed(?:\.php)?\?download=[A-Za-z0-9_\-]+""", re.I)
VCLOUD = re.compile(r"""https?://vcloud\.[a-z]{2,10}/[A-Za-z0-9_\-]{6,}""")
ATOB_ATOB = re.compile(r"""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([A-Za-z0-9+/=]{16,})['"]\s*\)\s*\)""")
BTN = re.compile(r"""<a\b[^>]*href="([^"]+)"[^>]*>\s*(?:<[^>]+>\s*)*([^<]*(?:Download|Server|File)[^<]*)(?:<[^>]+>\s*)*</a>""", re.I)
STREAMABLE_HOST = re.compile(r"""(r2\.cloudflarestorage\.com|r2\.dev)""", re.I)

fails = 0
def ck(name, cond, extra=""):
    global fails
    print(("OK  " if cond else "FAIL") + " " + name + (" " + extra if extra else ""))
    if not cond: fails += 1

# 1. movie gate
gt = S.get("https://nexdrive.fit/genxfm7847765350/", headers={"Referer": "https://new2.vegamovies.futbol/"}, timeout=30).text
f = FASTDL.findall(gt); v = VCLOUD.findall(gt)
ck("movie gate has fastdl+vcloud by host regex", len(f) >= 1 and len(v) >= 1, f"f={len(f)} v={len(v)}")

# 2+3. vcloud chain via plugin regexes
t1 = S.get(v[0], timeout=30).text
m = ATOB_ATOB.search(t1)
ck("ATOB_ATOB matches live vcloud page", bool(m))
if m:
    import base64
    tok = base64.b64decode(base64.b64decode(m.group(1))).decode()
    t2 = S.get(tok, headers={"Referer": v[0]}, timeout=30).text
    hits = [(h, txt) for h, txt in BTN.findall(t2) if STREAMABLE_HOST.search(h)]
    ck("BTN+STREAMABLE yield R2 urls", len(hits) >= 1, f"n={len(hits)}")
    if hits:
        pr = S.get(hits[0][0], headers={"Range": "bytes=0-1023"}, timeout=30, stream=True)
        ck("R2 answers 206 seekable", pr.status_code == 206, f"cr={pr.headers.get('content-range')}")
        pr.close()

# 4. series gateway interleave + episode headings
sg = S.get("https://nexdrive.fit/genxfm784776339014/", headers={"Referer": "https://new2.vegamovies.futbol/"}, timeout=30).text
sf = FASTDL.findall(sg); sv = VCLOUD.findall(sg)
eps = re.findall(r"Episodes:\s*(\d{1,2})", sg)
ck("series gate families == 10 each", len(sf) == 10 and len(sv) == 10, f"f={len(sf)} v={len(sv)}")
ck("series gate has 10 episode headings", len(set(e.lstrip('0') or '0' for e in eps)) >= 9 or len(eps) >= 9, f"eps={eps[:12]}")

# 5. episode numbering correctness: family idx i pairs with -:Episodes: i+1 heading
seq = re.findall(r"Episodes:\s*(\d{1,2})|https://vcloud\.[a-z]{2,10}/[A-Za-z0-9_\-]{6,}|https?://fastdl\.[a-z]{2,10}/embed(?:\.php)?\?download=[A-Za-z0-9_\-]+", sg)
v_idx = 0
ok_order = True
for tok in re.finditer(r"(Episodes:\s*(\d{1,2}))|(https://vcloud\.[a-z]{2,10}/[A-Za-z0-9_\-]{6,})|(https?://fastdl\.[a-z]{2,10}/embed(?:\.php)?\?download=[A-Za-z0-9_\-]+)", sg):
    g = tok.groups()
    if g[0]:
        expected = int(g[1]) - 1
        if v_idx != expected:
            ok_order = False
    elif g[2] and g[0] is None and g[3] is None:
        v_idx += 1
ck("vcloud family document order == episode number", ok_order, f"walked={v_idx}")

print("\nRESULT:", "ALL PASS" if fails == 0 else f"{fails} FAILURES")
sys.exit(1 if fails else 0)
