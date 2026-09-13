"""Show the heading/anchor sequence INSIDE a gateway page so we can tell
whether fastdl and vcloud links are grouped by episode or by server."""
import io, re, sys, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/138.0.0.0 Safari/537.36"
S = requests.Session(); S.verify = False
S.headers.update({"User-Agent": UA, "Referer": "https://new2.vegamovies.futbol/"})

for u in sys.argv[1:]:
    t = S.get(u, timeout=30).text
    print("=" * 90); print("GATE:", u, "len", len(t))
    for m in re.finditer(
        r'<h([1-6])[^>]*>(.*?)</h\1>'
        r'|<a[^>]*href="(https?://(?:fastdl|vcloud)[^"]*)"[^>]*>(.*?)</a>',
        t, re.S,
    ):
        if m.group(1):
            ht = re.sub(r"<[^>]+>", " ", m.group(2))
            ht = re.sub(r"\s+", " ", ht).strip()
            if ht and len(ht) < 120:
                print(f"  H{m.group(1)}: {ht[:100]}")
        else:
            txt = re.sub(r"<[^>]+>", " ", m.group(4))
            txt = re.sub(r"\s+", " ", txt).strip()
            host = "F" if "fastdl" in m.group(3) else "V"
            print(f"    {host} [{txt[:34]}] ...{m.group(3)[-34:]}")
