"""What's inside each of a series post's three genxfm anchors (G-Direct,
V-Cloud, Batch/Zip)? List fastdl/vcloud/hubcloud/drive hosts per page."""
import io, re, sys, requests, urllib3
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
urllib3.disable_warnings()
s = requests.Session(); s.verify = False
H = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/138.0.0.0",
     "Referer": "https://new2.vegamovies.futbol/"}

pages = {
    "G-Direct 480p": "https://nexdrive.fit/genxfm784776500208/",
    "V-Cloud 480p": "https://nexdrive.fit/genxfm784776500207/",
    "Batch/Zip 480p": "https://nexdrive.fit/genxfm784776507313/",
    "G-Direct 720p": "https://nexdrive.fit/genxfm784776500214/",
    "Batch 720p": "https://nexdrive.fit/genxfm784776507314/",
}
for name, u in pages.items():
    try:
        t = s.get(u, headers=H, timeout=25).text
        fastdl = re.findall(r'https://fastdl\.[a-z]+/embed\?download=\w+', t)
        vc = re.findall(r'https://vcloud\.[a-z]+/[a-z0-9]+', t)
        zipf = re.findall(r'https?://[^\s"\'<>]*(?:hubcloud|zip|\.zip)[^\s"\'<>]{0,40}', t)[:3]
        gdir = re.findall(r'G-Direct|Google Drive|drive\.google[^\s"\'<>]{0,40}', t)[:3]
        # the post title heading
        h = re.search(r'<title>([^<]+)</title>', t)
        print(f"{name:16s} {u[-16:]}  fastdl={len(fastdl)} vcloud={len(vc)} ziplike={zipf[:1]} gdir={bool(gdir)}  title={h.group(1)[:60] if h else '?'}")
    except Exception as e:
        print(name, "ERR", str(e)[:80])
