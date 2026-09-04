package com.ottmirror.sources

import com.ottmirror.core.HttpKit
/**

 * FILE: VidLinkSource.kt â€” the VidLink third-party stream source
 * (vidlink.pro).
 *
 *  - [VidlinkSource]  token generation (XSalsa20-Poly1305 secretbox over
 *                     mediaId + unix+480s under the reversed production
 *                     key) + the /api/b/{movie|tv} JSON API client with
 *                     multiLang=1 for multi-audio (Hindi) adaptive HLS
 *                     masters up to 1080p.
 *  - [NaCl]           the TweetNaCl secretbox primitives the token needs
 *                     (verified against official NaCl vectors in
 *                     VidlinkTest.kt).
 *
 * Role: a stream source â€” same category as the entries in Multimovies'
 * sources/ExternalSources.kt, self-contained because it carries its own crypto. If
 * VidLink rotates its key, only [VidlinkSource.KEY_HEX] needs updating.
 */

import java.math.BigInteger


/**
 * VidLink (vidlink.pro) token generator + API client.
 *
 * The site's own player encrypts `{mediaId}{unix+480s}` with
 * XSalsa20-Poly1305 (nacl.secretbox) under a fixed production key and a
 * 24-zero-byte nonce, then base64url-encodes `nonce || box` as the token:
 *
 *   GET https://vidlink.pro/api/b/movie/{token}?multiLang=1
 *   GET https://vidlink.pro/api/b/tv/{token}/{season}/{episode}?multiLang=1
 *
 * `multiLang=1` asks for the multi-audio master (Hindi + English dubs when
 * available). The JSON response carries `stream.playlist` (an HLS master
 * m3u8 URL) and `captions[]` subtitles. Token bytes are time-sensitive:
 * tokens older than a few minutes are rejected, so the timestamp is padded
 * +480s ahead exactly like the site's own JS does.
 *
 * Key/nonce verified Sept 2026 against mdtahseen7/Vidlink-proxy (public
 * reverse-engineering of vidlink.pro's player). If VidLink rotates its key
 * (every few months historically), replace [KEY_HEX] — that is the ONLY
 * constant that ever needs updating.
 */
object VidlinkSource {

    /** VidLink production secretbox key (hex). Rotate here when the site rotates. */
    private const val KEY_HEX =
        "c75136c5668bbfe65a7ecad431a745db68b5f381555b38d8f6c699449cf11fcd"

    /**
     * Headers the CDN serving the stream URLs requires at PLAYBACK time.
     *
     * bcdn.hakunaymatata.com (vidlink's host, Sept 2026) fingerprint-filters
     * by User-Agent: browser UAs get 428 Precondition Required / 429, while
     * a native player UA passes through and streams fine. Verified live
     * Sept 2026: "ExoPlayer" -> HTTP 200 with the full MP4 body.
     */
    val PLAYER_HEADERS: Map<String, String> = mapOf("User-Agent" to "ExoPlayer")

    /** 24 zero bytes, same as the site's player. */
    private val NONCE = ByteArray(24)

    /** Timestamp padding (seconds) the site adds so tokens live a few minutes. */
    private const val TIME_OFFSET_S = 480L

    /** Streams must be requested with the site page as Referer/Origin. */
    fun headers(mediaPageUrl: String): Map<String, String> = mapOf(
        "User-Agent" to HttpKit.userAgent,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to "https://vidlink.pro",
        "Referer" to mediaPageUrl,
    )

    fun movieHeaders(tmdbId: String): Map<String, String> =
        headers("https://vidlink.pro/movie/$tmdbId")

    fun tvHeaders(tmdbId: String, season: Int, episode: Int): Map<String, String> =
        headers("https://vidlink.pro/tv/$tmdbId/$season/$episode")

    /** Encrypt [mediaId] + (now + [TIME_OFFSET_S]) into a base64url token. */
    fun token(mediaId: String): String = token(mediaId, System.currentTimeMillis() / 1000L + TIME_OFFSET_S)

    /** Internal overload with an explicit timestamp — used by unit tests to
     *  cross-check token bytes against a reference implementation. */
    internal fun token(mediaId: String, timestampSeconds: Long): String {
        val key = hexToBytes(KEY_HEX)

        val idBytes = mediaId.toByteArray(Charsets.UTF_8)
        val message = ByteArray(idBytes.size + 8)
        idBytes.copyInto(message, 0)
        // 64-bit big-endian timestamp (mirrors JS DataView.setUint32 high/low).
        for (i in 0 until 8) {
            message[idBytes.size + 7 - i] = ((timestampSeconds ushr (i * 8)) and 0xFF).toByte()
        }

        val box = NaCl.secretbox(message, NONCE, key)

        val payload = ByteArray(24 + box.size)
        NONCE.copyInto(payload, 0)
        box.copyInto(payload, 24)

        return base64Url(payload)
    }

