package Test

import com.indstream.ManifestKit
import com.indstream.StreamEngine
import com.indstream.SubtitleKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleKitTest {

    private fun json(text: String) = org.json.JSONObject(text)

    @Test
    fun fromMaster_resolvesRelativeRenditions() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Hindi",LANGUAGE="hi",URI="/subs/hi.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,SUBTITLES="subs"
            1080p.m3u8
        """.trimIndent()
        val parsed = ManifestKit.parseMaster(master, "https://cdn.example.com/master.m3u8")
        val tracks = SubtitleKit.fromMaster(parsed, "https://cdn.example.com/master.m3u8")
        assertEquals(2, tracks.size)
        assertEquals("https://cdn.example.com/subs/en.m3u8", tracks[0].url)
        assertEquals("https://cdn.example.com/subs/hi.m3u8", tracks[1].url)
        assertEquals("en", tracks[0].language)
        assertEquals("hi", tracks[1].language)
    }

    @Test
    fun fromJson_findsCaptionArrayAtAnyDepth() {
        val root = json(
            """
            {
              "stream": {
                "captions": [
                  {"language": "English", "url": "https://subs.example.com/en.srt"},
                  {"language": "Hindi", "url": "/subs/hi.vtt", "type": "vtt"}
                ],
                "qualities": {"1080": {"url": "https://cdn.example.com/1080.mp4"}}
              }
            }
            """.trimIndent()
        )
        val tracks = SubtitleKit.fromJson(root, "https://api.example.com/title/1")
        assertEquals(2, tracks.size)
        assertEquals("English", tracks[0].language)
        assertEquals("https://subs.example.com/en.srt", tracks[0].url)
        assertEquals("https://api.example.com/subs/hi.vtt", tracks[1].url)
    }

    @Test
    fun fromJson_findsFileAndNameShape() {
        val root = json(
            """
            {"result": {"track": [
              {"name": "English", "file": "https://subs.example.com/en.srt"},
              {"name": "Tamil", "file": "https://subs.example.com/ta.srt"}
            ]}}
            """.trimIndent()
        )
        val tracks = SubtitleKit.fromJson(root, "https://api.example.com/")
        assertEquals(listOf("English", "Tamil"), tracks.map { it.language })
    }

    @Test
    fun fromJson_ignoresAudioPlaylistsAndVideoUrls() {
        val root = json(
            """
            {
              "sources": [{"url": "https://cdn.example.com/master.m3u8"}],
              "tracks": [
                {"file": "https://cdn.example.com/audio-en.m3u8", "label": "English"},
                {"file": "https://cdn.example.com/audio-hi.m3u8", "label": "Hindi"}
              ]
            }
            """.trimIndent()
        )
        assertTrue(SubtitleKit.fromJson(root, "https://api.example.com/").isEmpty())
    }

    @Test
    fun fromJson_acceptsStringArrays() {
        val root = json("""{"subtitles": ["https://subs.example.com/a.srt", "https://subs.example.com/b.ass"]}""")
        val tracks = SubtitleKit.fromJson(root, "https://api.example.com/")
        assertEquals(2, tracks.size)
        assertEquals("Subtitle", tracks[0].language)
    }

    @Test
    fun fromJson_dedupesByUrl() {
        val root = json(
            """
            {
              "captions": [{"language": "English", "url": "https://subs.example.com/en.srt"}],
              "subtitles": [{"language": "English", "url": "https://subs.example.com/en.srt"}]
            }
            """.trimIndent()
        )
        assertEquals(1, SubtitleKit.fromJson(root, "https://api.example.com/").size)
    }

    @Test
    fun merge_keepsExistingOrderAndDropsDuplicates() {
        val first = listOf(SubtitleKit.Track("English", "https://subs.example.com/en.srt"))
        val second = listOf(
            SubtitleKit.Track("Hindi", "https://subs.example.com/hi.srt"),
            SubtitleKit.Track("English", "https://subs.example.com/en.srt"),
        )
        val merged = SubtitleKit.merge(first, second)
        assertEquals(listOf("English", "Hindi"), merged.map { it.language })
    }

    @Test
    fun subtitleFile_carriesServerLabel() {
        val file = StreamEngine.subtitleFileOf("Server A", SubtitleKit.Track("en", "https://subs.example.com/en.srt"))
        assertEquals("English (Server A)", file.lang)
        assertEquals("https://subs.example.com/en.srt", file.url)
    }

    @Test
    fun withJsonSubtitles_attachesTracksToEveryStream() {
        val streams = listOf(
            StreamEngine.RawStream("a", "Server A", "https://cdn.example.com/a.mp4", false),
            StreamEngine.RawStream("a", "Server A", "https://cdn.example.com/b.mp4", false),
        )
        val payload = json("""{"captions": [{"language": "English", "url": "https://subs.example.com/en.srt"}]}""")
        val result = StreamEngine.withJsonSubtitles(streams, payload, "https://api.example.com/")
        assertEquals(2, result.size)
        assertTrue(result.all { it.subtitles.size == 1 })
        assertEquals("https://subs.example.com/en.srt", result[0].subtitles[0].url)
    }
}
