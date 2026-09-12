import re, sys, io, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
s = requests.Session(); s.verify = False
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/138.0.0.0"

# fastdl embed.php (series G-Direct link) — does it yield reurl?
e = s.get("https://fastdl.zip/embed.php?download=ZWWkCMAJw7ocuL9xDaapzH7hA",
          timeout=25, headers={"User-Agent": UA, "Referer": "https://nexdrive.fit/"}).text
print("embed.php len", len(e))
m = re.search(r'https?://fastdl\.[a-z]+/dl\.php\?link=(https?://[^\s"\'<>\\]+)', e)
print("reurl found:", bool(m))
if m:
    d = m.group(1)
    print("direct[:80]:", d[:80])
    r = s.get(d, headers={"User-Agent": UA, "Referer": "https://fastdl.zip/", "Range": "bytes=1000-1999"},
              timeout=30, stream=True, allow_redirects=True)
    print("GET Range ->", r.status_code, r.headers.get("content-range"), r.headers.get("content-type"))
    r.close()

# Batch/Zip genxfm page link type
t = s.get("https://nexdrive.fit/genxfm784776507313/", timeout=25,
          headers={"User-Agent": UA, "Referer": "https://new2.vegamovies.futbol/"}).text
print("zip page vcloud links:", len(re.findall(r"https://vcloud\.[a-z]+/", t)))
print("zip page fastdl links:", len(re.findall(r"https://fastdl\.[a-z]+/", t)))
print("zip page title:", re.search(r"<title>([^<]+)</title>", t).group(1)[:80])
