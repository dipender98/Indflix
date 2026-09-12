#!/usr/bin/env python3
"""
nexdrive_flow.py — deep-dive the nexdrive.fit genxfm -> direct file flow.

After the 10s countdown, the landing page exposes:
  - https://fastdl.zip/embed?download=<token>
  - https://vcloud.fit/<hex-id>
This script captures the page pre/post countdown, fetches the bundled JS
(cdn.jsdelivr.net/gh/vgmjs/assets/adminbar.js) and prints the code paths
that BUILD those URLs and what the embed page returns (final .mkv?).
"""

import re
import sys
import time

import requests
import urllib3

urllib3.disable_warnings()

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36")
TIMEOUT = 25

URL = "https://nexdrive.fit/genxfm7847765350/"
REF = "https://new2.vegamovies.futbol/"

s = requests.Session()
s.verify = False
s.headers.update({"User-Agent": UA, "Referer": REF})

# 1. Landing page script inventory.
r = s.get(URL, timeout=TIMEOUT)
print("stage1 status", r.status_code, "len", len(r.text))
html = r.text

# All external scripts + inline scripts touching download/fastdl/vcloud.
for m in re.finditer(r'<script[^>]{0,200}src=["\']([^"\']+)["\']', html):
    print("  script src:", m.group(1))
for m in re.finditer(r'(fastdl|vcloud|admin-ajax|genxfm|download)[^<>\n]{0,160}',
                     html, re.I):
    print("  ctx:", m.group(0)[:170].replace("\n", " "))

# 2. The custom adminbar.js — likely builds the URLs.
js_url = "https://cdn.jsdelivr.net/gh/vgmjs/assets/adminbar.js"
j = s.get(js_url, timeout=TIMEOUT)
print("\nadminbar.js status", j.status_code, "len", len(j.text))
print(j.text[:4000])
print("...")

# 3. Wait out the countdown, re-fetch, keep the token-bearing URLs.
time.sleep(12)
r2 = s.get(URL, timeout=TIMEOUT)
print("\nstage2 status", r2.status_code, "len", len(r2.text))
for pat in [r'https?://fastdl\.zip/embed\?download=[A-Za-z0-9]+',
            r'https?://vcloud\.fit/[a-z0-9]+']:
    print(" ", pat, "->", re.findall(pat, r2.text))

# 4. Follow the fastdl embed to see what it yields.
fdm = re.search(r'https?://fastdl\.zip/embed\?download=([A-Za-z0-9]+)', r2.text)
if fdm:
    embed = "https://fastdl.zip/embed?download=" + fdm.group(1)
    e = s.get(embed, timeout=TIMEOUT)
    print("\nfastdl embed status", e.status_code, "final", e.url, "len", len(e.text), "ct", e.headers.get("content-type"))
    for pat in [r'https?://[^\s"\'<>\\]+\.(?:mkv|mp4|m3u8)(?:\?[^\s"\'<>\\]*)?',
                r'<source[^>]{0,200}', r'file["\']?\s*[:=]\s*["\'][^"\']+',
                r'<a[^>]{0,200}download[^>]{0,200}>']:
        for mm in re.finditer(pat, e.text, re.I):
            print("   hit:", mm.group(0)[:200])
    # If the embed itself has an inner iframe, unwrap once.
    iframe = re.search(r'<iframe[^>]{0,200}src=["\']([^"\']+)["\']', e.text, re.I)
    if iframe:
        iu = iframe.group(1)
        if iu.startswith("//"): iu = "https:" + iu
        print("   iframe ->", iu)
        i2 = s.get(iu, timeout=TIMEOUT)
        print("   iframe status", i2.status_code, "len", len(i2.text))
        for pat in [r'https?://[^\s"\'<>\\]+\.(?:mkv|mp4|m3u8)(?:\?[^\s"\'<>\\]*)?',
                    r'atob\(["\'][A-Za-z0-9+/=]{8,}["\']\)']:
            for mm in re.finditer(pat, i2.text, re.I):
                print("     hit:", mm.group(0)[:160])

# 5. vcloud link — HEAD-like GET to see if it redirects to a file.
vcm = re.search(r'https?://vcloud\.fit/[a-z0-9]+', r2.text)
if vcm:
    v = s.get(vcm.group(0), timeout=TIMEOUT)
    print("\nvcloud status", v.status_code, "final", v.url, "ct", v.headers.get("content-type"), "len", len(v.text))
