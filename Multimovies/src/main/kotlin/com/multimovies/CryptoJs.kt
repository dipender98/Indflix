package com.multimovies

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Shared CryptoJS-compatible AES helpers for the OpenSSL "Salted__" envelope
 * format (`CryptoJS.AES.encrypt(data, passphrase)` / `.decrypt(...)` with a
 * string passphrase). Used by extractors whose web players ship that exact
 * client-side crypto.
 *
 * Copied from ScreenscapeExtractor's private ports so ScreenscapeExtractor.kt
 * stays untouched; new extractors (Nxsha) build on this object instead. The
 * encrypt side mirrors what CryptoJS does internally: random 8-byte salt,
 * EVP_BytesToKey(MD5) key+iv derivation, AES-256-CBC/PKCS5, output =
 * base64("Salted__" + salt + ciphertext).
 */
internal object CryptoJs {

    /** OpenSSL EVP_BytesToKey (MD5 variant), matching CryptoJS's default KDF. */
    fun evpKdf(password: ByteArray, salt: ByteArray?, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val total = keyLen + ivLen
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        while (out.size() < total) {
            val toHash = ByteArrayOutputStream().apply {
                write(block); write(password)
                if (salt != null) write(salt)
            }.toByteArray()
            block = md.digest(toHash)
            out.write(block)
        }
        val keyIv = out.toByteArray()
        return keyIv.copyOfRange(0, keyLen) to keyIv.copyOfRange(keyLen, total)
    }

    /** Decrypt a base64 OpenSSL-Salted AES ciphertext; returns UTF-8 plaintext
     *  or null when the payload is malformed / undecryptable. */
    fun aesDecryptCryptoJs(cipherBase64: String, passphrase: String): String? {
        val b64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val valid = cipherBase64.filter { it in b64Chars }
        val padding = (4 - (valid.length % 4)) % 4
        val padded = valid + "=".repeat(padding)
        val all = runCatching { Base64.getDecoder().decode(padded) }.getOrNull() ?: return null
        val (salt, cipher) = if (all.size >= 16 && String(all.copyOfRange(0, 8), StandardCharsets.ISO_8859_1) == "Salted__") {
            all.copyOfRange(8, 16) to all.copyOfRange(16, all.size)
        } else null to all
        val (key, iv) = evpKdf(passphrase.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
        return runCatching {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(c.doFinal(cipher), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    /** Encrypt [plaintext] the way CryptoJS.AES.encrypt(plaintext, passphrase)
     *  does and return its standard-base64 string form ("Salted__" envelope). */
    fun aesEncryptCryptoJs(plaintext: String, passphrase: String): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val (key, iv) = evpKdf(passphrase.toByteArray(StandardCharsets.UTF_8), salt, 32, 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val out = ByteArrayOutputStream(8 + salt.size + encrypted.size)
        out.write("Salted__".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(salt)
        out.write(encrypted)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}
