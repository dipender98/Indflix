package Test

import com.vegamovies.MetadataService.TmdbItem
import com.vegamovies.VegamoviesProvider
import com.vegamovies.bestTmdbMatch
import com.vegamovies.combinedScore
import com.vegamovies.deaccent
import com.vegamovies.dedupeByName
import com.vegamovies.doubleFirstVowel
import com.vegamovies.levenshteinDistance
import com.vegamovies.normalizeTitle
import com.vegamovies.queryVariants
import com.vegamovies.relevanceScore
import com.vegamovies.tokenMatchScore
import com.vegamovies.votesBand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JVM tests for the fuzzy / typo-tolerant search engine. Datasets mirror live
 * observations: exact-token site hits vs the popular "Baahubali" franchise.
 */
class FuzzySearchTest {

    /** Real TMDB /search/multi?query=bahubali shape (names, years, vote counts). */
    private val tmdbBahubali = listOf(
        TmdbItem(534116, null, "movie", "Bāhubali: The Epic", "2025", null, 6.4, 42),
        TmdbItem(256040, null, "movie", "Bāhubali: The Beginning", "2015", null, 7.567, 960),
        TmdbItem(0, null, "tv", "Bindiya Ke Bahubali", null, null, 0.0, 0),
        TmdbItem(350312, null, "movie", "Bāhubali 2: The Conclusion", "2017", null, 7.494, 882),
        TmdbItem(0, null, "tv", "Bãhubali: The Torchbearer", null, null, 10.0, 5),
        TmdbItem(0, null, "tv", "Baahubali: Crown of Blood", null, null, 10.0, 2),
    )

    // ───────────────────────── normalization / typo helpers ─────────────────────────.

    @Test
    fun deaccent_collapsesMacronAndTilde() {
        assertEquals("Bahubali", deaccent("Bāhubali"))
        assertEquals("Bahubali", deaccent("Bãhubali"))
        assertEquals("bahubali the beginning", normalizeTitle("Bāhubali: The       Beginning"))
        assertEquals("baahubali 2 the conclusion", normalizeTitle("Baahubali 2: The Conclusion"))
    }

    @Test
    fun levenshtein_seesSingleVowelTypo() {
        assertEquals(1, levenshteinDistance("bahubali", "baahubali"))
        assertEquals(0, levenshteinDistance("bahubali", "bahubali"))
    }

    @Test
    fun doubleFirstVowel_producesLongVowelTransliteration() {
        assertEquals("baahubali", doubleFirstVowel("bahubali"))
        assertEquals("aavengers", doubleFirstVowel("avengers"))
        assertNull(doubleFirstVowel("baahubali")) // already long: no variant needed
        assertNull(doubleFirstVowel("xyzzy"))
    }

    @Test
    fun queryVariants_respellTypoQuery() {
        assertEquals(listOf("baahubali"), queryVariants("bahubali"))
        assertTrue(queryVariants("baahubali").isEmpty())
        assertTrue(queryVariants("avengers").contains("aavengers"))
    }

    @Test
    fun tokenMatch_acceptsTypoTokens() {
        assertEquals(1.0, tokenMatchScore("bahubali", listOf("bahubali")))
        assertEquals(0.8, tokenMatchScore("bahubali", listOf("baahubali", "the", "beginning")))
        assertEquals(0.0, tokenMatchScore("bahubali", listOf("spider", "man")))
    }

    // ───────────────────────── relevance ─────────────────────────.

    @Test
    fun relevance_typoQueryReachesFranchiseTitle() {
        assertTrue(relevanceScore("bahubali", "Baahubali: The Beginning (2015)") > 0.7)
        assertTrue(relevanceScore("bahubali", "Sri Bharatha Baahubali (2020)") > 0.7)
        // The exact-token hit scores highest on pure relevance - popularity must be the tiebreak.
        assertTrue(
            relevanceScore("bahubali", "Bindiya Ke Bahubali") >
                relevanceScore("bahubali", "Baahubali: The Beginning (2015)"),
        )
    }

    // ───────────────────────── popularity / combined score ─────────────────────────.

    @Test
    fun votesBand_mapsVoteCountsToBands() {
        assertEquals(0.8, votesBand(960))
        assertEquals(0.8, votesBand(882))
        assertEquals(0.4, votesBand(42))
        assertEquals(0.0, votesBand(0))
    }

    @Test
    fun bestTmdbMatch_findsPopularFranchiseEntry() {
        val hit = bestTmdbMatch(tmdbBahubali, "Baahubali: The Beginning (2015)", 2015)
        assertEquals("Bāhubali: The Beginning", hit?.name)
        assertNull(bestTmdbMatch(tmdbBahubali, "Sri Bharatha Baahubali (2020)", 2020))
    }

