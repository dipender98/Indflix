"""Dump the ordered sequence of (heading | chip | anchor) on series pages,
so label pairing + server chips are modeled on real markup. Also probe
Cinemeta (Stremio's keyless CDN metadata) for a series with IMDB id."""
import io, re, sys, json, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from bs4 import BeautifulSoup

urllib3.disable_warnings()
s = requests.Session(); s.verify = False
s.headers.update({"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/138.0.0.0"})

for slug in ["https://new2.vegamovies.futbol/download-my-bias-my-boss-season-1-hindi-dubbed-series-480p-720p-1080p-web-dl/"]:
    t = s.get(slug, timeout=25).text
    soup = BeautifulSoup(t, "html.parser")
    content = soup.find("div", class_="entry-content") or soup.body
    print("=" * 90); print(slug)
    n = 0
    for el in content.find_all(["h1","h2","h3","h4","h5","h6","a","strong","span","p","br"]):
        if el.name == "a" and "genxfm" not in (el.get("href") or ""): continue
        txt = el.get_text(" ", strip=True)
        if el.name.startswith("h") and txt:
            print(f"  HEAD <{el.name}> {txt[:110]}")
        elif el.name == "a":
            print(f"  ANCHOR {el['href'][:62]}  text={txt[:30]!r}")
        elif el.name in ("span","strong","p") and txt and "genxfm" not in str(el.get_text()):
            if re.search(r"(?i)direct|cloud|batch|zip|pixel|drive|server|\d{3,4}p\]", txt) and len(txt) < 130:
                print(f"    chip <{el.name}> {txt[:120]}")
        n += 1
        if n > 400: break

# Cinemeta probe: Crew Girl Netflix 2026 — find its imdb via TMDB search quickly, then cinemeta
print("=" * 90)
tmdb = s.get("https://api.themoviedb.org/3/search/multi?api_key=e6333b32409e02a4a6eba6fb7ff866bb&query=Crew+Girl&include_adult=false", timeout=12).json()
hits = [r for r in tmdb.get("results", []) if r.get("media_type") == "tv"]
print("tmdb tv hits:", [(h["id"], h["name"], (h.get("first_air_date") or "")[:4]) for h in hits[:4]])
imdb = None
if hits:
    d = s.get(f"https://api.themoviedb.org/3/tv/{hits[0]['id']}?api_key=e6333b32409e02a4a6eba6fb7ff866bb", timeout=12).json()
    imdb = d.get("external_ids", {}).get("imdb_id") or None
print("imdb:", imdb)
if not imdb:  # use a known series instead to validate cinemeta shape
    imdb = "tt0944947"
cm = s.get(f"https://v3-cinemeta.strem.io/meta/series/{imdb}.json", timeout=12)
print("cinemeta status", cm.status_code)
j = cm.json()
vids = j.get("videos", [])[:4]
print(" videos:", [v.get("title","")[:60] for v in vids])
print(" keys:", list(j.keys()))
print(" background:", str(j.get("background"))[:80])
