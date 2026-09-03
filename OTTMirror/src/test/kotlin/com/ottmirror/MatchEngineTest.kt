package com.ottmirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchEngineTest {

    @Test
    fun normalizeTitle_stripsPunctuation() {
        assertEquals("the dark knight", MatchEngine.normalizeTitle("The Dark Knight!"))
        assertEquals("mission impossible", MatchEngine.normalizeTitle("Mission: Impossible — "))
        assertEquals("", MatchEngine.normalizeTitle("   "))
        assertEquals("", MatchEngine.normalizeTitle(null))
    }

    @Test
    fun titleVariants_handlesAmpersandAndAnd() {
        val variants = MatchEngine.titleVariants("Tom & Jerry")
        assertTrue("tom jerry" in variants)
        assertTrue("tom and jerry" in variants)
    }

    @Test
    fun titleVariants_dropsApostrophes() {
        val variants = MatchEngine.titleVariants("Harry Potter")
        assertEquals(variants.first(), "harry potter")
    }

    @Test
    fun levenshtein_basic() {
        assertEquals(0, MatchEngine.levenshtein("abc", "abc"))
        assertEquals(1, MatchEngine.levenshtein("abc", "abd"))
        assertEquals(3, MatchEngine.levenshtein("", "abc"))
        assertEquals(3, MatchEngine.levenshtein("kitten", "sitting"))
    }

    @Test
    fun titleDistance_exactIsOne() {
        assertEquals(1.0, MatchEngine.titleDistance("Inception", "inception"))
    }

    @Test
    fun titleDistance_tokenPrefixHigh() {
        val score = MatchEngine.titleDistance("avengers endgame", "Avengers: Endgame")
        assertTrue(score >= 0.9, "expected >=0.9, got $score")
    }

    @Test
    fun titleDistance_typoStillMatches() {
        val score = MatchEngine.titleDistance("intersteller", "Interstellar")
        assertTrue(score > 0.7, "expected >0.7, got $score")
    }

    @Test
    fun yearMatches_respectsTolerance() {
        assertTrue(MatchEngine.yearMatches(2019, 2019))
        assertTrue(MatchEngine.yearMatches(2019, 2017)) // within tolerance 2
        assertFalse(MatchEngine.yearMatches(2019, 2001))
        assertTrue(MatchEngine.yearMatches(null, 2001)) // unknown = pass
        assertTrue(MatchEngine.yearMatches(2019, null)) // unknown = pass
    }

    @Test
    fun isRelevant_combinesYearAndTitle() {
        assertTrue(MatchEngine.isRelevant("Joker (2019)", "Joker", 2019, 2019))
        assertFalse(MatchEngine.isRelevant("Joker (2019)", "Joker", 2019, 2001))
        assertTrue(MatchEngine.isRelevant("Joker (2019)", "Joker", null, 2001))
    }

    @Test
    fun parseYear_finds4DigitYear() {
        assertEquals(2021, MatchEngine.parseYear("2021"))
        assertEquals(2021, MatchEngine.parseYear("released 2021-03-05"))
        assertEquals(null, MatchEngine.parseYear("nope"))
        assertEquals(null, MatchEngine.parseYear(null))
    }
}
