#!/usr/bin/env python3
"""
nexdrive_probe.py — map the wire protocol behind Vegamovies' download
intermediary pages:  https://nexdrive.fit/genxfm{ID}/

Goal: discover how a genxfm landing page turns into a DIRECT file URL
(countdown + ajax/form/redirect), so Vegamovies' NexdriveResolver.kt can
replicate it.

    e:/Project/Indflix/.venv/Scripts/python.exe tools/nexdrive_probe.py

Probes one known-live sample first (Avengers Endgame 480p), then reports:
  - HTTP status + final URL of the landing page (redirect chain).
  - Every interesting in-page artifact: forms, ajax endpoints, data-* attrs,
    countdown JS, tokens, base64 blobs, links to file hosts.
  - What a POST/get to any discovered endpoint returns.
"""

import json
import re
import sys
import time
import urllib.parse

import requests
import urllib3

urllib3.disable_warnings()

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
TIMEOUT = 20

SAMPLES = [
    "https://nexdrive.fit/genxfm7847765350/",  # Avengers Endgame 480p
    "https://nexdrive.fit/genxfm7847765353/",  # 720p HEVC
    "https://nexdrive.fit/genxfm784776107220/",  # 2160p 4k HDR
]

# Patterns that usually hold the resolution endpoint / direct URL.
INTERESTING = [
    (r'<form[^>]{0,300}>', "form"),
    (r'action=["\'][^"\']+["\']', "form-action"),
    (r'\$.post\(\s*["\'][^"\']+["\']', "jq-post"),
    (r'\$.ajax\(\s*\{[^}]{0,200}', "jq-ajax"),
    (r'fetch\(["\'][^"\']+["\']', "fetch"),
    (r'/wp-json/[A-Za-z0-9_/\-\.]+', "wp-json"),
    (r'/api/[A-Za-z0-9_/\-\.]+', "api-path"),
    (r'admin-ajax\.php', "admin-ajax"),
    (r'\?[^"\']*action=[A-Za-z0-9_]+', "action-qs"),
    (r'data-[a-z\-]+=["\'][^"\']{1,120}["\']', "data-attr"),
    (r'setTimeout|setInterval|countdown', "countdown-js"),
    (r'window\.[A-Za-z_$]+\s*=\s*[^;]{1,120}', "window-var"),
    (r'https?://[^\s"\'<>\\]+\.(?:mkv|mp4|avi|mov|ts|m3u8)(?:\?[^\s"\'<>\\]*)?',
     "direct-file"),
    (r'<a\s+[^>]{0,200}(?:rel=["\']nofollow["\']|class=["\'][^"\']*download[^"\']*)[^>]*>',
     "dl-anchor"),
    (r'atob\(|base64', "encoded-js"),
]


def dump(tag, v, n=200):
    print(f"  [{tag}] {v[:n]}")


def probe(url):
    print("=" * 78)
    print("PROBE", url)
    s = requests.Session()
    s.verify = False
    s.headers.update({"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})

    r = s.get(url, timeout=TIMEOUT, allow_redirects=True)
    print("  status", r.status_code, "final", r.url)
    print("  redirects", [x.status_code for x in r.history])
    print("  body len", len(r.text), "ct", r.headers.get("content-type"))
    if r.status_code != 200:
        print("  body head:", r.text[:500])
    html = r.text
    for pat, tag in INTERESTING:
        for m in re.finditer(pat, html, re.I):
            dump(tag, m.group(0))

    # Wait through a countdown (if JS-driven), re-fetch, look again.
    print("  -- waiting 12s through any countdown --")
    time.sleep(12)
    r2 = s.get(url, timeout=TIMEOUT)
    for u in set(re.findall(r'https?://[^\s"\'<>\\]+', r2.text, re.I)):
        if u.startswith(url) or "nexdrive" in u:
            continue
        dump("url-after-wait", u)

    # Try a couple of plausible endpoints on the same host.
    host = "https://nexdrive.fit"
    for endpoint in ["/api/generate", "/generate", "/download", "/wp-admin/admin-ajax.php"]:
        rid = re.search(r'genxfm(\d+)', url)
        payload = {
            "id": rid.group(1) if rid else "",
            "hash": rid.group(1) if rid else "",
            "action": "gen_xfm",
            "url": url,
            "ref": url,
        }
        try:
            pr = s.post(host + endpoint, data=payload, timeout=TIMEOUT)
            print(" POST", endpoint, "action=gen_xfm", pr.status_code, pr.text[:160])
            pr = s.get(host + endpoint, params=payload, timeout=TIMEOUT)
            print(" GET ", endpoint, "action=gen_xfm", pr.status_code, pr.text[:160])
        except Exception as exc:
            print(" endpoint", endpoint, "->", exc)
    print()


if __name__ == "__main__":
    for s_url in SAMPLES[:1]:
        try:
            probe(s_url)
        except Exception as exc:
            print("ERR", s_url, exc)
