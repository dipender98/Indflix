"""Exactly mimic the plugin: for a series POST, list heading -> chip anchors with
JSOUP-like text, then expand each gateway counting fastdl/vcloud families by order."""
import io, re, sys, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
from bs4 import BeautifulSoup

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
S = requests.Session(); S.verify = False
S.headers.update({"User-Agent": UA})

POST = sys.argv[1] if len(sys.argv) > 1 else "https://new2.vegamovies.futbol/download-squid-game-the-challenge-hindi-english-series-480p-720p-1080p-web-dl/"

t = S.get(POST, timeout=25).text
soup = BeautifulSoup(t, "html.parser")
root = soup.select_one("div.entry-content") or soup
cur = ""
for el in root.find_all(True):
    if el.name and re.fullmatch(r"h[1-6]", el.name):
        txt = el.get_text(" ", strip=True)
        if txt and len(txt) < 220:
            print(f"H {txt[:100]}")
    elif el.name == "a":
        href = el.get("href") or ""
        if "genxfm" in href:
            print(f"   CHIP [{el.get_text(' ',strip=True)[:40]}] {href}")

# expand S1 480 gateways
for g in ["https://nexdrive.fit/genxfm784776339014/", "https://nexdrive.fit/genxfm784776340446/",
          "https://nexdrive.fit/genxfm784776500208/", "https://nexdrive.fit/genxfm784776500207/"]:
    try:
        gt = S.get(g, headers={"Referer": POST}, timeout=25).text
    except Exception as e:
        print("GATE", g, "ERR", str(e)[:60]); continue
    ti = re.search(r"<h1[^>]*>(.*?)</h1>", gt, re.S)
    f = re.findall(r'https://fastdl\.[a-z]+/embed(?:\.php)?\?download=\w+', gt)
    v = re.findall(r'https://vcloud\.[a-z]+/[A-Za-z0-9_\-]{6,}', gt)
    vs = re.findall(r'https://vcloud\.[a-z]+/[A-Za-z0-9_\-]+', gt)
    print(f"GATE {g}\n  h1={re.sub('<[^>]+>','',ti.group(1)).strip()[:90] if ti else '?'}\n  fastdl={len(f)} vcloud(6+)={len(v)} vcloud(any)={len(vs)}")
    # doc order interleave check
    seq = re.findall(r'(https://fastdl\.[a-z]+/embed(?:\.php)?\?download=\w+|https://vcloud\.[a-z]+/[A-Za-z0-9_\-]+)', gt)
    kinds = ["F" if x.startswith("https://fastdl") else "V" for x in seq]
    print("  order:", "".join(kinds))
