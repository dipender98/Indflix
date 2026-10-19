package com.indstream.foryou

import com.indstream.HttpKit
import com.indstream.Settings
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Chat completion client used to rank recommendation candidates. */
object AiService {

    /** Supported provider presets. */
    enum class Provider(
        val id: String,
        val label: String,
        val baseUrl: String,
        val defaultModel: String,
    ) {
        OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free"),
        GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
        OPENAI("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        OTHER("other", "Other (OpenAI-compatible)", "", "");

        companion object {
            /** Preset for a stored id, unknown ids fall back to the first one. */
            fun from(id: String?): Provider = entries.firstOrNull { it.id == id } ?: OPENROUTER
        }
    }

    /** One title the model may pick. */
    data class Candidate(
        val id: Int,
        val title: String,
        val year: String?,
        val rating: Double,
        val genres: String,
    )

    private const val SYSTEM =
        "You are a film and series recommendation engine. You answer only with a JSON array of integers."

    /** True when a key is stored for the selected provider. */
    fun isConfigured(): Boolean = !Settings.aiApiKey().isNullOrBlank()

    /** Rerank the candidates for the given history. Returns the picked ids, best first. */
    suspend fun rank(
        watched: List<ProfileTitle>,
        candidates: List<Candidate>,
        count: Int,
    ): List<Int> {
        val provider = Provider.from(Settings.aiProviderId())
        val key = Settings.aiApiKey()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val base = baseUrl(provider)
        val model = Settings.aiModel().takeIf { it.isNotBlank() } ?: provider.defaultModel
        if (base.isBlank() || model.isBlank()) return emptyList()
        val prompt = buildPrompt(watched, candidates, count)
        val body = post(provider, base, model, key, prompt) ?: return emptyList()
        return extractIds(extractText(body, provider == Provider.GEMINI) ?: return emptyList())
    }

    /** Verify a key with a tiny request. Returns a human readable result. */
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

    /** List the models a key can see and check the chosen one. Pure result text. */
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

    /** Model list endpoint for a provider. */
    private fun modelsEndpoint(provider: Provider, base: String, key: String): String =
        if (provider == Provider.GEMINI) "$base/models?key=$key" else "$base/models"

    /** Parse a model list payload (OpenAI "data" or Gemini "models" shape). Pure. */
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

    /** Effective base url for a provider, with the stored override applied. */
    fun baseUrl(provider: Provider): String {
        val custom = Settings.aiBaseUrl().trim().trimEnd('/')
        if (custom.isNotBlank()) return custom
        return provider.baseUrl.trimEnd('/')
    }

    /** Build the ranking prompt. Pure. */
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
            appendLine("Pick the $count candidates the user is most likely to enjoy, best first.")
            appendLine("Only use ids from the candidate list, never repeat an id.")
            appendLine("Answer with a JSON array of the tmdb ids only, for example [123, 456]. No other text.")
        }
    }

    /** Pull the id array out of a model reply. Pure. */
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

    /** Pull the reply text out of a provider payload. Pure. */
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

    /** Chat endpoint for a provider. */
    private fun endpoint(provider: Provider, base: String, model: String, key: String): String =
        if (provider == Provider.GEMINI) {
            "$base/models/${URLEncoder.encode(model, "UTF-8")}:generateContent?key=$key"
        } else {
            "$base/chat/completions"
        }

    /** Request headers for a provider. */
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

    /** Request body for a provider. */
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

    /** POST the prompt and return the raw body. Null on any failure. */
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
