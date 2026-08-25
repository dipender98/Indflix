package com.multimovies

import android.util.Log
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mockStatic

class LiveScreenscapeTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun mockAndroidLog() {
            try {
                val logMock = mockStatic(Log::class.java)
                logMock.`when`<Boolean> { Log.isLoggable(anyString(), anyInt()) }.thenReturn(false)
            } catch (e: Throwable) {
                println("Log mock failed: ${e.message}")
            }
        }
    }

    private val report = StringBuilder()
    private val file = java.io.File("E:/Project/Indflix/screenscape_report.txt")

    private fun log(s: String) {
        report.appendLine(s)
        println(s)
    }

    @Test
    fun `live screenscape extractor for movies and tv`() = runBlocking {
        report.clear()
        log("# screenscape.me live extractor (${java.time.Instant.now()})")
        data class Case(val label: String, val url: String, val ref: String?, val season: Int?, val episode: Int?)
        val cases = listOf(
            Case("Movie: Tumbbad", "https://screenscape.me/embed?imdb=tt8239946&type=movie&lan=hindi", null, null, null),
            Case("Movie: Andhadhun", "https://screenscape.me/embed?imdb=tt8108198&type=movie&lan=hindi", null, null, null),
            Case("TV: Breaking Bad S1E1", "https://screenscape.me/embed?imdb=tt0903747&type=tv&s=1&e=1&lan=hindi", "https://multimovies.motorcycles/tvshows/breaking-bad/season-1/episode-1/", 1, 1),
        )
        for ((label, url, ref, season, episode) in cases) {
            log("")
            log("### $label")
            log("- embed: $url")
            try {
                val subs = mutableListOf<ScreenSubtitle>()
                val links = ScreenscapeExtractor.extract(
                    MultiSourcePuller.Source(name = "screenscape.me", url = url, referer = ref, season = season, episode = episode),
                    onSubtitle = { subs.add(it) },
                )
                if (links.isEmpty()) {
                    log("  NO LINKS")
                } else {
                    links.forEach { l ->
                        log("  - [${l.name}] q=${l.quality} hi=${l.name.contains("Hindi")} url=${l.url}")
                    }
                    if (subs.isNotEmpty()) log("  subtitles: ${subs.size} (${subs.take(3).joinToString { it.lang }})")
                }
            } catch (t: Throwable) {
                log("  EXCEPTION: ${t.message?.take(200)}")
                t.printStackTrace()
            }
        }
        file.writeText(report.toString())
    }
}
