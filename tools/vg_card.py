import re, requests, urllib3
urllib3.disable_warnings()
s = requests.Session(); s.verify = False
s.headers.update({"User-Agent": "Mozilla/5.0 Chrome/138"})
t = s.get("https://new2.vegamovies.futbol/dual-audio-movies/", timeout=20).text
idx = [m.start() for m in re.finditer(r'class="poster-card"', t)]
print("at:", idx[:5], "total", len(idx))
i = idx[min(4, len(idx)-1)]
print(t[i-120:i + 1600])



