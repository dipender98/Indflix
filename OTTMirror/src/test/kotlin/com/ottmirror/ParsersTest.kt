package com.ottmirror

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsersTest {

    @Test
    fun parseSearch_bareArray() {
        val hits = NetMirrorParsers.parseSearch("""[{"id":"101","t":"Money Heist","type":"tv"},{"id":"102","t":"3 Idiots","type":"movie"}]""")
        assertEquals(2, hits.size)
        assertEquals("101", hits[0].id)
        assertEquals("Money Heist", hits[0].title)
        assertEquals("movie", hits[1].type)
    }

    @Test
    fun parseSearch_wrappedObject() {
        val hits = NetMirrorParsers.parseSearch("""{"searchResult":[{"id":"5","t":"RRR"}]}""")
        assertEquals(1, hits.size)
        assertEquals("5", hits[0].id)
        assertEquals("RRR", hits[0].title)
    }

    @Test
    fun parseSearch_blankOrGarbage_returnsEmpty() {
        assertTrue(NetMirrorParsers.parseSearch(null).isEmpty())
        assertTrue(NetMirrorParsers.parseSearch("").isEmpty())
        assertTrue(NetMirrorParsers.parseSearch("not json").isEmpty())
    }

    @Test
    fun parsePost_movieShape() {
        val post = assertNotNull(
            NetMirrorParsers.parsePost(
                """{"id":"42","title":"Dune","year":"2021","type":"movie","desc":"A desert epic","genre":"Sci-Fi","match":"IMDb 8.0","tmdb_id":"438631","episodes":[]}"""
            )
        )
        assertEquals("Dune", post.title)
        assertEquals("438631", post.tmdbId)
        assertEquals("IMDb 8.0", post.rating)
        assertTrue(post.episodes.isEmpty())
        assertTrue(post.seasons.isEmpty())
    }

    @Test
    fun parsePost_tvShape() {
        val post = assertNotNull(
            NetMirrorParsers.parsePost(
                """{"id":"7","title":"Special Ops","episodes":[{"id":"71","t":"E1","s":"S1","ep":"E1","time":"45m"}],"season":[{"id":"7","name":"S1"}],"nextPageShow":1,"nextPageSeason":"7"}"""
            )
        )
        assertEquals(1, post.episodes.size)
        assertEquals("71", post.episodes[0].id)
        assertEquals(1, post.episodes[0].season)
        assertEquals(1, post.episodes[0].episode)
        assertTrue(post.nextPageShow)
        assertEquals("7", post.nextPageSeason)
    }

    @Test
    fun parseEpisodes_paginationFlag() {
        val (eps, next) = NetMirrorParsers.parseEpisodes(
            """{"episodes":[{"id":"9","t":"S01E02","s":"S1","ep":"E2"}],"nextPageShow":0}"""
        )
        assertEquals(1, eps.size)
        assertEquals("9", eps[0].id)
        assertEquals(2, eps[0].episode)
        assertEquals(false, next)
    }

    @Test
    fun parsePlaylist_objectAndArrayForms() {
        val obj = assertNotNull(
            NetMirrorParsers.parsePlaylist(
                """{"title":"Dune","sources":[{"file":"/play/master.m3u8","label":"Full HD","type":"hls"}],"tracks":[{"kind":"captions","file":"/en.vtt","label":"English"}]}"""
            )
        )
        assertEquals(1, obj.sources!!.size)
        assertEquals("/play/master.m3u8", obj.sources[0].file)
        assertEquals("English", obj.tracks!![0].label)

        val arr = assertNotNull(
            NetMirrorParsers.parsePlaylist(
                """[{"sources":[{"file":"https://cdn/x.m3u8","label":"720p","type":"hls"}]}]"""
            )
        )
        assertEquals("720p", arr.sources!![0].label)
    }

    @Test
    fun parseNewTvTokenAndPlayer() {
        val token = assertNotNull(
            NetMirrorParsers.parseNewTvToken("""{"token_hash":"aHR0cHM6Ly90di5pbWdjZG4ua2lt","dom":"false"}""")
        )
        assertEquals("aHR0cHM6Ly90di5pbWdjZG4ua2lt", token.tokenHash)

        val player = assertNotNull(
            NetMirrorParsers.parseNewTvPlayer("""{"status":"ok","video_link":"https://cdn/x/master.m3u8","referer":"https://api"}""")
        )
        assertEquals("https://cdn/x/master.m3u8", player.videoLink)
        assertEquals("https://api", player.referer)
    }

    @Test
    fun base64Decode_roundTrips() {
        assertEquals("https://tv.imgcdn.kim", Base64Decode.decodeUtf8("aHR0cHM6Ly90di5pbWdjZG4ua2lt"))
        assertEquals("", Base64Decode.decodeUtf8(""))
        assertNull(Base64Decode.decodeUtf8("!!not base64!!"))
        assertNull(Base64Decode.decodeUtf8("not valid base64!!"))
        assertEquals("hello", Base64Decode.decodeUtf8("aGVsbG8="))
    }
}