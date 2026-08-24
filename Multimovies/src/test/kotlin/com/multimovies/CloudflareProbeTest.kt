package com.multimovies

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class CloudflareProbeTest {

    private fun hmacHex(message: String, key: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `probe bootstrap with browser headers`() {
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        val e = (0 until 24).joinToString("") { (java.security.SecureRandom().nextInt(256)).toString(16).padStart(2, '0') }
        val x = System.currentTimeMillis().toString(36)
        val r = (0 until 9).joinToString("") { (java.security.SecureRandom().nextInt(256)).toString(16).padStart(2, '0') }
        val t = "token.$x.$r"
        val enc = java.util.Base64.getEncoder().encodeToString(t.toByteArray(Charsets.UTF_8))
            .replace("+", "-").replace("/", "_").replace(Regex("=+$"), "")
        val f = hmacHex(t, e).take(24)
        val route = "$enc.$f"
        val url = "https://screenscape.me/api/$route"
        println("route=$route")
        println("e=$e")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
            "x-screenscape-client" to "web-player",
            "x-screenscape-bootstrap" to e,
            "Referer" to "https://screenscape.me/embed?imdb=tt8239946&type=movie&lan=hindi",
            "sec-ch-ua" to "\"Not=A?Brand\";v=\"99\", \"Google Chrome\";v=\"151\", \"Chromium\";v=\"151\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin" to "https://screenscape.me",
            "Cache-Control" to "no-store",
        )
        val b = Request.Builder().url(url).post("".toRequestBody(null))
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            println("status=${resp.code}")
            println("headers=${resp.headers.toMultimap().filterKeys { it in setOf("cf-ray", "server", "content-type", "cf-mitigated") }}")
            println("body=${resp.body?.string()?.take(200)}")
        }
    }
}