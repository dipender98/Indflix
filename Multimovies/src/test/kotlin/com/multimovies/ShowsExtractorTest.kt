package com.multimovies

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the api.shows.st (111Movies) JSON parsing. */
class ShowsExtractorTest {

    private val sampleJson = """
        {
          "meta": {"title": "Rush"},
          "subtitles": [
            {"label": "English", "file": "https://cache.vdrk.site/v1/vtt/movie/96721/English.vtt", "type": "vtt"},
            {"label": "Hindi", "file": "https://cache.vdrk.site/v1/vtt/movie/96721/Hindi.vtt", "type": "vtt"}
          ],
          "source": {
            "source": "vidapi",
            "label": "VidAPI",
            "url": "https://a2.shows.st/api?d=blob&internal_token=v1.1.internal.vidapi.2.abc",
            "manifest": "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=670703,RESOLUTION=640x266\nhttps://d.shows.st/api?d=rend1"
          }
        }
    """.trimIndent()

    @Test
    fun `extracts the master playlist url from source`() {
        val json = JSONObject(sampleJson)
        assertEquals(
            "https://a2.shows.st/api?d=blob&internal_token=v1.1.internal.vidapi.2.abc",
            ShowsExtractor.parseSourceUrl(json)
        )
    }

    @Test
    fun `parses subtitle tracks`() {
        val subs = ShowsExtractor.parseSubtitleTracks(JSONObject(sampleJson))
        assertEquals(2, subs.size)
        assertEquals("English" to "https://cache.vdrk.site/v1/vtt/movie/96721/English.vtt", subs[0])
        assertEquals("Hindi" to "https://cache.vdrk.site/v1/vtt/movie/96721/Hindi.vtt", subs[1])
    }

    @Test
    fun `missing source url yields null`() {
        assertNull(ShowsExtractor.parseSourceUrl(JSONObject("""{"source":{}}""")))
        assertNull(ShowsExtractor.parseSourceUrl(JSONObject("""{}""")))
    }

    @Test
    fun `missing subtitles yields empty list`() {
        assertTrue(ShowsExtractor.parseSubtitleTracks(JSONObject("""{"source":{"url":"x"}}""")).isEmpty())
    }
}