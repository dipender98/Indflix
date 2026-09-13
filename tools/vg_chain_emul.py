"""Kotlin-identical chain emulation for one movie gate: gate -> embed -> reurl ->
direct GET + container sniff."""
import io, re, sys, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
s = requests.Session(); s.verify = False
s.headers.update({"User-Agent": UA})

post = sys.argv[1] if len(sys.argv) > 1 else "https://new2.vegamovies.futbol/download-avengers-endgame-2019-hindi-dubbed-480p-720p-1080p-2160p-4k/"
t = s.get(post, timeout=25).text
GEN = re.compile(r"""https?://[a-z0-9.\-]*nexdrive\.[a-z]{2,10}/genxfm[^\s"'<>\\]+""", re.I)
g = GEN.findall(t)[0]
gt = s.get(g, headers={"Referer": post}, timeout=25).text
FASTDL = re.compile(r"""https?://fastdl\.[a-z]{2,10}/embed(?:\.php)?\?download=[A-Za-z0-9_\-]+""", re.I)
emb = FASTDL.findall(gt)
print("gate", g, "| embeds", len(emb))
embed = emb[0]
et = s.get(embed, headers={"Referer": g, "Accept": "text/html"}, timeout=25).text
print("embed body len", len(et))
REURL = re.compile(r"""https?://fastdl\.[a-z]{2,10}/dl\.php\?link=(https?://[^\s"'<>\\]+)""", re.I)
m = REURL.search(et)
print("reurl group:", m.group(1)[:120] if m else None)
DIRECT = re.compile(r"https://video-downloads\.googleusercontent\.com/\S+")
d = DIRECT.search(m.group(1)) if m else None
direct = d.group(0) if d else None
print("direct:", (direct or "NONE")[:110])
if direct:
    print("direct tail repr:", repr(direct[-40:]))
    r = s.get(direct, headers={"Referer": "https://fastdl.zip/", "User-Agent": UA},
              timeout=60, stream=True)
    print("GET:", r.status_code, r.headers.get("content-type"),
          "len:", r.headers.get("content-length"),
          "cd:", (r.headers.get("content-disposition") or "")[:70])
    chunks = []
    got = 0
    for c in r.iter_content(500000):
        chunks.append(c); got += len(c)
        if got >= 3_000_000: break
    first = b"".join(chunks)
    print("received", len(first), "bytes OK")
    if first[:4] == b"\x1a\x45\xdf\xa3": print("CONTAINER: matroska/webm")
    elif first[4:8] == b"ftyp": print("CONTAINER: mp4")
    else: print("CONTAINER:?", first[:8].hex())
    r.close()
    # can we seek? range request mid-file
    total = int(r.headers.get("content-length") or 0)
    if total:
        r2 = s.get(direct, headers={"Referer": "https://fastdl.zip/", "Range": f"bytes={total//2}-{total//2+100}"}, timeout=30, stream=True)
        print("MID Range:", r2.status_code, "content-range:", r2.headers.get("content-range"), "got:", len(r2.content))
        r2.close()
