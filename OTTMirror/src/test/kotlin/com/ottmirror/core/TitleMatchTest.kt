package com.ottmirror.core
/**

 * FILE: TitleMatchTest.kt â€” guards the [TitleMatch] service.
 *
 *  - Title normalization, variant generation.
 *  - Levenshtein distance + relevance thresholds.
 *  - Year tolerance rules.
 */

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TitleMatchTest {

    @Test
    fun normalizeTitle_stripsPunctuation() {
        assertEquals("the dark knight", TitleMatch.normalizeTitle("The Dark Knight!"))
        assertEquals("mission impossible", TitleMatch.normalizeTitle("Mission: Impossible — "))
        assertEquals("", TitleMatch.normalizeTitle("   "))
        assertEquals("", TitleMatch.normalizeTitle(null))
    }

    @Test
    fun titleVariants_handlesAmpersandAndAnd() {
        val variants = TitleMatch.titleVariants("Tom & Jerry")
        assertTrue("tom jerry" in variants)
        assertTrue("tom and jerry" in variants)
    }

    @Test
    fun titleVariants_dropsApostrophes() {
        val variants = TitleMatch.titleVariants("Harry Potter")
        assertEquals(variants.first(), "harry potter")
    }

    @Test
    fun levenshtein_basic() {
        assertEquals(0, TitleMatch.levenshtein("abc", "abc"))
        assertEquals(1, TitleMatch.levenshtein("abc", "abd"))
        assertEquals(3, TitleMatch.levenshtein("", "abc"))
        assertEquals(3, TitleMatch.levenshtein("kitten", "sitting"))
    }

    @Test
    fun titleDistance_exactIsOne() {
        assertEquals(1.0, TitleMatch.titleDistance("Inception", "inception"))
    }

    @Test
    fun titleDistance_tokenPrefixHigh() {
        val score = TitleMatch.titleDistance("avengers endgame", "Avengers: Endgame")
        assertTrue(score >= 0.9, "expected >=0.9, got $score")
    }

    @Test
    fun titleDistance_typoStillMatches() {
        val score = TitleMatch.titleDistance("intersteller", "Interstellar")
        assertTrue(score > 0.7, "expected >0.7, got $score")
    }

    @Test
    fun yearMatches_respectsTolerance() {
        assertTrue(TitleMatch.yearMatches(2019, 2019))
        assertTrue(TitleMatch.yearMatches(2019, 2017)) // within tolerance 2
        assertFalse(TitleMatch.yearMatches(2019, 2001))
        assertTrue(TitleMatch.yearMatches(null, 2001)) // unknown = pass
        assertTrue(TitleMatch.yearMatches(2019, null)) // unknown = pass
    }

    @Test
    fun isRelevant_combinesYearAndTitle() {
        assertTrue(TitleMatch.isRelevant("Joker (2019)", "Joker", 2019, 2019))
        assertFalse(TitleMatch.isRelevant("Joker (2019)", "Joker", 2019, 2001))
        assertTrue(TitleMatch.isRelevant("Joker (2019)", "Joker", null, 2001))
    }

    @Test
    fun parseYear_finds4DigitYear() {
        assertEquals(2021, TitleMatch.parseYear("2021"))
        assertEquals(2021, TitleMatch.parseYear("released 2021-03-05"))
        assertEquals(null, TitleMatch.parseYear("nope"))
        assertEquals(null, TitleMatch.parseYear(null))
    }
}



