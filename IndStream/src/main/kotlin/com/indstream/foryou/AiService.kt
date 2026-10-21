package com.indstream.foryou

import com.indstream.HttpKit
import com.indstream.Settings
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

object AiService {

    enum class Provider(
        val id: String,
        val label: String,
        val baseUrl: String,
        val defaultModel: String,
    ) {
        OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free"),
        GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
        OTHER("other", "Other (OpenAI-compatible)", "", "");

        companion object {
            fun from(id: String?): Provider = entries.firstOrNull { it.id == id } ?: OPENROUTER
        }
    }

    data class Candidate(
        val id: Int,
        val title: String,
        val year: String?,
        val rating: Double,
        val genres: String,
    )

    private const val SYSTEM =
        "You are a film and series recommendation engine. You pick only titles that closely match " +
            "the user's taste. You answer only with a JSON array of integers."

    fun isConfigured(): Boolean = !Settings.aiApiKey().isNullOrBlank()

    suspend fun rank(
        watched: List<ProfileTitle>,
        candidates: List<Candidate>,
        count: Int,
    ): List<Int> {
        val provider = Provider.from(Settings.aiProviderId())
        val key = Settings.aiApiKey()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val base = baseUrl(provider)
        val model = Settings.aiModel(provider.id).takeIf { it.isNotBlank() } ?: provider.defaultModel
        if (base.isBlank() || model.isBlank()) return emptyList()
        val prompt = buildPrompt(watched, candidates, count)
        val body = post(provider, base, model, key, prompt) ?: return emptyList()
        return extractIds(extractText(body, provider == Provider.GEMINI) ?: return emptyList())
    }

    suspend fun testKey(
        provider: Provider,
        baseUrl: String,
        model: String,
        key: String,
    ): String {
        if (key.isBlank()) return "Enter a key first"
        val base = baseUrl.trim().trimEnd('/').ifBlank { provider.baseUrl }
        if (base.isBlank()) return "Enter the API base url first"
        val useModel = model.trim().ifBlank { provider.defaultModel }
        if (useModel.isBlank()) return "Enter a model first"
        val prompt = "Answer with the single word OK"
        return try {
            val resp = app.post(
                endpoint(provider, base, useModel, key),
                timeout = 20,
                headers = headers(provider, key),
                json = body(provider, useModel, prompt),
            )
            when {
                resp.code in 200..299 -> "Key works - provider connected"
                resp.code == 401 || resp.code == 403 -> "Key rejected (${resp.code}) - check the key"
                resp.code == 429 -> "Rate limited (429) - the key is probably valid"
                else -> "Provider error (${resp.code})"
            }
        } catch (e: Exception) {
            "Verify failed - no network?"
        }
    }

    suspend fun testModels(
        provider: Provider,
        baseUrl: String,
        model: String,
        key: String,
    ): String {
        if (key.isBlank()) return "Enter a key first"
        val base = baseUrl.trim().trimEnd('/').ifBlank { provider.baseUrl }
        if (base.isBlank()) return "Enter the API base url first"
        val useModel = model.trim().ifBlank { provider.defaultModel }
        if (useModel.isBlank()) return "Enter a model first"
        return try {
            val resp = app.get(
                modelsEndpoint(provider, base, key),
                timeout = 20,
                headers = headers(provider, key),
            )
            when {
                resp.code in 200..299 -> {
                    val ids = parseModels(resp.text, provider == Provider.GEMINI)
                    when {
                        ids.isEmpty() -> "List fetched but no models found (${resp.code})"
                        ids.any { it.equals(useModel, ignoreCase = true) } ->
                            "Model ok - $useModel exists (${ids.size} total)"
                        else -> {
                            val sample = ids.take(5).joinToString(", ")
                            "List ok (${ids.size} models) - '$useModel' not in it. Try: $sample"
                        }
                    }
                }
                resp.code == 401 || resp.code == 403 -> "Key rejected (${resp.code}) - check the key"
                resp.code == 429 -> "Rate limited (429) - the key is probably valid"
                else -> "Provider error (${resp.code})"
            }
        } catch (e: Exception) {
            "List failed - no network?"
        }
    }

    suspend fun fetchModels(
        provider: Provider,
        baseUrl: String,
        key: String,
    ): List<String> {
        val base = baseUrl.trim().trimEnd('/').ifBlank { provider.baseUrl }
        if (base.isBlank() || (provider == Provider.GEMINI && key.isBlank())) return emptyList()
        val requestHeaders = headers(provider, key).let {
            if (key.isBlank()) it - "Authorization" else it
        }
        val resp = runCatching {
            app.get(
                modelsEndpoint(provider, base, key),
                timeout = 20,
                headers = requestHeaders,
            )
        }.getOrNull() ?: return emptyList()
        if (resp.code !in 200..299) return emptyList()
        return freeModels(resp.text, provider == Provider.GEMINI)
    }

