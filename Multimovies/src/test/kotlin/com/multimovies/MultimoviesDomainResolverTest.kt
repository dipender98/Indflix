package com.multimovies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Guards the gateway-page domain extraction that the provider's self-heal
 *  depends on: multimovies.wtf announces the current live TLD, and the resolver
 *  must pick it out of share/social links and non-site anchors. */
class MultimoviesDomainResolverTest {

    private val gatewayHtml = """
        <html><head><title>MultiMovies</title></head><body>
          <a href="https://multimovies.beer" class="nav-btn">Go</a>
          <a href="https://multimovies.beer" class="cta-primary">Visit Site</a>
          <a href="https://multimovies.beer" style="text-decoration:none">1</a>
          <a href="https://api.whatsapp.com/send?text=Best+free+movies+site%21+https%3A%2F%2Fmultimovies.wtf%2F">share</a>
          <a href="https://t.me/share/url?url=https%3A%2F%2Fmultimovies.wtf%2F">telegram</a>
          <a href="#">Disclaimer</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `picks the most repeated live domain`() {
        assertEquals("https://multimovies.beer", MultimoviesDomainResolver.extractLiveDomain(gatewayHtml))
    }

    @Test
    fun `extract ignores share links and anchors without live domains`() {
        val html = """
            <a href="https://api.whatsapp.com/send?text=https%3A%2F%2Fmultimovies.wtf%2F">w</a>
            <a href="#">x</a>
            <a href="https://example.org/stream/">y</a>
        """.trimIndent()
        assertNull(MultimoviesDomainResolver.extractLiveDomain(html))
    }

    @Test
    fun `extract rejects blank html`() {
        assertNull(MultimoviesDomainResolver.extractLiveDomain(""))
    }

    @Test
    fun `normalize strips trailing slash and www`() {
        assertEquals("https://multimovies.beer", MultimoviesDomainResolver.normalize("https://multimovies.beer/"))
        assertEquals("https://multimovies.beer", MultimoviesDomainResolver.normalize("https://www.multimovies.beer"))
    }

    @Test
    fun `normalize rejects the gateway itself, share urls and other hosts`() {
        assertNull(MultimoviesDomainResolver.normalize("https://multimovies.wtf"))
        assertNull(MultimoviesDomainResolver.normalize("https://multimovies.wtf/"))
        assertNull(MultimoviesDomainResolver.normalize("https://api.whatsapp.com/send?text=https%3A%2F%2Fmultimovies.wtf%2F"))
        assertNull(MultimoviesDomainResolver.normalize("https://other.example/path"))
        assertNull(MultimoviesDomainResolver.normalize("#"))
        assertNull(MultimoviesDomainResolver.normalize("multimovies.beer"))
    }
}
