"""Emulate CSX VCloud().getUrl on a real vcloud.fit link: token page -> buttons
-> FSL/Mega/10Gbps direct resolution. Also try hubcloud domain swap."""
import io, re, sys, base64, urllib.parse, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
S = requests.Session(); S.verify = False
S.headers.update({"User-Agent": UA})

def b64x2(v): return base64.b64decode(base64.b64decode(v)).decode(errors="replace")

def try_vcloud(vurl, label):
    print("=" * 80)
    print(label, vurl)
    r1 = S.get(vurl, timeout=25)
    html = r1.text
    print("  step1 len", len(html), "cookies:", dict(S.cookies) if S.cookies else "-")
    m = re.search(r"""var\s+url\s*=\s*atob\(\s*atob\(\s*['"]([A-Za-z0-9+/=]+)['"]""", html)
    if not m:
        # maybe script:containsData(url) with single atob or plain var url
        m1 = re.search(r"""var\s+url\s*=\s*['"]([^'"]+)['"]""", html)
        m2 = re.search(r"""atob\(\s*['"]([A-Za-z0-9+/=]{20,})['"]""", html)
        print("  no double atob. single var url:", (m1.group(1)[:60] if m1 else None),
              "| single atob candidate:", bool(m2))
        turl = m1.group(1) if m1 else (b64x2(m2.group(1)) if m2 else None)
    else:
        turl = b64x2(m.group(1))
    print("  token url:", turl[:100])
    if not turl: return
    r2 = S.get(turl, headers={"Referer": vurl}, timeout=25)
    html2 = r2.text
    print("  step2 status", r2.status_code, "len", len(html2), "final", r2.url[:90])
    hdr = re.search(r'<div class="card-header"[^>]*>(.*?)</div>', html2, re.S)
    size = re.search(r'<i id="size"[^>]*>(.*?)</i>', html2, re.S)
    print("  card-header:", re.sub(r"<[^>]+>", " ", hdr.group(1)).strip()[:70] if hdr else "-")
    print("  size:", size.group(1)[:20] if size else "-")
    btns = re.findall(r'<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>', html2, re.S)
    btns = [(h, re.sub(r"<[^>]+>", " ", t).strip()) for h, t in btns if "btn" in html2[html2.find(h)-120:html2.find(h)] or True]
    for href, txt in btns[:20]:
        if not (txt and ("ownload" in txt or "erver" in txt or "FSL" in txt or "Mega" in txt or "File" in txt or "Buzz" in txt or "Pixel" in txt or "10Gbps" in txt)):
            continue
        t = txt
        print(f"    BTN [{t[:34]}] {href[:120]}  (len={len(href)})")
        u = href if href.startswith("http") else ("https://" + r2.url.split("/")[2] + href)
        if "cloudflarestorage" in u:
            try:
                pr = S.get(u, headers={"Range": "bytes=0-65535"}, timeout=30, stream=True)
                print("      GET R2+Range:", pr.status_code, "ct:", pr.headers.get("content-type"),
                      "cr:", pr.headers.get("content-range"), "acc:", pr.headers.get("accept-ranges"))
                first = next(pr.iter_content(64), b"")
                print("      first8:", first[:8].hex())
                pr.close()
            except Exception as e:
                print("      R2 ERR", str(e)[:60])
        elif "10Gbps" in t:
            cur = u
            for _ in range(7):
                rr = S.head(cur, allow_redirects=False, timeout=15)
                if rr.status_code in (200, 301, 302, 307, 308):
                    loc = rr.headers.get("Location")
                    if not loc: break
                    cur = loc if loc.startswith("http") else urllib.parse.urljoin(cur, loc)
                else:
                    cur = None; break
            fin = cur
            if fin and "link=" in fin: fin = "https://" + fin.split("link=")[-1].split("?")[0] if False else fin.split("link=")[-1]
            print("      10Gbps ->", (fin or "?")[:120])
            if fin:
                try:
                    pr = S.get(fin, headers={"Range": "bytes=0-1023"}, timeout=20, stream=True)
                    print("        probe:", pr.status_code, pr.headers.get("content-type"), pr.headers.get("content-range"))
                    pr.close()
                except Exception as e:
                    print("        probe ERR", str(e)[:60])
            u = href if href.startswith("http") else ("https://" + turl.split("/")[2] + href)
            try:
                cur = u
                for _ in range(7):
                    rr = S.head(cur, allow_redirects=False, timeout=15, headers={"Referer": r2.url})
                    if rr.status_code in (200, 301, 302, 307, 308):
                        loc = rr.headers.get("Location")
                        if not loc: break
                        cur = loc if loc.startswith("http") else urllib.parse.urljoin(cur, loc)
                    else:
                        cur = None; break
                final = cur
                if final and "link=" in final: final = final.split("link=")[-1]
                print(f"    FOLLOW [{t[:15]}] ->", (final or "?")[:110])
                if final and final.startswith("http"):
                    pr = S.get(final, headers={"Range": "bytes=0-1023"}, timeout=25, stream=True)
                    ct = pr.headers.get("content-type")
                    cr = pr.headers.get("content-range")
                    acc = pr.headers.get("accept-ranges")
                    first = next(pr.iter_content(16), b"")
                    cont = "MKV" if first[:4] == b"\x1aE\xff\xa3" else ("MP4-box" if b"ftyp" in first[:12] else first[:6])
                    print(f"      probe GET-R: {pr.status_code} ct={ct} accept_ranges={acc} cr={cr} first={cont!r}")
                    pr.close()
            except Exception as e:
                print("    FOLLOW ERR", t[:15], str(e)[:60])

# S2 480p V-Cloud ep1 (Squid Game):
try_vcloud("https://vcloud.fit/b88ugmhwbj0jiyc", "SERIES S2 480 EP1")
# Movie gate vcloud (Endgame 480, fresh):
t = S.get("https://nexdrive.fit/genxfm7847765350/", headers={"Referer": "https://new2.vegamovies.futbol/"}, timeout=25).text
v = re.findall(r"https://vcloud\.[a-z]+\.[a-z]+/[A-Za-z0-9_\-]{6,}", t)
if v: try_vcloud(v[0], "MOVIE 480")
