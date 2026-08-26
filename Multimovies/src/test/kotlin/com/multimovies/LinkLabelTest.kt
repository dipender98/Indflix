package com.multimovies

import kotlin.test.Test
import kotlin.test.assertEquals

/** Guards the link identity contract CloudStream's priority editor depends on:
 *  ExtractorLink.source == name == linkLabel(...) on every emission path.
 *  CloudStream saves player priorities by exact match on `source` while the
 *  server list displays `name` — any drift breaks the user's ranking. */
class LinkLabelTest {

    // ---- MultiSourcePuller.linkLabel -------------------------------------

    @Test
    fun `label is the bare server name`() {
        assertEquals("Cineverse", MultiSourcePuller.linkLabel("Cineverse", false))
    }

    @Test
    fun `hindi variant appends exactly one Hindi suffix`() {
        assertEquals("Cineverse Hindi", MultiSourcePuller.linkLabel("Cineverse", true))
        assertEquals("Nxsha (Nitro) Hindi", MultiSourcePuller.linkLabel("Nxsha (Nitro)", true))
    }

    @Test
    fun `blank or null base falls back to Multimovies`() {
        assertEquals("Multimovies", MultiSourcePuller.linkLabel(null, false))
        assertEquals("Multimovies", MultiSourcePuller.linkLabel("   ", false))
        assertEquals("Multimovies Hindi", MultiSourcePuller.linkLabel("", true))
    }

    @Test
    fun `label trims surrounding whitespace`() {
        assertEquals("VidSrc", MultiSourcePuller.linkLabel("  VidSrc  ", false))
    }

    // ---- sourceKey round-trip (priority lookup must survive labeling) -----

    @Test
    fun `sourceKey round-trips every SOURCE_PRIORITY entry through linkLabel`() {
        for (entry in SOURCE_PRIORITY) {
            assertEquals(entry.trim(), MultiSourcePuller.sourceKey(MultiSourcePuller.linkLabel(entry, true)))
            assertEquals(entry.trim(), MultiSourcePuller.sourceKey(MultiSourcePuller.linkLabel(entry, false)))
        }
    }

    @Test
    fun `nxsha sub-server labels map onto the nxsha priority entry`() {
        assertEquals("Nxsha", MultiSourcePuller.sourceKey(MultiSourcePuller.linkLabel("Nxsha (Nitro)", false)))
        assertEquals("Nxsha", MultiSourcePuller.sourceKey(MultiSourcePuller.linkLabel("Nxsha (Nitro)", true)))
    }

    @Test
    fun `screenscape label maps onto its priority entry`() {
        val labeled = MultiSourcePuller.linkLabel("screenscape.me", true)
        assertEquals("screenscape.me Hindi", labeled)
        assertEquals("screenscape.me", MultiSourcePuller.sourceKey(labeled))
    }

    // ---- sourceKey strips duplicate counter -N ----------------------------

    @Test
    fun `sourceKey strips duplicate counter suffix`() {
        assertEquals("Cineverse", MultiSourcePuller.sourceKey("Cineverse-2"))
        assertEquals("Cineverse", MultiSourcePuller.sourceKey("Cineverse-3"))
    }

    @Test
    fun `sourceKey strips counter before paren and Hindi`() {
        assertEquals("Cineverse", MultiSourcePuller.sourceKey("Cineverse Hindi-2"))
        assertEquals("Nxsha", MultiSourcePuller.sourceKey("Nxsha (Nitro)-2"))
    }

    @Test
    fun `sourceKey acts on labels that already have the counter`() {
        // round-trip: linkLabel -> sourceKey should survive every SOURCE_PRIORITY entry
        for (entry in SOURCE_PRIORITY) {
            val plain = MultiSourcePuller.linkLabel(entry, false)
            assertEquals(entry.trim(), MultiSourcePuller.sourceKey("$plain-2"))
            assertEquals(entry.trim(), MultiSourcePuller.sourceKey("$plain-99"))
        }
    }
}
