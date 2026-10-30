package Test

import com.indstream.IndStreamProvider
import com.indstream.M3uPlaylists
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uPlaylistsTest {

    private val playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="one" tvg-logo="https://img.example/one.png" group-title="News;Politics",One News
        https://cdn.example/one.m3u8
        #EXTINF:-1 tvg-logo="https://img.example/two.png" group-title="Sports",Two Sports (1080p)
        #EXTVLCOPT:http-user-agent=Example
        https://cdn.example/two.m3u8
        #EXTINF:-1 group-title="Movies",Film Channel (720p)
        /relative/three.m3u8
        #EXTINF:-1,Movies Again
        #EXTGRP:Movies
        https://cdn.example/four.m3u8
        #EXTINF:-1,Duplicate
        https://cdn.example/one.m3u8
        #EXTINF:-1,Not http
        rtmp://cdn.example/five
        #EXTINF:-1 group-title="News",Comma, Name
        https://cdn.example/six.m3u8
    """.trimIndent()

    @Test
    fun entries_are_parsed_with_groups_logos_and_order() {
        val entries = M3uPlaylists.parse(playlist, "https://cdn.example/lists/main.m3u")
        assertEquals(5, entries.size)

        val first = entries[0]
        assertEquals("One News", first.name)
        assertEquals("https://cdn.example/one.m3u8", first.url)
        assertEquals("https://img.example/one.png", first.logo)
        assertEquals(listOf("News", "Politics"), first.groups)
        assertTrue(first.isLive)

        val second = entries[1]
        assertEquals(1080, second.height)
        assertEquals(listOf("Sports"), second.groups)

        assertEquals("https://cdn.example/relative/three.m3u8", entries[2].url)
        assertEquals(720, entries[2].height)

        assertEquals(listOf("Movies"), entries[3].groups)
        assertEquals("Comma, Name", entries[4].name)
    }

    @Test
    fun duplicate_urls_and_non_http_links_are_dropped() {
        val entries = M3uPlaylists.parse(playlist)
        assertEquals(1, entries.count { it.url == "https://cdn.example/one.m3u8" })
        assertTrue(entries.none { it.url.startsWith("rtmp") })
    }

    @Test
    fun duration_marks_live_and_on_demand_entries() {
        val entries = M3uPlaylists.parse(
            """
            #EXTM3U
            #EXTINF:-1,Live One
            https://cdn.example/live.m3u8
            #EXTINF:3600,Movie One
            https://cdn.example/movie.mp4
            """.trimIndent(),
        )
        assertEquals(2, entries.size)
        assertTrue(entries[0].isLive)
        assertFalse(entries[1].isLive)
        assertEquals(3600, entries[1].duration)
    }

    @Test
    fun an_entry_round_trips_through_its_play_url() {
        val entry = M3uPlaylists.Entry(
            name = "News & Weather + More",
            url = "https://cdn.example/live.m3u8?token=a+b&x=1",
            logo = "https://img.example/logo.png",
            groups = listOf("News", "Weather"),
            height = 1080,
            duration = -1,
        )
        val encoded = M3uPlaylists.encode(entry)
        assertEquals(entry, M3uPlaylists.decode(encoded))
    }

    @Test
    fun unrelated_links_are_not_playlist_entries() {
        assertNull(M3uPlaylists.decode("https://www.themoviedb.org/movie/1"))
        assertNull(M3uPlaylists.decode("indstream-m3u://item"))
        assertNull(M3uPlaylists.decode("indstream-m3u://item?n=x"))
        assertNull(M3uPlaylists.decode(null))
    }

    @Test
    fun heights_come_from_names_and_links() {
        assertEquals(1080, M3uPlaylists.heightOf("Channel (1080p)", null))
        assertEquals(2160, M3uPlaylists.heightOf("Channel 4K", null))
        assertEquals(720, M3uPlaylists.heightOf("Channel", "https://cdn.example/720p/live.m3u8"))
        assertEquals(0, M3uPlaylists.heightOf("Channel", "https://cdn.example/live.m3u8"))
        assertEquals(0, M3uPlaylists.heightOf(null, null))
    }

    @Test
    fun playlist_sources_are_normalized() {
        assertEquals(
            "https://example.com/list.m3u",
            M3uPlaylists.normalizeSource("  https://example.com/list.m3u  "),
        )
        assertEquals("https://example.com/list.m3u", M3uPlaylists.normalizeSource("example.com/list.m3u"))
        assertEquals(
            M3uPlaylists.DEFAULT_SOURCE,
            M3uPlaylists.normalizeSource(M3uPlaylists.DEFAULT_SOURCE),
        )
        assertNull(M3uPlaylists.normalizeSource(null))
        assertNull(M3uPlaylists.normalizeSource(""))
        assertNull(M3uPlaylists.normalizeSource("ftp://example.com/list.m3u"))
        assertNull(M3uPlaylists.normalizeSource("https://"))
    }

    @Test
    fun the_default_source_supplies_the_category_rows_when_present() {
        val custom = "https://example.com/own.m3u"
        assertEquals(M3uPlaylists.DEFAULT_SOURCE, M3uPlaylists.primarySource(listOf(custom, M3uPlaylists.DEFAULT_SOURCE)))
        assertEquals(custom, M3uPlaylists.primarySource(listOf(custom)))
        assertNull(M3uPlaylists.primarySource(emptyList()))
    }

    @Test
    fun a_source_label_falls_back_to_its_host() {
        assertEquals("iptv-org.github.io", M3uPlaylists.sourceLabel(M3uPlaylists.DEFAULT_SOURCE))
        assertEquals("example.com · mylist", M3uPlaylists.sourceLabel("https://www.example.com/lists/mylist.m3u"))
        assertNotNull(M3uPlaylists.sourceLabel("https://example.com"))
    }

    @Test
    fun playlist_rows_stay_hidden_until_enabled() {
        assertTrue(M3uPlaylists.rows().isEmpty())
    }

    @Test
    fun a_stream_link_keeps_the_item_it_came_from() {
        val entry = M3uPlaylists.Entry(
            name = "News One",
            url = "https://cdn.example/live/news-one.m3u8",
            logo = null,
            groups = listOf("News"),
            height = 1080,
        )
        M3uPlaylists.remember(entry)
        assertEquals(entry, M3uPlaylists.remembered(entry.url))
        assertNull(M3uPlaylists.remembered("https://cdn.example/live/other.m3u8"))
        assertNull(M3uPlaylists.remembered(null))
    }

    @Test
    fun the_real_live_entry_url_round_trips() {
        val entry = M3uPlaylists.Entry(
            name = "3AW (1080p)",
            url = "https://3awlive.akamaized.net/hls/live/2032295/3AW/index.m3u8",
            logo = "https://i.imgur.com/Z4MdB0S.png",
            groups = listOf("News"),
            height = 1080,
            duration = -1,
        )
        val encoded = M3uPlaylists.encode(entry)
        assertTrue(encoded.startsWith("indstream-m3u://item?"))
        assertTrue(encoded.contains("u=https%3A%2F%2F3awlive.akamaized.net"))
        val decoded = M3uPlaylists.decode(encoded)
        assertEquals(entry, decoded)
        assertTrue(decoded!!.isLive)
    }

    @Test
    fun provider_load_returns_a_live_response_for_a_playlist_item() = runBlocking {
        val entry = M3uPlaylists.Entry(
            name = "3AW (1080p)",
            url = "https://3awlive.akamaized.net/hls/live/2032295/3AW/index.m3u8",
            logo = "https://i.imgur.com/Z4MdB0S.png",
            groups = listOf("News"),
            height = 1080,
            duration = -1,
        )
        val response = IndStreamProvider().load(M3uPlaylists.encode(entry))
        assertNotNull(response)
        assertIs<LiveStreamLoadResponse>(response)
        assertEquals("3AW (1080p)", response.name)
        assertEquals(entry.url, response.dataUrl)
        assertEquals(entry.logo, response.posterUrl)
        assertEquals(entry.groups, response.tags)
        assertEquals(entry, M3uPlaylists.remembered(entry.url))
    }

    @Test
    fun decode_survives_the_mainurl_prefix_that_fixurl_prepends() {
        // CloudStream's APIRepository.load() calls api.fixUrl() on the card url.
        // Because the scheme does not start with "http", the provider receives
        // "https://www.themoviedb.org/indstream-m3u://item?..." on the device.
        val entry = M3uPlaylists.Entry(
            name = "3AW (1080p)",
            url = "https://3awlive.akamaized.net/hls/live/2032295/3AW/index.m3u8",
            logo = "https://i.imgur.com/Z4MdB0S.png",
            groups = listOf("News"),
            height = 1080,
            duration = -1,
        )
        val mangled = "https://www.themoviedb.org/" + M3uPlaylists.encode(entry)
        assertEquals(entry, M3uPlaylists.decode(mangled))
    }

    @Test
    fun provider_load_returns_a_live_response_for_the_fixurl_mangled_url() = runBlocking {
        val entry = M3uPlaylists.Entry(
            name = "3AW (1080p)",
            url = "https://3awlive.akamaized.net/hls/live/2032295/3AW/index.m3u8",
            logo = "https://i.imgur.com/Z4MdB0S.png",
            groups = listOf("News"),
            height = 1080,
            duration = -1,
        )
        val mangled = "https://www.themoviedb.org/" + M3uPlaylists.encode(entry)
        val response = IndStreamProvider().load(mangled)
        assertNotNull(response)
        assertIs<LiveStreamLoadResponse>(response)
        assertEquals(entry.url, response.dataUrl)
        assertEquals(entry, M3uPlaylists.remembered(entry.url))
    }
}
