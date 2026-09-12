"""Dump heading/anchor structure of series genxfm pages (G-Direct 480p,
V-Cloud 480p) — do links run in episode order? Are there per-episode headings?"""
import io, re, sys, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
s = requests.Session(); s.verify = False
H = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/138.0.0.0",
     "Referer": "https://new2.vegamovies.futbol/"}

for name, u in {"G-Direct 480p": "https://nexdrive.fit/genxfm784776500208/",
                "V-Cloud 480p": "https://nexdrive.fit/genxfm784776500207/"}.items():
    t = s.get(u, headers=H, timeout=25).text
    print("=" * 90); print(name, u)
    # ordered tokens: headings and download links
    seq = re.findall(r'<h([1-6])[^>]*>([^<]{0,110})</h\1>|href="(https?://(?:fastdl|vcloud|hubcloud|nexdrive)[^"]{0,80})"', t)
    eps = 0
    for lvl, ht, href in seq:
        if lvl:
            ht = ht.strip()
            if ht and not ht.startswith(("Vegamovies", "Download Links")):
                print(f"  H{lvl}: {ht[:100]}")
        elif href:
            eps += 1
            print(f"     LINK[{eps}]: {href[:75]}")
    print("  total links:", eps)
