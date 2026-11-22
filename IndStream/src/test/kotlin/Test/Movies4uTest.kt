package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.ServerSpeed
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Movies4uTest {

    @Test
    fun movies4u_registry_presentAndTmdbKeyed() {
        val spec = ServerFarm.allServers.first { it.id == "movies4u" }
        assertEquals("Movies4u", spec.name)
        assertEquals(ServerIdType.TMDB, spec.idType)
        assertEquals("https://new8.movies4u.garden/", spec.referer)
        assertEquals(60, spec.timeoutSec)
        assertEquals(ServerSpeed.SLOW, spec.speed)
        assertTrue(spec.downloadFriendly)
        assertTrue(spec.supports4k)
        assertEquals(setOf("Hindi", "Tamil", "Telugu"), spec.declaredLanguages)
        assertFalse(spec.hasSubtitles)
    }

    @Test
    fun movies4u_lookupHits_parsesTypoProxyJson() {
        val json = """{"hits":[{"post_title":"Film (2024)","permalink":"https://site.example/p/m/"}]}"""
        val hits = StreamEngine.m4uSearchHits(json, "https://site.example")
        assertEquals(1, hits.size)
        assertEquals("https://site.example/p/m/", hits[0].first)
        assertEquals("Film (2024)", hits[0].second)
        assertTrue(StreamEngine.m4uSearchHits("<html>not json</html>", "https://site.example").isEmpty())
    }

    @Test
    fun movies4u_groups_headingOwnedButtonsDiv() {
        val html = "<h5>Film (2024) 1080p [1.7GB]</h5>" +
            "<div class=\"downloads-btns-div\">" +
            "<a href=\"https://hubcloud.ist/drive/abc\">Cloud</a>" +
            "<a href=\"https://pixeldrain.dev/u/AbC123\">PixelDrain</a>" +
            "<a href=\"/relative\">Rel</a></div>" +
            "<h5>Other (2023) 480p</h5><div class=\"downloads-btns-div\"></div>"
        val groups = StreamEngine.m4uGroups(html, "https://site.example")
        assertEquals(1, groups.size)
        assertEquals("Film (2024) 1080p [1.7GB]", groups[0].heading)
        assertEquals(3, groups[0].links.size, "all anchors resolve absolute via abs:href")
        assertTrue(groups[0].links.all { it.startsWith("http") })
        assertTrue(groups[0].links.any { it == "https://site.example/relative" })
    }

    @Test
    fun movies4u_episodeOf_parsesHeadingPatterns() {
        assertEquals(4, StreamEngine.m4uEpisodeOf("Episodes: 4"))
        assertEquals(4, StreamEngine.m4uEpisodeOf("S02E04"))
        assertEquals(4, StreamEngine.m4uEpisodeOf("E04"))
        assertNull(StreamEngine.m4uEpisodeOf("Film (2024) 1080p"))
    }

    @Test
    fun movies4u_groupAudioLabel_prefersPostListWhenHeadingSignalsMulti() {
        assertEquals("Hindi, English", StreamEngine.groupAudioLabel("Jailer 2 (2026) [Hindi ORG. + Multi Audio] 1080p", "Hindi, English"))
        assertEquals("Punjabi", StreamEngine.groupAudioLabel("Punjabi ORG DD 5.1", "Punjabi, Hindi, English, Tamil"))
        assertEquals("Hindi, English", StreamEngine.groupAudioLabel("1080p", "Hindi, English"))
    }
}
