package com.multimovies

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostExtractorsTest {

    @Test
    fun `vixsrc global source movie and tv url builders`() {
        val vix = GlobalSources.list.first { it.name == "vixsrc.to" }
        assertEquals("https://vixsrc.to/movie/tt0944947", vix.buildUrl("tt0944947", null, null))
        assertEquals("https://vixsrc.to/tv/tt0944947/1/2", vix.buildUrl("tt0944947", 1, 2))
    }

    @Test
    fun `autoembed and 2embed tv url builders include season episode`() {
        val auto = GlobalSources.list.first { it.name == "autoembed" }
        assertEquals("https://player.autoembed.app/embed/tv/tt0944947/1/2", auto.buildUrl("tt0944947", 1, 2))

        val embed = GlobalSources.list.first { it.name == "2embed.cc" }
        assertEquals(
            "https://www.2embed.cc/embed/tv?imdb=tt0944947&s=1&e=2",
            embed.buildUrl("tt0944947", 1, 2)
        )
    }

    @Test
    fun `tmdb-based sources use tmdb id`() {
        val vid = GlobalSources.list.first { it.name == "vidlink.pro" }
        assertEquals("https://vidlink.pro/movie/123", vid.buildUrl("123", null, null))
        assertEquals("https://vidlink.pro/tv/123/1/2", vid.buildUrl("123", 1, 2))

        val multi = GlobalSources.list.first { it.name == "multiembed.mov" }
        assertEquals("https://multiembed.mov/?video_id=123&tmdb=1", multi.buildUrl("123", null, null))
    }

    @Test
    fun `SourceMetaCache round trips by data url`() {
        SourceMetaCache.clear()
        SourceMetaCache.put("http://example/episode/1x2", SourceMeta("tt0944947", "12345", 1, 2))
        val m = SourceMetaCache.get("http://example/episode/1x2")
        assertEquals("tt0944947", m?.imdbId)
        assertEquals("12345", m?.tmdbId)
        assertEquals(1, m?.season)
        assertEquals(2, m?.episode)
    }

    @Test
    fun `parseEncVidlink accepts common json shapes`() {
        assertEquals("enc123", MultiSourcePuller.parseEncVidlink("""{"text":"enc123"}"""))
        assertEquals("enc123", MultiSourcePuller.parseEncVidlink("""{"encrypted":"enc123"}"""))
        assertEquals("enc123", MultiSourcePuller.parseEncVidlink("\"enc123\""))
        assertNull(MultiSourcePuller.parseEncVidlink("not json"))
    }

    @Test
    fun `extractM3u8FromVidlink finds stream url in json`() {
        assertEquals(
            "https://cdn.example/master.m3u8",
            MultiSourcePuller.extractM3u8FromVidlink("""{"url":"https://cdn.example/master.m3u8"}""")
        )
        assertNull(MultiSourcePuller.extractM3u8FromVidlink("{}"))
    }
}
