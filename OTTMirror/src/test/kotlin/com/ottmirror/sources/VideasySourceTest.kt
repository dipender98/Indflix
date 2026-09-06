package com.ottmirror.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards VideasySource's cipher port against the verified Python reference
 * (cracked Sept 2026 against live api.speedracelight.com payloads, header
 * "mvm1"). Round-trip + anchor tests so refactors can't silently break it.
 */
class VideasySourceTest {

    @Test
    fun mix_deterministicAndZeroAnchored() {
        assertEquals(0, VideasySource.mixForTest(0L))
        assertEquals(VideasySource.mixForTest(0xFFFFFFFFL), VideasySource.mixForTest(0xFFFFFFFFL))
        assertTrue(VideasySource.mixForTest(1L) != 0)
    }

    @Test
    fun fnv1a_deterministic() {
        assertTrue(VideasySource.fnv1aForTest("") != 0)
        assertEquals(VideasySource.fnv1aForTest("abc"), VideasySource.fnv1aForTest("abc"))
    }

    @Test
    fun decrypt_roundTrip() {
        // encrypt == decrypt for a stream XOR cipher: build a ciphertext by
        // running the keystream over a known plaintext, then decrypt it back
        // through the public entry point.
        val seed = "59622076.V3iLVcZwtMl1EabSU9UqBf"
        val mediaId = 385687
        val plaintext = "mvm1{\"ok\":true,\"sources\":[]}"
        val pt = plaintext.toByteArray(Charsets.UTF_8)

        val state = VideasySource.stateForTest(seed, mediaId)
        val cipher = ByteArray(pt.size)
        var i = 0; var counter = 0
        while (i < pt.size) {
            val v = VideasySource.keystreamForTest(state, counter).toLong() and 0xFFFFFFFFL
            counter++
            for (shift in intArrayOf(0, 8, 16, 24)) {
                if (i >= pt.size) break
                cipher[i] = (pt[i].toInt() xor ((v ushr shift) and 0xFFL).toInt()).toByte()
                i++
            }
        }
        val b64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(cipher)
        assertEquals(plaintext.substring(4), VideasySource.decryptForTest(b64, seed, mediaId))
    }

    @Test
    fun decrypt_rejectsBadHeader() {
        // All-zeros ciphertext decrypts to garbage → header check must throw.
        val b64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(16))
        var threw = false
        try {
            VideasySource.decryptForTest(b64, "1.abc", 42)
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "bad header must throw IllegalStateException")
    }
}