    internal fun freeModels(body: String, gemini: Boolean): List<String> = try {
        val root = JSONObject(body)
        val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        if (gemini) {
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val methods = o.optJSONArray("supportedGenerationMethods") ?: return@mapNotNull null
                if ((0 until methods.length()).none {
                        methods.optString(it).equals("generateContent", ignoreCase = true)
                    }
                ) return@mapNotNull null
                o.optString("name").removePrefix("models/").takeIf { it.isNotBlank() }
            }.distinct().take(5)
        } else {
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val pricing = o.optJSONObject("pricing")
                val free = id.endsWith(":free", ignoreCase = true) ||
                    (pricing != null && pricing.optString("prompt").toDoubleOrNull() == 0.0 &&
                        pricing.optString("completion").toDoubleOrNull() == 0.0)
                if (!free) return@mapNotNull null
                o.optLong("created", 0L) to id
            }.sortedByDescending { it.first }.map { it.second }.distinct().take(12)
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun modelsEndpoint(provider: Provider, base: String, key: String): String =
        if (provider == Provider.GEMINI) "$base/models?key=$key" else "$base/models"

    internal fun parseModels(body: String, gemini: Boolean): List<String> = try {
        val root = JSONObject(body)
        val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val raw = o.optString("id").takeIf { it.isNotBlank() }
                ?: o.optString("name").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (gemini) raw.removePrefix("models/") else raw
        }.distinct()
    } catch (e: Exception) {
        emptyList()
    }

    fun baseUrl(provider: Provider): String {
        val custom = Settings.aiBaseUrl(provider.id).trim().trimEnd('/')
        if (custom.isNotBlank()) return custom
        return provider.baseUrl.trimEnd('/')
    }

    internal fun buildPrompt(
        watched: List<ProfileTitle>,
        candidates: List<Candidate>,
        count: Int,
    ): String {
        val seen = watched.take(40).joinToString("\n") { w ->
            "- ${w.title.trim().take(48)} | ${w.year ?: "?"} | rating ${w.rating.toInt()} | " +
                if (w.watched) "watched" else "on list"
        }
        val pool = candidates.take(50).joinToString("\n") { c ->
            "- ${c.id} | ${c.title.trim().take(48)} | ${c.year ?: "?"} | ${c.rating} | ${c.genres.take(40)}"
        }
        return buildString {
            appendLine("Titles the user already watched or wants to watch:")
            appendLine(seen.ifBlank { "- (none)" })
            appendLine()
            appendLine("Candidate titles:")
            appendLine(pool.ifBlank { "- (none)" })
            appendLine()
            appendLine("Pick up to $count candidates the user is most likely to enjoy, best first.")
            appendLine("Match on genre, tone, era and rating; prefer strong fits over popular titles.")
            appendLine("Skip anything only loosely related - never pad the list with weak matches.")
            appendLine("Only use ids from the candidate list, never repeat an id.")
            appendLine("Answer with a JSON array of the tmdb ids only, for example [123, 456]. No other text.")
        }
    }

    internal fun extractIds(text: String): List<Int> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return try {
            val arr = JSONArray(text.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { i ->
                when (val v = arr.opt(i)) {
                    is Int -> v
                    is Number -> v.toInt()
                    is String -> v.trim().toIntOrNull()
                    else -> null
                }
            }.filter { it > 0 }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun extractText(body: String, gemini: Boolean): String? {
        return try {
            val root = JSONObject(body)
            if (gemini) {
                val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                parts?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("text") }
                        .joinToString("")
                }?.takeIf { it.isNotBlank() }
            } else {
                val content = root.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.opt("content")
                when (content) {
                    is String -> content.takeIf { it.isNotBlank() }
                    is JSONArray -> (0 until content.length())
                        .mapNotNull { content.optJSONObject(it)?.optString("text") }
                        .joinToString("").takeIf { it.isNotBlank() }
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun endpoint(provider: Provider, base: String, model: String, key: String): String =
        if (provider == Provider.GEMINI) {
            "$base/models/${URLEncoder.encode(model, "UTF-8")}:generateContent?key=$key"
        } else {
            "$base/chat/completions"
        }

    private fun headers(provider: Provider, key: String): Map<String, String> {
        val base = mapOf(
            "Content-Type" to "application/json",
            "User-Agent" to HttpKit.userAgent,
        )
        return when (provider) {
            Provider.GEMINI -> base
            Provider.OPENROUTER -> base + mapOf(
                "Authorization" to "Bearer $key",
                "HTTP-Referer" to "https://github.com/dipender98/Indflix",
                "X-Title" to "IndStream",
            )
            else -> base + mapOf("Authorization" to "Bearer $key")
        }
    }

    private fun body(provider: Provider, model: String, prompt: String): Map<String, Any?> =
        if (provider == Provider.GEMINI) {
            mapOf(
                "contents" to listOf(
                    mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt))),
                ),
                "generationConfig" to mapOf(
                    "temperature" to 0.4,
                    "maxOutputTokens" to 900,
                    "responseMimeType" to "application/json",
                ),
            )
        } else {
            mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to SYSTEM),
                    mapOf("role" to "user", "content" to prompt),
                ),
                "temperature" to 0.4,
                "max_tokens" to 900,
            )
        }

    private suspend fun post(
        provider: Provider,
        base: String,
        model: String,
        key: String,
        prompt: String,
    ): String? = runCatching {
        app.post(
            endpoint(provider, base, model, key),
            timeout = 25,
            headers = headers(provider, key),
            json = body(provider, model, prompt),
        ).text
    }.getOrNull()
}
