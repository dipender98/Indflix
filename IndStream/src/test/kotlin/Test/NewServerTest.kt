package Test

import com.indstream.ServerFarm
import com.indstream.ServerSpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Template for testing a NEWLY ADDED server/provider.
 *
 * Every live server in [ServerFarm.allServers] has its own dedicated test
 * file (VidyTest, VidBoltTest, MovieBoxTest, AnimeWorldTest, ...). This file
 * is reserved for servers that are added in the future.
 *
 * Kotlin covers EVERYTHING needed to validate a new provider — no Python
 * required — because tests run the real resolver code on the same classpath:
 *   - unit: registry spec, URL building, payload parsing (no network)
 *   - live: `StreamEngine.resolveOne(...)` end-to-end (real network) to prove
 *     the provider actually returns playable streams
 *
 * When adding a new server:
 *  1. Register it in `ServerRegistry.kt` ([ServerFarm.allServers]).
 *  2. Add the new id to `RejectedServers` only when it is intentionally NOT
 *     added (see `RejectedServers.kt`).
 *  3. Uncomment + fill in the examples below (or rename this file to
 *     `<ProviderName>Test.kt` and add a new file).
 *  4. Never assert below-720p output: `passesQualityFloor` must stay true for
 *     every emitted stream.
 */
class NewServerTest {

    // Helper: fetch the spec of the server under test once it is registered.
    private fun spec(id: String): ServerSpec = ServerFarm.allServers.first { it.id == id }

    // ===== 1. Unit: registry spec =====

    // @Test
    // fun newServer_registry_presentAndTmdbKeyed() {
    //     val s = spec("new-server-id")
    //     assertEquals("NewServerName", s.name)
    //     assertEquals(ServerIdType.TMDB, s.idType)
    //     assertEquals(10, s.timeoutSec)
    //     // assert flags that match the registry entry (hasSubtitles, isJsonApi,
    //     // downloadFriendly, supports4k, declaredLanguages, animeOnly, ...)
    // }

    // ===== 2. Unit: URL building =====

    // @Test
    // fun newServer_urls_carryIdAndEpisode() {
    //     val s = spec("new-server-id")
    //     assertEquals("https://provider.example/movie/27205", ServerFarm.buildMovieUrl(s, "27205"))
    //     assertEquals("https://provider.example/tv/1399/1/1", ServerFarm.buildTvUrl(s, "1399", 1, 1))
    // }

    // ===== 3. Unit: payload parsing (pin real captured payload + garbage) =====

    // @Test
    // fun newServer_parsesProviderPayload() {
    //     // val out = StreamEngine.yourNewParser(realCapturedText)
    //     // assertEquals(expectedUrl, out[0].url)
    //     // assertTrue(StreamEngine.yourNewParser("garbage").isEmpty())
    // }

    // ===== 4. LIVE: does it actually get streams (suitable?) =====
    // Use fixed TMDB ids (e.g. movie 27205, series 1399). This hits the real
    // network through the production resolver. It proves the provider is
    // suitable: resolves, returns absolute http urls, never below 720p.

    // @Test
    // fun newServer_liveResolvesStreams() = runBlocking {
    //     val streams = StreamEngine.resolveOne(spec("new-server-id"), 27205, null, "movie", 1, 1)
    //     assertTrue(streams.isNotEmpty(), "must resolve live streams")
    //     assertTrue(streams.all { it.url.startsWith("http") }, "urls must be absolute")
    //     assertTrue(streams.all { it.qualityHint >= 720 }, "never emit below 720p")
    //     streams.forEach { println("  q=${it.qualityHint} lang='${it.audioLabel}' ${it.url.take(80)}") }
    // }

    // @Test
    // fun newServer_liveResolvesTv() = runBlocking {
    //     val streams = StreamEngine.resolveOne(spec("new-server-id"), 1399, null, "tv", 1, 1)
    //     assertTrue(streams.isNotEmpty(), "tv must resolve too")
    //     assertTrue(streams.all { it.qualityHint >= 720 })
    // }
}
