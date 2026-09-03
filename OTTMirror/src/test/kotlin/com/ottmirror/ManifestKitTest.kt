package com.ottmirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestKitTest {

    @Test
    fun parseMaster_emptyReturnsNull() {
        assertNull(ManifestKit.parseMaster(""))
        assertNull(ManifestKit.parseMaster(null))
        assertNull(ManifestKit.parseMaster("#EXTM3U\n#EXTINF:5,test\nfile.ts"))
    }

    @Test
    fun parseMaster_singleVariant() {
        val master = """
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,CODECS="avc1.64001f,mp4a.40.2"
1080p.m3u8
        """.trimIndent()
        val result = ManifestKit.parseMaster(master, "https://cdn.example.com/")
        assertNotNull(result)
        assertEquals(1, result.variants.size)
        assertEquals(1080, result.variants[0].height)
        assertEquals(2_000_000L, result.variants[0].bandwidth)
        assertEquals("https://cdn.example.com/1080p.m3u8", result.variants[0].url)
    }

    @Test
    fun parseMaster_multipleVariants() {
        val master = """
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
360p.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
720p.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
1080p.m3u8
        """.trimIndent()
        val result = ManifestKit.parseMaster(master, "https://cdn.example.com/")
        assertNotNull(result)
        assertEquals(3, result.variants.size)
        assertEquals(1080, ManifestKit.bestHeight(result.variants))
    }

    @Test
    fun parseMaster_withAudioAndSubtitles() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Hindi",LANGUAGE="hi",URI="hindi.m3u8",DEFAULT=NO,AUTOSELECT=NO
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",URI="english.m3u8",DEFAULT=YES,AUTOSELECT=YES
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Tamil",LANGUAGE="ta",URI="tamil.m3u8",DEFAULT=NO,AUTOSELECT=NO
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs-en.vtt",DEFAULT=YES
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Hindi",LANGUAGE="hi",URI="subs-hi.vtt",DEFAULT=NO
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.64001f,mp4a.40.2",AUDIO="audio",SUBTITLES="subs"
1080p.m3u8
        """.trimIndent()
        val result = ManifestKit.parseMaster(master, "https://cdn.example.com/")
        assertNotNull(result)
        assertTrue(result.isMultiAudio, "expected multi-audio")
        assertTrue(result.hasSubtitles, "expected subtitles")
        assertEquals(3, result.audio.size)
        assertEquals(2, result.subtitles.size)
        assertEquals("Hindi", result.audio[0].name)
        // Hindi + English dual audio must be detected
        assertTrue(ManifestKit.hasHindiEnglishAudio(result), "expected Hi+En dual audio")
    }

    @Test
    fun hasHindiEnglishAudio_falseWhenMissingOne() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a1",NAME="English",LANGUAGE="en",URI="en.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a1",NAME="Tamil",LANGUAGE="ta",URI="ta.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="a1"
1080p.m3u8
        """.trimIndent()
        val result = ManifestKit.parseMaster(master, "https://cdn.example.com/")
        assertNotNull(result)
        // English+Tamil, no Hindi → NOT Hindi+English dual
        assertFalse(ManifestKit.hasHindiEnglishAudio(result), "expected false (no Hindi)")
    }

    @Test
    fun hasHindiEnglishAudio_falseWhenNoAudioRenditions() {
        val master = """
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
360p.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
1080p.m3u8
        """.trimIndent()
        val result = ManifestKit.parseMaster(master, "https://cdn.example.com/")
        assertNotNull(result)
        assertFalse(ManifestKit.hasHindiEnglishAudio(result), "expected false (muxed audio only)")
    }

    @Test
    fun audioPriority_hindiOnly() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Hindi",LANGUAGE="hi",URI="hi.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio"
