package com.ottmirror

internal object Base64Decode {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val REVERSE: IntArray = IntArray(128) { -1 }.also { rev ->
        ALPHABET.forEachIndexed { i, c -> rev[c.code] = i }
    }

    fun decodeUtf8(input: String): String? {
        val clean = input.filterNot { it == '\n' || it == '\r' }
        if (clean.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream((clean.length * 3) / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            if (c == '=') break
            if (c.code >= 128) return null
            val v = REVERSE[c.code]
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return runCatching { out.toString("UTF-8") }.getOrNull()
    }
}