    /** Build the movie API URL for a TMDB id. */
    fun movieApiUrl(tmdbId: String): String =
        "https://vidlink.pro/api/b/movie/${token(tmdbId)}?multiLang=1"

    /** Build the TV API URL for a TMDB id + season/episode. */
    fun tvApiUrl(tmdbId: String, season: Int, episode: Int): String =
        "https://vidlink.pro/api/b/tv/${token(tmdbId)}/$season/$episode?multiLang=1"

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    /** URL-safe base64 without padding (JS `b64.replace(+,-)(/,_)(=+)`). */
    private val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private fun base64Url(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val has1 = i + 1 < bytes.size
            val has2 = i + 2 < bytes.size
            val b1 = if (has1) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (has2) bytes[i + 2].toInt() and 0xFF else 0
            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (!has1) break
            out.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            if (!has2) break
            out.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        return out.toString()
    }
}

/**
 * Minimal pure-Kotlin port of the TweetNaCl primitives VidLink's token needs:
 * crypto_secretbox_xsalsa20poly1305 (secretbox). All byte order is
 * little-endian; Salsa20 arithmetic uses 32-bit Int with unsigned shifts.
 *
 * Used by [VidlinkSource] — inputs are tiny (< 64 bytes), so the
 * BigInteger-based Poly1305 is more than fast enough.
 */
internal object NaCl {

    private val SIGMA = "expand 32-byte k".toByteArray(Charsets.US_ASCII)

