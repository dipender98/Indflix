package com.multimovies

import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SolveDocumentTest {

    private fun challengeDoc(): Document =
        Jsoup.parse("<html><body>Just a moment... verifying you are human</body></html>")

    private fun realDoc(title: String): Document =
        Jsoup.parse("<html><body><h1>$title</h1></body></html>")

    // (a) Normal load with working solve -> first attempt returns the real page.
    @Test
    fun `case a returns real document on first attempt`() = runTest {
        val doc = retryUntilSolved(
            attempts = 2,
            fetch = { realDoc("Batman") },
            isBlocked = { it.body()?.text().orEmpty().contains("just a moment", true) },
            failureMessage = { "boom" },
        )
        assertEquals("Batman", doc.selectFirst("h1")?.text())
    }

    // (b) Load that needs the retry path -> first returns the CF challenge, second the real page.
    @Test
    fun `case b retries after challenge and returns real document`() = runTest {
        var calls = 0
        val doc = retryUntilSolved(
            attempts = 2,
            fetch = {
                calls++
                if (calls == 1) challengeDoc() else realDoc("Superman")
            },
            isBlocked = { it.body()?.text().orEmpty().contains("just a moment", true) },
            failureMessage = { "boom" },
        )
        assertEquals(2, calls)
        assertEquals("Superman", doc.selectFirst("h1")?.text())
    }

    // (c) Both attempts fail -> throws ONCE (no infinite loop). This is the fix that
    // stops the "keep refreshing" behaviour: a finite number of attempts, then a single error.
    @Test
    fun `case c throws once instead of looping`() = runTest {
        var calls = 0
        val ex = assertFailsWith<IllegalStateException> {
            retryUntilSolved(
                attempts = 2,
                fetch = {
                    calls++
                    throw RuntimeException("blocked by cloudflare")
                },
                isBlocked = { false },
                failureMessage = { lastErr -> lastErr?.localizedMessage ?: "Failed to load" },
            )
        }
        // Exactly the configured number of attempts are made, then it throws - it does NOT loop forever.
        assertEquals(2, calls)
        assertTrue(
            ex.message?.contains("blocked by cloudflare") == true,
            "error message should surface the underlying failure, got: ${ex.message}",
        )
    }

    // (c-variant) Both attempts return the challenge page -> also throws once (no loop).
    @Test
    fun `case c challenge on both attempts throws once`() = runTest {
        var calls = 0
        val ex = assertFailsWith<IllegalStateException> {
            retryUntilSolved(
                attempts = 2,
                fetch = { calls++; challengeDoc() },
                isBlocked = { it.body()?.text().orEmpty().contains("just a moment", true) },
                failureMessage = { "Cloudflare challenge not solved" },
            )
        }
        assertEquals(2, calls)
        assertTrue(ex.message?.contains("Cloudflare challenge not solved") == true)
    }
}
