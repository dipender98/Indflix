package com.ottmirror.sources
/**

 * FILE: VidLinkTest.kt â€” guards VidLinkSource.kt (delete-safe rename-proof
 * tests named after what they test).
 *
 *  - NaCl secretbox correctness against the official libsodium test vector.
 *  - XSalsa20/Poly1305 round-trip integrity.
 *  - VidLink token encoding: base64url without padding, deterministic per
 *    (id, timestamp), timestamp-sensitive.
 */

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cryptographic correctness tests for [NaCl] (XSalsa20-Poly1305 secretbox)
 * and [VidlinkSource] token encoding.
 *
 * Vectors:
 *  - Secretbox Test Vector #1 from the official NaCl documentation
 *    (https://nacl.cr.yp.to/secretbox.html) — key/nonce/message -> boxed bytes.
 *  - HSalsa20 test vector from the NaCl / XSalsa20 paper.
 */
class VidlinkTest {

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun secretbox_naclTestVector1() {
        // Secretbox Test Vector #1 from libsodium's test/default/secretbox.c:
        // 131-byte message -> 147-byte boxed output (16 MAC + 131 ciphertext).
        val key = unhex(
            "1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389"
        )
        val nonce = unhex(
            "69696ee955b62b73cd62bda875fc73d68219e0036b7a0b37"
        )
        val message = unhex(
            "be075fc53c81f2d5cf141316ebeb0c7b5228c52a4c62cbd44b66849b64244ffc" +
                "e5ecbaaf33bd751a1ac728d45e6c61296cdc3c01233561f41db66cce314adb31" +
                "0e3be8250c46f06dceea3a7fa1348057e2f6556ad6b1318a024a838f21af1fde" +
                "048977eb48f59ffd4924ca1c60902e52f0a089bc76897040e082f93776384864" +
                "5e0705"
        )
        val expected = unhex(
            "f3ffc7703f9400e52a7dfb4b3d3305d98e993b9f48681273c29650ba32fc76ce" +
                "48332ea7164d96a4476fb8c531a1186ac0dfc17c98dce87b4da7f011ec48c972" +
                "71d2c20f9b928fe2270d6fb863d51738b48eeee314a7cc8ab932164548e526ae" +
                "90224368517acfeabd6bb3732bc0e9da99832b61ca01b6de56244a9e88d5f9b3" +
                "7973f622a43d14a6599b1f654cb45a74e355a5"
        )

        assertEquals(131, message.size, "vector message size")
        assertEquals(147, expected.size, "vector expected size")
        val boxed = NaCl.secretbox(message, nonce, key)
        assertEquals(expected.size, boxed.size, "boxed length")
        assertTrue(boxed.contentEquals(expected), "secretbox output mismatch:\n got ${hex(boxed)}\nwant ${hex(expected)}")
    }

    @Test
    fun secretbox_decryptRoundTrip() {
        // Decrypting our own box with the same primitives must recover the message.
        val key = unhex("c75136c5668bbfe65a7ecad431a745db68b5f381555b38d8f6c699449cf11fcd")
        val nonce = ByteArray(24)
        val message = "603".toByteArray(Charsets.UTF_8) + ByteArray(8) { 0 }

        val boxed = NaCl.secretbox(message, nonce, key)

        // Manual open: derive keystream, XOR ciphertext back, recompute MAC.
        val mac = boxed.copyOfRange(0, 16)
        val padded = ByteArray(32 + message.size)
        message.copyInto(padded, 32)
        val ks = NaCl.xsalsa20Keystream(key, nonce, padded.size)
        val cipher = padded.mapIndexed { i, b -> (b.toInt() xor ks[i].toInt()).toByte() }.toByteArray()
        val recomputedMac = NaCl.poly1305(cipher.copyOfRange(0, 32), cipher.copyOfRange(32, cipher.size))

        assertTrue(recomputedMac.contentEquals(mac), "MAC round-trip failed")
    }

    @Test
    fun vidlinkToken_isBase64UrlNoPadding() {
        val token = VidlinkSource.token("603", 1_700_000_000L)
        // payload = 24 nonce + (16 MAC + 11-byte message) = 51 bytes
        // -> base64url = ceil(51/3)*4 = 68 chars, no '=' padding.
        assertEquals(68, token.length, "unexpected token length: $token")
        assertTrue(token.none { it == '+' || it == '/' || it == '=' }, "token not base64url: $token")
    }

    @Test
    fun vidlinkToken_deterministicPerTimestamp() {
        val a = VidlinkSource.token("603", 1_700_000_000L)
        val b = VidlinkSource.token("603", 1_700_000_000L)
        assertEquals(a, b, "same id+timestamp must yield identical tokens")
        val c = VidlinkSource.token("603", 1_700_000_060L)
        assertTrue(a != c, "different timestamps must yield different tokens")
    }

    @Test
    fun vidlinkPlayerHeaders_useNativePlayerAgent() {
        // The bcdn.hakunaymatata.com CDN User-Agent-fingerprints requests:
        // browser UAs get 428/429, a native player UA gets 200. Playback
        // headers therefore must override the UA with a non-browser value.
        val ua = VidlinkSource.PLAYER_HEADERS["User-Agent"]
        assertEquals("ExoPlayer", ua, "vidlink playback must use a native player UA")
    }
}

