package com.multimovies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

/** Guards the title normalization pipeline that bridges TMDB titles (with
 *  "&", apostrophes, punctuation) to the way a Dooplay site stores them. */
class TitleNormalizationTest {

    // ---- normalizeTitle ---------------------------------------------------

    @Test
    fun `normalizeTitle strips straight apostrophe`() {
        assertEquals("the kings man", normalizeTitle("The King's Man"))
    }

    @Test
    fun `normalizeTitle strips curly apostrophe`() {
        assertEquals("the kings man", normalizeTitle("The King\u2019s Man"))
    }

    @Test
    fun `normalizeTitle preserves Devanagari vowel signs`() {
        assertEquals("\u0939\u093f\u0928\u094d\u0926\u0940", normalizeTitle("\u0939\u093f\u0928\u094d\u0926\u0940"))
    }

    @Test
    fun `normalizeTitle collapses punctuation to spaces`() {
        assertEquals("locke key", normalizeTitle("Locke & Key"))
        assertEquals("the ring 2002", normalizeTitle("The Ring (2002)"))
        assertEquals("spider man no way home", normalizeTitle("Spider-Man: No Way Home"))
    }

    // ---- titleVariants ----------------------------------------------------

    @Test
    fun `titleVariants includes original`() {
        assertContains(titleVariants("Locke & Key"), "Locke & Key")
    }

    @Test
    fun `titleVariants replaces & with and`() {
        assertContains(titleVariants("Locke & Key"), "Locke and Key")
    }

    @Test
    fun `titleVariants replaces and with &`() {
        assertContains(titleVariants("Locke and Key"), "Locke & Key")
    }

    @Test
    fun `titleVariants strips apostrophe from title`() {
        assertContains(titleVariants("The King's Man"), "The Kings Man")
    }

    @Test
    fun `titleVariants strips curly apostrophe`() {
        assertContains(titleVariants("The King\u2019s Man"), "The Kings Man")
    }

    @Test
    fun `titleVariants removes stray punctuation`() {
        val variants = titleVariants("The Ring (2002)")
        assertContains(variants, "The Ring 2002")
    }

    @Test
    fun `titleVariants returns distinct entries`() {
        val variants = titleVariants("Locke & Key")
        assertEquals(variants, variants.distinct())
    }

    @Test
    fun `titleVariants no-ampersand title keeps the single spelling`() {
        val variants = titleVariants("John Wick")
        assertEquals(listOf("John Wick"), variants)
    }

    @Test
    fun `titleVariants handles colon and hyphen`() {
        val variants = titleVariants("Spider-Man: No Way Home")
        assertContains(variants, "Spider Man No Way Home")
    }

    // ---- titleDistance ----------------------------------------------------

    @Test
    fun `distance zero for exact match`() {
        assertEquals(0, titleDistance("Locke & Key", "Locke & Key"))
    }

    @Test
    fun `distance zero for and vs ampersand`() {
        assertEquals(0, titleDistance("Locke and Key", "Locke & Key"))
    }

    @Test
    fun `distance zero for ampersand vs and`() {
        assertEquals(0, titleDistance("Locke & Key", "Locke and Key"))
    }

    @Test
    fun `distance zero when apostrophe is the only difference`() {
        assertEquals(0, titleDistance("The King's Man", "The Kings Man"))
    }

    @Test
    fun `distance two for unrelated titles`() {
        assertEquals(2, titleDistance("Locke and Key", "Stranger Things"))
    }
}