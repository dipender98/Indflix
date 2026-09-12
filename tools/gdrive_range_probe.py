import re, requests, urllib3, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
s = requests.Session(); s.verify = False
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

# Resolve fresh: Avengers 480p genxfm -> fastdl embed -> direct URL?
r = s.get("https://nexdrive.fit/genxfm7847765350/", timeout=30,
          headers={"User-Agent": UA, "Referer": "https://new2.vegamovies.futbol/"})
embed = re.search(r"https://fastdl\.[a-z]+/embed\?download=[A-Za-z0-9]+", r.text).group(0)
e = s.get(embed, timeout=30, headers={"User-Agent": UA, "Referer": "https://nexdrive.fit/"})
m = re.search(r"https?://fastdl\.[a-z]+/dl\.php\?link=(https?://[^\s\"'<>\\]+)", e.text)
direct = m.group(1)
print("direct:", direct[:100])

h = {"User-Agent": UA, "Referer": "https://fastdl.zip/"}
for rng in ["bytes=0-1023", "bytes=0-", "bytes=1000000-1001023"]:
    rr = s.get(direct, headers={**h, "Range": rng}, stream=True, timeout=30)
    print(f"GET Range[{rng}] -> {rr.status_code} ct={rr.headers.get('content-type')} "
          f"cr={rr.headers.get('content-range')} cl={rr.headers.get('content-length')} cd={bool(rr.headers.get('content-disposition'))}")
    rr.close()

rh = s.head(direct, headers=h, timeout=30, allow_redirects=True)
print("HEAD ->", rh.status_code, rh.headers.get("content-type"), rh.headers.get("content-length"), rh.headers.get("accept-ranges"))

# Variant: strip ?xx or try googleapis videoplay? Just try drive host swap (common trick): u?confirm
alt = re.sub(r"video-downloads", "drive.usercontent", direct)
ra = s.get(alt, headers=h, timeout=30, stream=True)
print("drive.usercontent ->", ra.status_code, ra.url[:90], ra.headers.get("content-type"), ra.headers.get("accept-ranges"))
ra.close()
