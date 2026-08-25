package com.multimovies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cross-implementation regression vectors for the Nxsha AES envelopes.
 *
 * LOCAL_VECTOR was produced by the reference Node implementation of the web
 * player's encodeData (CryptoJS-compatible OpenSSL Salted AES-256-CBC) for the
 * plaintext payload {"hello":"world","n":42,...}. Decrypting it here proves the
 * Kotlin port matches the site's crypto byte-for-byte.
 */
class NxshaProtocolCryptoTest {

    private val localVector =
        "U2FsdGVkX19gSkY-YuS9co_S4ZkOpCMNPEt3reST8JFWCoc8cBxAxy2axeD2scq6AKQXVD3qxRzJJTpypZWXVbx9IGqR92XpvUqpGlPxt8yi8sUECJuWRkDck5wa_mk7"

    @Test
    fun `decrypts node-produced envelope`() {
        val json = NxshaProtocol.decodeData(localVector)
        assertNotNull(json, "decodeData failed on the reference vector")
        assertEquals("world", json.optString("hello"))
        assertEquals(42, json.optInt("n"))
    }

    @Test
    fun `encodeData output round-trips through decodeData`() {
        val encoded = NxshaProtocol.encodeData(
            mapOf(
                "tmdbId" to "155",
                "imdb_id" to "",
                "type" to "movie",
                "season" to null,
                "episode" to null,
            )
        )
        // base64url alphabet, no padding
        assertTrue(encoded.none { it == '+' || it == '/' || it == '=' })
        val decoded = NxshaProtocol.decodeData(encoded)
        assertNotNull(decoded)
        assertEquals("155", decoded.optString("tmdbId"))
        assertEquals("movie", decoded.optString("type"))
        assertTrue(decoded.has("_req_ts"), "encodeData must add _req_ts like the player")
        assertTrue(decoded.optString("_req_salt").isNotBlank(), "encodeData must add _req_salt")
    }

    @Test
    fun `aesEncrypt then aesDecrypt restores plaintext`() {
        val secret = "Salted__ round-trip: héllo नमस्ते 123"
        val encrypted = CryptoJs.aesEncryptCryptoJs(secret, NxshaProtocol.PASSPHRASE)
        assertEquals(secret, CryptoJs.aesDecryptCryptoJs(encrypted, NxshaProtocol.PASSPHRASE))
    }

    @Test
    fun `decodeData rejects garbage`() {
        assertEquals(null, NxshaProtocol.decodeData(""))
        assertEquals(null, NxshaProtocol.decodeData(null))
        assertEquals(null, NxshaProtocol.decodeData("not-a-valid-envelope"))
    }
}