1080p.m3u8
        """.trimIndent()
        val result = ManifestKit.parseMaster(master, "https://cdn.example.com/")
        assertNotNull(result)
        assertEquals(4, ManifestKit.audioPriority(result))
    }

    @Test
    fun audioPriority_hindiEnglish() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Hindi",LANGUAGE="hi",URI="hi.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",URI="en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio"
1080p.m3u8
        """.trimIndent()
        val result = ManifestKit.parseMaster(master, "https://cdn.example.com/")
        assertNotNull(result)
        assertEquals(3, ManifestKit.audioPriority(result))
    }

    @Test
    fun audioPriority_originalOnly() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Original",LANGUAGE="ja",URI="orig.m3u8",DEFAULT=YES
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio"
1080p.m3u8
        """.trimIndent()
        val result = ManifestKit.parseMaster(master, "https://cdn.example.com/")
        assertNotNull(result)
        assertEquals(2, ManifestKit.audioPriority(result))
    }

    @Test
    fun audioPriority_englishOnly() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",URI="en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio"
1080p.m3u8
        """.trimIndent()
        val result = ManifestKit.parseMaster(master, "https://cdn.example.com/")
        assertNotNull(result)
        assertEquals(1, ManifestKit.audioPriority(result))
    }

    @Test
    fun audioPriority_otherOnly() {
        val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Tamil",LANGUAGE="ta",URI="ta.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="audio"
1080p.m3u8
        """.trimIndent()
        val result = ManifestKit.parseMaster(master, "https://cdn.example.com/")
        assertNotNull(result)
        assertEquals(0, ManifestKit.audioPriority(result))
    }

    @Test
    fun isMaster_detectsMaster() {
        assertTrue(ManifestKit.isMaster("#EXT-X-STREAM-INF:BANDWIDTH=1000\nfile.m3u8"))
        assertFalse(ManifestKit.isMaster("#EXTM3U\n#EXTINF:5\nfile.ts"))
        assertFalse(ManifestKit.isMaster(null))
    }

    @Test
    fun parseAttrs_handlesQuotedAndUnquoted() {
        val attrs = ManifestKit.parseAttrs("""BANDWIDTH=2000000,RESOLUTION=1920x1080,CODECS="avc1.64001f,mp4a.40.2"""")
        assertEquals("2000000", attrs["BANDWIDTH"])
        assertEquals("1920x1080", attrs["RESOLUTION"])
        assertEquals("avc1.64001f,mp4a.40.2", attrs["CODECS"])
    }

    @Test
    fun parseMpd_basic() {
        val mpd = """
<?xml version="1.0" encoding="utf-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
  <Period>
    <AdaptationSet mimeType="video/mp4">
      <Representation id="1" width="640" height="360" bandwidth="500000" codecs="avc1.4d401e"/>
      <Representation id="2" width="1280" height="720" bandwidth="1500000" codecs="avc1.4d401f"/>
      <Representation id="3" width="1920" height="1080" bandwidth="5000000" codecs="avc1.640028"/>
    </AdaptationSet>
    <AdaptationSet mimeType="audio/mp4">
      <Representation id="4" bandwidth="128000" codecs="mp4a.40.2"/>
    </AdaptationSet>
  </Period>
</MPD>
        """.trimIndent()
        val reps = ManifestKit.parseMpd(mpd)
        assertEquals(3, reps.size)
        assertEquals(1080, reps[2].height)
        assertEquals(5_000_000L, reps[2].bandwidth)
    }

    @Test
    fun qualityLabel_generatesCorrectLabels() {
        assertEquals("4K", ManifestKit.qualityLabel(2160))
        assertEquals("1080p", ManifestKit.qualityLabel(1080))
        assertEquals("720p", ManifestKit.qualityLabel(720))
        assertEquals("480p", ManifestKit.qualityLabel(480))
        assertEquals("Auto", ManifestKit.qualityLabel(0))
        assertEquals("Auto", ManifestKit.qualityLabel(-1))
    }

    @Test
    fun urlKey_deduplicates() {
        assertEquals(ManifestKit.urlKey("https://cdn.example.com/path/file.m3u8?token=abc"),
            ManifestKit.urlKey("https://cdn.example.com/path/file.m3u8?token=xyz"))
        assertFalse(ManifestKit.urlKey("https://cdn.example.com/a.m3u8") ==
            ManifestKit.urlKey("https://cdn2.example.com/a.m3u8"))
    }

    @Test
    fun resolveUrl_handlesAllCases() {
        assertEquals("https://cdn.ex/file.m3u8", ManifestKit.resolveUrl("https://cdn.ex/", "file.m3u8"))
        assertEquals("https://cdn.ex/file.m3u8", ManifestKit.resolveUrl("https://cdn.ex/", "/file.m3u8"))
        assertEquals("https://other.ex/file.m3u8", ManifestKit.resolveUrl("https://cdn.ex/", "https://other.ex/file.m3u8"))
        assertEquals("https://other.ex/file.m3u8", ManifestKit.resolveUrl("https://cdn.ex/", "//other.ex/file.m3u8"))
    }
}