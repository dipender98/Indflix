import re, requests, urllib3, json
urllib3.disable_warnings()
s = requests.Session(); s.verify = False
s.headers.update({"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/138.0.0.0"})
t = s.get("https://new2.vegamovies.futbol/?s=avengers", timeout=20).text
m = re.search(r'const CONFIG\s*=\s*\{[\s\S]{0,600}?\}', t)
print(m.group(0) if m else "CONFIG not found")
cfg = None
if m:
    mm = re.search(r'proxyUrl\s*:\s*["\']([^"\']+)', m.group(0))
    pm = re.search(r'popularUrl\s*:\s*["\']([^"\']+)', m.group(0))
    if mm:
        base = "https://new2.vegamovies.futbol"
        u = mm.group(1)
        if u.startswith("/"): u = base + u
        from urllib.parse import urlparse, parse_qs, urlencode, urlunparse
        p = urlparse(u)
        q = parse_qs(p.query)
        q["q"] = ["avengers"]; q["page"] = ["1"]
        api = urlunparse((p.scheme, p.netloc, p.path, "", urlencode({k: v[0] for k, v in q.items()}), ""))
        print("\nAPI:", api)
        r = s.get(api, timeout=20)
        print("status", r.status_code)
        try:
            d = r.json()
            hits = d.get("hits", [])
            print("hits:", len(hits), "total:", d.get("total"), d.get("nbHits"))
            if hits: print(json.dumps(hits[0], indent=1)[:1500])
        except Exception as e:
            print("not json:", r.text[:400])