    // ── Salsa20 core ────────────────────────────────────────────────────

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[b] = x[b] xor rotl(x[a] + x[d], 7)
        x[c] = x[c] xor rotl(x[b] + x[a], 9)
        x[d] = x[d] xor rotl(x[c] + x[b], 13)
        x[a] = x[a] xor rotl(x[d] + x[c], 18)
    }

    private fun load32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun store32(out: ByteArray, off: Int, v: Int) {
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = ((v ushr 8) and 0xFF).toByte()
        out[off + 2] = ((v ushr 16) and 0xFF).toByte()
        out[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /** 20-round Salsa20 core. [addOriginal] = full core (adds input back);
     *  otherwise returns the raw final state (HSalsa20 behavior). */
    private fun core(input: IntArray, addOriginal: Boolean): IntArray {
        val x = input.copyOf()
        var i = 20
        while (i > 0) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 5, 9, 13, 1)
            quarterRound(x, 10, 14, 2, 6)
            quarterRound(x, 15, 3, 7, 11)
            quarterRound(x, 0, 1, 2, 3)
            quarterRound(x, 5, 6, 7, 4)
            quarterRound(x, 10, 11, 8, 9)
            quarterRound(x, 15, 12, 13, 14)
            i -= 2
        }
        if (addOriginal) for (j in 0 until 16) x[j] += input[j]
        return x
    }

    /** HSalsa20: derive a 32-byte subkey from a 32-byte key + 16-byte nonce. */
    private fun hsalsa20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val state = IntArray(16)
        state[0] = load32(SIGMA, 0)
        state[1] = load32(key, 0)
        state[2] = load32(key, 4)
        state[3] = load32(key, 8)
        state[4] = load32(key, 12)
        state[5] = load32(SIGMA, 4)
        state[6] = load32(nonce16, 0)
        state[7] = load32(nonce16, 4)
        state[8] = load32(nonce16, 8)
        state[9] = load32(nonce16, 12)
        state[10] = load32(SIGMA, 8)
        state[11] = load32(key, 16)
        state[12] = load32(key, 20)
        state[13] = load32(key, 24)
        state[14] = load32(key, 28)
        state[15] = load32(SIGMA, 12)
        val out = core(state, addOriginal = false)
        val res = ByteArray(32)
        // TweetNaCl hsalsa20 output word order.
        val picks = intArrayOf(0, 5, 10, 15, 6, 7, 8, 9)
        picks.forEachIndexed { i, w -> store32(res, i * 4, out[w]) }
        return res
    }

    /** One 64-byte Salsa20 keystream block for an 8-byte nonce + 32-byte key. */
    private fun salsa20Block(key32: ByteArray, nonce8: ByteArray, blockIndex: Long): ByteArray {
        val state = IntArray(16)
        state[0] = load32(SIGMA, 0)
        state[1] = load32(key32, 0)
        state[2] = load32(key32, 4)
        state[3] = load32(key32, 8)
        state[4] = load32(key32, 12)
        state[5] = load32(SIGMA, 4)
        state[6] = load32(nonce8, 0)
        state[7] = load32(nonce8, 4)
        state[8] = blockIndex.toInt() // low 32 bits of the LE 64-bit counter
        state[9] = (blockIndex ushr 32).toInt()
        state[10] = load32(SIGMA, 8)
        state[11] = load32(key32, 16)
        state[12] = load32(key32, 20)
        state[13] = load32(key32, 24)
        state[14] = load32(key32, 28)
        state[15] = load32(SIGMA, 12)
        val out = core(state, addOriginal = true)
        val res = ByteArray(64)
        for (i in 0 until 16) store32(res, i * 4, out[i])
        return res
    }

    /** XSalsa20 keystream of [length] bytes for a 24-byte nonce + 32-byte key. */
    internal fun xsalsa20Keystream(key: ByteArray, nonce24: ByteArray, length: Int): ByteArray {
        require(key.size == 32 && nonce24.size == 24)
        val subkey = hsalsa20(key, nonce24.copyOfRange(0, 16))
        val nonce8 = nonce24.copyOfRange(16, 24)
        val out = ByteArray(length)
        var offset = 0
        var block = 0L
        while (offset < length) {
            val blockBytes = salsa20Block(subkey, nonce8, block)
            val n = minOf(64, length - offset)
            blockBytes.copyInto(out, offset, 0, n)
            offset += n
            block++
        }
        return out
    }

    // ── Poly1305 (BigInteger-based; correctness over speed for tiny inputs) ──

    private fun leBytesToBig(b: ByteArray): BigInteger =
        BigInteger(1, b.reversedArray())

    private fun bigToLeBytes(v: BigInteger, size: Int): ByteArray {
        val out = ByteArray(size)
        var x = v
        for (i in 0 until size) {
            out[i] = x.and(BigInteger.valueOf(0xFF)).toInt().toByte()
            x = x.shiftRight(8)
        }
        return out
    }

    /** Poly1305 MAC with [key] = 32-byte one-time key over [msg]. */
    internal fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        require(key.size == 32)
        val p = BigInteger.valueOf(2).pow(130).subtract(BigInteger.valueOf(5))
        // Clamp r exactly like TweetNaCl (r &= 0xffffffc0ffffffc0ffffffc0fffffff):
        val rBytes = key.copyOfRange(0, 16)
        rBytes[3] = (rBytes[3].toInt() and 15).toByte()
        rBytes[4] = (rBytes[4].toInt() and 252).toByte()
        rBytes[7] = (rBytes[7].toInt() and 15).toByte()
        rBytes[8] = (rBytes[8].toInt() and 252).toByte()
        rBytes[11] = (rBytes[11].toInt() and 15).toByte()
        rBytes[12] = (rBytes[12].toInt() and 252).toByte()
        rBytes[15] = (rBytes[15].toInt() and 15).toByte()
        val r = leBytesToBig(rBytes)
        val s = leBytesToBig(key.copyOfRange(16, 32))
        var h = BigInteger.ZERO
        var i = 0
        while (i < msg.size) {
            val end = minOf(i + 16, msg.size)
            // Append the 0x01 byte (block length marker) for full AND partial blocks.
            val block = msg.copyOfRange(i, end) + 0x01
            h = h.add(leBytesToBig(block)).multiply(r).mod(p)
            i += 16
        }
        h = h.add(s).mod(BigInteger.valueOf(2).pow(128))
        return bigToLeBytes(h, 16)
    }

    // ── secretbox ───────────────────────────────────────────────────────

    /**
     * NaCl secretbox (XSalsa20-Poly1305): returns MAC(16) || ciphertext,
     * matching tweetnacl.js `nacl.secretbox(message, nonce, key)`.
     */
    internal fun secretbox(message: ByteArray, nonce24: ByteArray, key: ByteArray): ByteArray {
        require(nonce24.size == 24 && key.size == 32)
        // TweetNaCl pads the message with 32 zero bytes; the first keystream
        // 32 bytes then double as the Poly1305 key.
        val full = ByteArray(32 + message.size)
        message.copyInto(full, 32)
        val ks = xsalsa20Keystream(key, nonce24, full.size)
        val c = ByteArray(full.size) { (full[it].toInt() xor ks[it].toInt()).toByte() }
        val mac = poly1305(c.copyOfRange(0, 32), c.copyOfRange(32, c.size))
        val out = ByteArray(16 + message.size)
        mac.copyInto(out, 0)
        c.copyInto(out, 16, 32, c.size)
        return out
    }
}



