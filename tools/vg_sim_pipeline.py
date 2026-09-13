"""Simulate the EXACT Vegamovies v2 pipeline (parseDetail -> expandAll ->
familyEpisodeCount -> episode rows) in Python against live pages, so we can see
the episode rows and link labels the plugin produces. Kotlin-identical logic."""
import io, re, sys, json, base64, urllib.parse, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
S = requests.Session(); S.headers.update({"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})
S.verify = False

GENXFM = re.compile(r"""https?://[a-z0-9.\-]*nexdrive\.[a-z]{2,10}/genxfm[^\s"'<>\\]+""", re.I)
DOWNLOAD_HEADING = re.compile(r"""(?i)(\d{3,4}p\b|4K|WEB[\s-]?DL|WEBRip|Blu\s?Ray|BD-?Rip|HD-?Rip|DVDRip|x26[45]|HEVC|AVC\b|\d+(?:\.\d+)?\s?(?:GB|MB)(?:/|\b)|/ZiP|/ZIP|\bZIP\b|Batch|Season\s*\d|\bPack\b|Complete|DUAL\s*[- ]?AUDIO|HINDI|TAMIL|TELUGU|\bEP(?:\.|ISODE)?\s*\d|S\d{1,2}[\s._-]?E\d{1,3})""")
FASTDL = re.compile(r"""https?://fastdl\.[a-z]{2,10}/embed(?:\.php)?\?download=[A-Za-z0-9_\-]+""", re.I)
VCLOUD = re.compile(r"""https?://vcloud\.[a-z]{2,10}/[A-Za-z0-9_\-]{6,}""")
SEASON_RE = re.compile(r"""(?i)season\s*(\d{1,2})""")
PACK = re.compile(r"""(?i)\b(batch|pack|zip|complete|all\s*episodes?)\b""")

def kindFromChip(c):
    lc = c.lower()
    if "g-direct" in lc or "g-drive" in lc or "direct" in lc: return "G-Drive"
    if "v-cloud" in lc: return "V-Cloud"
    if "batch" in lc or "zip" in lc: return "Batch/Zip"
    return ""

def parse_detail(html):
    groups = []
    heading = ""
    anchors = []
    # walk ALL elements in doc order: headings h1-h6, anchors with genxfm href
    for m in re.finditer(r'<h([1-6])[^>]*>(.*?)</h\1>|<a\b[^>]*href="([^"]*genxfm[^"]*)"[^>]*>(.*?)</a>', html, re.S | re.I):
        if m.group(1):
            t = re.sub(r"<[^>]+>", " ", m.group(2))
            t = re.sub(r"\s+", " ", t).strip()
            t = t.replace("&#8211;", "-").replace("&#8230;", "...").replace("&amp;", "&")
            if t and len(t) < 220:
                if anchors:
                    groups.append((heading, list(anchors)))
                    anchors = []
                heading = t if DOWNLOAD_HEADING.search(t) else ""
        else:
            href = m.group(3).rstrip("/") + "/"
            chip = re.sub(r"<[^>]+>", " ", m.group(4))
            chip = re.sub(r"\s+", " ", chip).strip()
            if heading:  # Kotlin: DlLink kind computed later; heading may be ""
                pass
            anchors.append((href, chip, heading))
    if anchors:
        groups.append((heading, list(anchors)))
    out = []
    for h, a in groups:
        if a and h:
            out.append((h, a))
    merged = {}
    order = []
    for h, a in out:
        if h not in merged: merged[h] = []; order.append(h)
        seen = {x[0] for x in merged[h]}
        for x in a:
            if x[0] not in seen:
                merged[h].append(x); seen.add(x[0])
    return [(h, merged[h]) for h in order]

def expand(gurl, ref):
    h = {"Referer": ref}
    html = S.get(gurl, headers=h, timeout=25).text
    ti = re.search(r"<h1[^>]*>(.*?)</h1>", html, re.S | re.I)
    title = re.sub(r"<[^>]+>", " ", ti.group(1)).strip() if ti else None
    links = []
    for i, m in enumerate(FASTDL.findall(html)): links.append(("G-Drive", m, i))
    for i, m in enumerate(VCLOUD.findall(html)): links.append(("V-Cloud", m, i))
    return links, title

def quality_of(heading):
    m = re.search(r"(?i)\b(2160p|1080p|720p|480p|4K)\b", heading or "")
    return m.group(1) if m else "?"

if __name__ == "__main__":
    post = sys.argv[1]
    html = S.get(post, timeout=25).text
    groups = parse_detail(html)
    season_groups = {}
    order = []
    for h, a in groups:
        ms = SEASON_RE.search(h)
        s = int(ms.group(1)) if ms else 1
        if s not in season_groups: season_groups[s] = []; order.append(s)
        season_groups[s].append((h, a))
    for s in order:
        glist = season_groups[s]
        expanded = []
        for h, a in glist:
            concrete = []
            for gurl, chip, hd in a:
                ck = kindFromChip(chip)
                links, title = expand(gurl, post)
                if not links:
                    concrete.append(("GATE", gurl, 0, hd))
                else:
                    for k, u, i in links:
                        kk = ck or k
                        concrete.append((kk, u, i, hd))
                    print(f"  [expand] {h[:45]!r} gate={gurl[-14:]} chip={chip[:18]!r} -> "
                          f"{[(k, i) for k, _, i in links][:3]}... n={len(links)} title_tag={(title or '')[-14:]!r}")
            expanded.append((h, concrete))
        # familyEpisodeCount
        maxEps = 0
        for h, c in expanded:
            if PACK.search(h): continue
            fams = {}
            for k, u, i, hd in c:
                if k == "Batch/Zip": continue
                fams[k] = max(fams.get(k, 0), i + 1)
            me = max(fams.values()) if fams else 0
            print(f"  [count] {h[:48]!r} families={fams}")
            maxEps = max(maxEps, me)
        print(f"SEASON {s}: maxEps={maxEps}")
        rows = {}
        if maxEps > 1:
            for i in range(maxEps):
                links_for_ep = []
                for h, c in expanded:
                    if PACK.search(h): continue
                    for k, u, idx, hd in c:
                        if idx == i and k != "Batch/Zip":
                            links_for_ep.append((k, hd))
                rows[i + 1] = links_for_ep
            for ep, ls in sorted(rows.items()):
                q = quality_of(ls[0][1]) if ls else "-"
                label_names = [f"{k} {quality_of(h)}" for k, h in ls]
                print(f"    EP {ep}: primary={q} links={label_names[:6]}{'...' if len(label_names)>6 else ''} count={len(ls)}")
