import re, sys, io, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
s = requests.Session(); s.verify = False
s.headers.update({"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/138.0.0.0"})

for slug in ["https://new2.vegamovies.futbol/download-crew-girl-2026-season-1-netflix/",
             "https://new2.vegamovies.futbol/download-my-bias-my-boss-season-1-hindi-dubbed-series-480p-720p-1080p-web-dl/"]:
    t = s.get(slug, timeout=25).text
    print("=" * 100); print(slug, "len", len(t))
    # strip tags but keep block boundaries: show the text sequence between h-tags and anchors
    seg = re.sub(r"<script[\s\S]*?</script>", "", t)
    # print raw html around each genxfm anchor (first 6), 500 chars back
    for m in list(re.finditer(r'href="https://nexdrive[^"]+"', seg))[:6]:
        i = m.start()
        raw = seg[max(0, i-500):i+80]
        txt = re.sub(r"<[^>]+>", " ", raw)
        txt = re.sub(r"\s+", " ", txt)
        print(" >>>", txt[-220:].encode("ascii", "replace").decode())
    # any table rows with EP markers?
    print("  EP markers:", re.findall(r"(?i)(?:episode|ep\.?|S\d{1,2}E\d{1,2}|\bE\d{1,3}\b|\d{1,2}x\d{2})", re.sub(r"<[^>]+>"," ",seg))[:40])