    @Test
    fun combinedScore_ranksPopularBahubaliFilmsFirst() {
        val beginning = combinedScore("bahubali", "Baahubali: The Beginning (2015)", 2015, tmdbBahubali)
        val conclusion = combinedScore("bahubali", "Baahubali 2: The Conclusion (2017)", 2017, tmdbBahubali)
        val epic = combinedScore("bahubali", "Baahubali: The Epic (2025)", 2025, tmdbBahubali)
        val bindiya = combinedScore("bahubali", "Bindiya Ke Bahubali (Season 1 - 2)", null, tmdbBahubali)
        val sri = combinedScore("bahubali", "Sri Bharatha Baahubali (2020)", 2020, tmdbBahubali)

        assertTrue(beginning > epic, "beginning=$beginning epic=$epic")
        assertTrue(conclusion > epic, "conclusion=$conclusion epic=$epic")
        assertTrue(epic > bindiya, "epic=$epic bindiya=$bindiya")
        assertTrue(bindiya > sri, "bindiya=$bindiya sri=$sri")
    }
// ───────────────────────── end-to-end ranking ─────────────────────────.

    /** The ts-search hit set for "bahubali" once the typo respelling surfaces the franchise posts. */
    @Test
    fun rank_searchResults_putsBeginningAndConclusionOnTop() {
        val p = VegamoviesProvider()
        val json = """
          {"hits":[
            {"document":{"permalink":"/download-sri-bharatha-baahubali-2020-hindi-dubbed-movie-480p-720p-1080p/",
              "post_title":"Download Sri Bharatha Baahubali (2020) HDRip Hindi Dubbed Full Movie 480p [550MB] | 720p [1.4GB] | 1080p [2.8GB]"}},
            {"document":{"permalink":"/download-bindiya-ke-bahubali-season-1-2-hindi-amazon-complete-web-series-480p-720p-1080p-web-dl/",
              "post_title":"Download Bindiya Ke Bahubali (Season 1 - 2) Hindi Amazon Complete Web Series 480p | 720p | 1080p WEB-DL"}},
            {"document":{"permalink":"/download-baahubali-the-torch-bearer-season-1-netflix-complete-web-series/",
              "post_title":"Download Baahubali: The Torch Bearer (Season 1) Hindi DD5.1 - Telugu DD5.1 Netflix Complete Web Series 480p | 720p | 1080p"}},
            {"document":{"permalink":"/download-baahubali-the-epic-2025-hindi-dubbed-full-movie-web-dl/",
              "post_title":"Download Baahubali: The Epic (2025) Hindi (ORG DD5.1) Dubbed Full Movie WEB-DL 480p [900MB] | 720p | 1080p"}},
            {"document":{"permalink":"/download-baahubali-2-the-conclusion-2017-hindi-bluray-full-movie/",
              "post_title":"Download Baahubali 2: The Conclusion (2017) Blu-Ray {Hindi DD5.1} Full Movie 480p [500MB] | 720p | 1080p"}},
            {"document":{"permalink":"/download-baahubali-the-beginning-2015-hindi-bluray-full-movie/",
              "post_title":"Download Baahubali: The Beginning (2015) Blu-Ray {Hindi DD5.1} Full Movie 480p [460MB] | 720p | 1080p"}},
            {"document":{"permalink":"/download-baahubali-crown-of-blood-2024-season-1-hotstar-special/",
              "post_title":"Download Baahubali: Crown of Blood (2024) Season 1 [Hindi DD5.1] Hotstar Special 480p | 720p | 1080p"}}
          ]}
        """.trimIndent()

        val hits = p.parseSearchHits(json, "https://new2.rogmovies.click")
        assertEquals(7, hits.size)

        val ranked = p.rankSearchResults("bahubali", hits, tmdbBahubali)
        val names = ranked.map { it.name }
        println("RANKED: " + names.joinToString(" | "))

        // The most popular films in India must lead the list.
        assertTrue(names[0].contains("Beginning") || names[0].contains("Conclusion"), "first=$names[0]")
        assertTrue(names[1].contains("Beginning") || names[1].contains("Conclusion"), "second=$names[1]")
        assertTrue(names[0] != names[1])
        assertTrue(names[2].contains("Epic"), "third=$names[2]")
        // The incidental exact-token hit the site used to rank first is last.
        assertEquals("Sri Bharatha Baahubali (2020)", names.last())
    }

    /** Mirror domains host the same post as identical cleaned titles - they must collapse to one row. */
    @Test
    fun dedupeByName_collapsesMirrorDomains() {
        val p = VegamoviesProvider()
        val json = """
          {"hits":[
            {"document":{"permalink":"/download-sri-bharatha-baahubali-2020-hindi-dubbed-movie-480p-720p-1080p/",
              "post_title":"Download Sri Bharatha Baahubali (2020) HDRip Hindi Dubbed Full Movie 480p [550MB] | 720p [1.4GB]"}},
            {"document":{"permalink":"/download-baahubali-the-beginning-2015-hindi-bluray-full-movie/",
              "post_title":"Download Baahubali: The Beginning (2015) Blu-Ray {Hindi DD5.1} Full Movie 480p [460MB]"}},
            {"document":{"permalink":"/download-sri-bharatha-baahubali-2020-hdr 720p-hindi-dubbed/",
              "post_title":"Download Sri Bharatha Baahubali (2020) HDRip Hindi Dubbed Full Movie 720p [1.4GB] | 1080p [2.8GB]"}}
          ]}
        """.trimIndent()
        val hits = p.parseSearchHits(json, "https://new2.vegamovies.futbol")
        assertEquals(3, hits.size)
        val deduped = hits.dedupeByName()
        assertEquals(2, deduped.size)
        assertTrue(deduped.count { it.name.contains("Sri Bharatha") } == 1)
    }
}