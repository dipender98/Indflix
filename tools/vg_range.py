import re, sys, io, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
s = requests.Session(); s.verify = False
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

t = s.get("https://new2.vegamovies.futbol/download-avengers-endgame-2019-hindi-dubbed-480p-720p-1080p-2160p-4k/",
          timeout=25, headers={"User-Agent": UA, "Referer": "https://new2.vegamovies.futbol/"}).text
g = re.search(r'https://nexdrive\.[a-z]+/genxfm\d+/', t).group(0)
p = s.get(g, timeout=25, headers={"User-Agent": UA, "Referer": "https://new2.vegamovies.futbol/"}).text
fastdl = re.findall(r'https://fastdl\.[a-z]+/embed\?download=[A-Za-z0-9]+', p)[0]
nh = g[:g.index('/', 8)] + "/"
e = s.get(fastdl, timeout=25, headers={"User-Agent": UA, "Referer": nh}).text
m = re.search(r'https?://fastdl\.[a-z]+/dl\.php\?link=(https?://[^\s"\'<>\\]+)', e)
dlphp, direct = m.group(0), m.group(1)

def probe(label, url, headers):
    try:
        r = s.get(url, headers={**headers, "Range": "bytes=2000000-2001023"},
                  timeout=30, stream=True, allow_redirects=False)
        loc = r.headers.get("location")
        print(f"{label:34s} {r.status_code} cr={r.headers.get('content-range')} "
              f"cl={str(r.headers.get('content-length'))[:14]} loc={loc[:70] if loc else ''}")
        r.close()
    except Exception as ex:
        print(label, "ERR", type(ex).__name__)

probe("guset Chrome", direct, {"User-Agent": UA, "Referer": "https://fastdl.zip/"})
probe("guset bare", direct, {})
probe("guset Chrome mobile", direct, {"User-Agent": "Mozilla/5.0 (Linux; Android 13)"})
# follow dl.php WITHOUT redirects, capture its own status/headers
d = s.get(dlphp, headers={"User-Agent": UA, "Referer": fastdl[:fastdl.index('/', 8)]+"/", "Range": "bytes=0-1023"},
          timeout=30, stream=True, allow_redirects=False)
print("dl.php no-follow:", d.status_code, "loc=", (d.headers.get('location') or '')[:90], "refresh=", d.headers.get('refresh'))
d.close()

# Is there an alternate Google range-capable endpoint? Try replacing host.
for alt in ["https://doc-10-b4-docs.googleusercontent.com", "https://drive.google.com/uc?export=download"]:
    print("noted", alt)
