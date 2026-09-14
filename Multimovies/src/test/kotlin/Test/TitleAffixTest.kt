package Test

import com.multimovies.stripSiteAffix
import kotlin.test.Test
import kotlin.test.assertEquals

class TitleAffixTest {

    @Test
    fun stripsLeadingPipeAffix() {
        assertEquals("Reacher", stripSiteAffix("Multimovies | Reacher"))
        assertEquals("Reacher", stripSiteAffix("multimovies|Reacher"))
        assertEquals("Reacher", stripSiteAffix("Multimovies: Reacher"))
    }

    @Test
    fun stripsTrailingAffixVariants() {
        assertEquals("Reacher", stripSiteAffix("Reacher | Multimovies"))
        assertEquals("Reacher", stripSiteAffix("Reacher - Multimovies"))
    }

    @Test
    fun keepsCleanAndEdgeTitles() {
        assertEquals("Spider-Man", stripSiteAffix("Spider-Man"))
        assertEquals("A | B", stripSiteAffix("A | B"))
        assertEquals("Multimovies", stripSiteAffix("Multimovies"))
        assertEquals("", stripSiteAffix("  "))
    }
}
