package Test

import com.multimovies.MULTIMOVIES_HOST_REGEX
import com.multimovies.MultimoviesDomainResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainResolverMmTest {

    @Test
    fun normalizeAcceptsSubdomainHosts() {
        assertEquals("https://m1.multimovies.courses", MultimoviesDomainResolver.normalize("https://m1.multimovies.courses/"))
        assertEquals("https://m1.multimovies.courses", MultimoviesDomainResolver.normalize("https://m1.multimovies.courses"))
        assertEquals("https://multimovies.garden", MultimoviesDomainResolver.normalize("https://www.multimovies.garden/"))
    }

    @Test
    fun normalizeRejectsGatewayAndLookalikes() {
        assertNull(MultimoviesDomainResolver.normalize("https://multimovies.wtf/"))
        assertNull(MultimoviesDomainResolver.normalize("https://m1.multimovies.courses.evil.com/"))
        assertNull(MultimoviesDomainResolver.normalize("https://m1multimovies.courses/"))
        assertNull(MultimoviesDomainResolver.normalize("https://example.com/"))
    }

    @Test
    fun extractLiveDomainPicksMostRepeatedHost() {
        val html = """
            <html><body>
            <a href="https://multimovies.wtf/">gateway</a>
            <a href="https://m1.multimovies.courses/">live 1</a>
            <a href="https://m1.multimovies.courses/watch/x">live 2</a>
            <a href="https://m1.multimovies.courses/search">live 3</a>
            </body></html>
        """.trimIndent()

        assertEquals("https://m1.multimovies.courses", MultimoviesDomainResolver.extractLiveDomain(html))
    }

    @Test
    fun currentDomainFallsBackToLiveSeed() {
        assertEquals("https://m1.multimovies.courses", MultimoviesDomainResolver.currentDomain())
    }

    @Test
    fun hostRegexMatchesSubdomainsOnAnyTld() {
        assertTrue(MULTIMOVIES_HOST_REGEX.containsMatchIn("https://m1.multimovies.courses/movie/x-1"))
        assertTrue(MULTIMOVIES_HOST_REGEX.containsMatchIn("https://multimovies.garden/tvshows/y/"))
        assertTrue(MULTIMOVIES_HOST_REGEX.containsMatchIn("https://www.multimovies.casa"))
        assertFalse(MULTIMOVIES_HOST_REGEX.containsMatchIn("https://m1multimovies.courses/"))
        assertFalse(MULTIMOVIES_HOST_REGEX.containsMatchIn("https://m1.multimovies.courses.evil.com/x"))
        assertFalse(MULTIMOVIES_HOST_REGEX.containsMatchIn("https://example.com/multimovies.courses/"))
    }
}
