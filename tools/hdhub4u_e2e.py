"""End-to-end pipeline test: 3 titles through the exact plugin flow.
search (typesense) -> load (post parse) -> loadLinks (resolve every button)
-> playable check (Range GET on the final URL). Mirrors HDHub4uProvider.
"""
import re
import sys

sys.path.insert(0, "tools")
import hdhub4u_probe as h

QUERIES = ["haiwaan", "salmokji", "the revolutionaries"]


def playable_check(s, url, referer):
    try:
        r = s.get(url, timeout=25, allow_redirects=True,
                  headers={"Range": "bytes=0-127", "Referer": referer, "User-Agent": h.UA})
        ct = r.headers.get("Content-Type", "")
        cd = r.headers.get("Content-Disposition", "")[:60]
        cr = r.headers.get("Content-Range", "")[:40]
        return f"HTTP {r.status_code} {ct[:30]} {cr} {cd}"
    except Exception as e:
        return f"ERR {type(e).__name__}: {str(e)[:60]}"


def main():
    s = h.sess()
    live = h.resolve_live(s)
    if not live:
        print("FATAL: no live domain"); return 1
    print(f"LIVE: {live}\n")

    ok_titles = 0
    for q in QUERIES:
        print(f"===== TITLE '{q}' =====")
        # 1) search — the site's own typesense backend (Referer = live site)
        r = s.get(h.TYPESENSE, timeout=15, headers={"Referer": live + "/search.html"},
                  params={"q": q, "query_by": "post_title,category,stars,director,imdb_id",
                          "query_by_weights": "4,2,2,2,4", "sort_by": "sort_by_date:desc",
                          "limit": "5", "highlight_fields": "none", "page": "1"})
        if r.status_code != 200:
            print(f"  search HTTP {r.status_code} — FAIL"); continue
        hits = r.json().get("hits", [])
        if not hits:
            print("  search: 0 hits — FAIL"); continue
        doc = hits[0]["document"]
        title = doc["post_title"]
        permalink = doc["permalink"]
        print(f"  hit: {title[:80]}")
        print(f"       {permalink}")

        # 2) load — post page parse (mirror of parsePostLinks)
        html = s.get(permalink, timeout=15).text
        links = h.post_links(html, live)
        print(f"  post links: {len(links)}")

        # 3) loadLinks — resolve every button (arrival order like the plugin)
        emitted = 0
        for lab, href in links:
            host = h.host_of(href)
            res = h.RESOLVERS.get(host)
            if not res:
                continue
            try:
                out = res(s, href)
            except Exception as e:
                out = f"ERR {type(e).__name__}: {e}"
            if out is None:
                out = []
            if isinstance(out, str):  # bare URL (hubcdn) or error marker
                out = [out]
            for u in out:
                if u.startswith("["):
                    print(f"    [{lab[:26]:26}] {host:16} -> {u[:90]}")
                    continue
                if u.startswith("SUB "):
                    print(f"    [{lab[:26]:26}] {host:16} -> {u[:110]}")
                    continue
                stat = playable_check(s, u, "https://hdstream4u.com/")
                good = stat.startswith(("HTTP 200", "HTTP 206"))
                if good:
                    emitted += 1
                print(f"    [{'OK' if good else 'BAD'}] [{lab[:18]:18}] {host:16} -> {stat}")
        print(f"  => {emitted} playable source(s)\n")
        if emitted > 0:
            ok_titles += 1

    print(f"RESULT: {ok_titles}/3 titles produced at least one playable link")
    return 0 if ok_titles == 3 else 2


if __name__ == "__main__":
    sys.exit(main())
