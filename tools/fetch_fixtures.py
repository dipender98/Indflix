#!/usr/bin/env python3
"""fetch_fixtures.py (one-off) — download cinevood.loan ground truth into
tools/fixtures/cinevood/ so Kotlin parsers/test data can be written offline.
Idempotent-ish: overwrites. Prints status for the wp-json list/search forms
(the Task 1 verification item)."""
import os, re, ssl, sys, time, urllib.request, json

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
BASE = "https://cinevood.loan"
OUT = os.path.join(os.path.dirname(__file__), "fixtures", "cinevood")
os.makedirs(OUT, exist_ok=True)
CTX = ssl.create_default_context(); CTX.check_hostname=False; CTX.verify_mode=ssl.CERT_NONE

def get(url, timeout=25):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language":"en-US,en;q=0.9"})
    t0=time.time()
    with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
        data = r.read()
        return r.status, r.geturl(), round(time.time()-t0,2), data

def save(name, url):
    try:
        st, final, t, data = get(url)
        path = os.path.join(OUT, name)
        with open(path, "wb") as f: f.write(data)
        print(f'{st:>4} {t:>5}s {len(data):>8}B  {name:<32} {url[:75]}')
        return st, data
    except Exception as e:
        print(f'ERR  {"":>5}  {str(e)[:70]:<70} {name}')
        return 0, b""

def main():
    # 1. HTML listing + movie post + a series post
    save("home.html", BASE + "/")
    save("category_dual_audio.html", BASE + "/category/dual-audio-movies/")
    save("post_movie_toxic.html", BASE + "/download-toxic-a-fairytale-for-grown-ups-2026-hindi-movie/")
    save("post_series_chumbak.html", BASE + "/download-chumbak-season-1-hindi-netflix-complete-series-480p-720p-1080p/")
    save("post_series_heroes.html", BASE + "/download-heroes-season-1-4-hindi-english-series-480p-720p-1080p-web-dl/")
    # 2. wp-json FORMS — the key verification
    st,_ = save("wp_search.json", BASE + "/wp-json/wp/v2/posts?search=dhamaal&per_page=5&_fields=id,link,title,categories,featured_media")
    stE,_ = save("wp_search_embedded.json", BASE + "/wp-json/wp/v2/posts?search=dhamaal&per_page=5&_embed&_fields=id,link,title,categories,_embedded")
    st,_ = save("wp_post_65844_full.json", BASE + "/wp-json/wp/v2/posts/65844?_embed")
    save("wp_post_chumbak_full.json", BASE + "/wp-json/wp/v2/posts?slug=download-chumbak-season-1-hindi-netflix-complete-series-480p-720p-1080p&_embed")
    st2, d2 = save("wp_single.json", BASE + "/wp-json/wp/v2/posts/65844?_fields=id,link,title,categories")
    st3, d3 = save("wp_categories.json", BASE + "/wp-json/wp/v2/categories?per_page=100&_fields=id,slug,name,count&orderby=count&order=desc")
    save("wp_categories_p2.json", BASE + "/wp-json/wp/v2/categories?per_page=100&page=2&_fields=id,slug,name,count&orderby=count&order=desc")
    save("wp_taxonomies.json", BASE + "/wp-json/wp/v2/taxonomies")
    print("\nFORM SUPPORT CHECK:")
    print(f"  posts?search (list)      -> {st}")
    print(f"  posts?search&_embedded   -> {stE}  bytes={os.path.getsize(os.path.join(OUT,'wp_search_embedded.json')) if stE else '-'}")
    print(f"  posts/{'{id}'}              -> {st2}")
    print(f"  categories (list)        -> {st3}")
    # discover a series post url from home for later fixture
    d = open(os.path.join(OUT,"home.html"),"rb").read().decode("utf-8","ignore")
    ser = re.findall(r'href="([^"]*(?:season|[sS]\d{2})[^"]*)"', d)
    print("  series-like links on home:", [s.split('/')[-2] for s in ser[:5]])

if __name__ == "__main__":
    main()
