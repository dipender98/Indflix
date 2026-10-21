package com.indstream

import android.content.Context
import android.widget.Toast
import com.indstream.foryou.AppSession
import com.indstream.foryou.ForYouRefresh

object Settings {

    const val KEY_LINK = "https://store.wyzie.io/redeem"

    const val TMDB_LINK = "https://www.themoviedb.org/settings/api"

    private const val PREFS = "indstream_settings"
    private const val KEY = "wyzie_api_key"
    private const val TMDB_KEY = "tmdb_api_key"
    private const val AI_KEY = "ai_api_key"
    private const val AI_KEY_PREFIX = "ai_api_key_"
    private const val AI_MODEL = "ai_model"
    private const val AI_BASE = "ai_base_url"
    private const val AI_MODEL_PREFIX = "ai_model_"
    private const val AI_BASE_PREFIX = "ai_base_"
    private const val AI_PROVIDER = "ai_provider"
    private const val FORYOU_MODE = "foryou_refresh_mode"
    private const val SERVER_OFF_PREFIX = "server_off_"
    private const val SHUFFLE_ROWS = "shuffle_rows"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cached: String? = null

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        appContext = context.applicationContext
        AppSession.attach(context)
        load()
    }

    fun appContextOrNull(): Context? = appContext

    fun apiKey(): String? {
        if (!loaded) load()
        return cached?.takeIf { it.isNotBlank() }
    }

    fun isValidKey(raw: String?): Boolean = normalizeKey(raw).length >= 8

    fun normalizeKey(raw: String?): String = raw?.trim().orEmpty()

    fun save(context: Context, raw: String): Boolean {
        val key = normalizeKey(raw)
        if (key.isNotEmpty() && !isValidKey(key)) return false
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, key).apply()
        }
        cached = key
        loaded = true
        appContext = context.applicationContext
        return true
    }

    fun clear(context: Context) {
        save(context, "")
    }

    fun tmdbApiKey(): String? {
        if (!tmdbLoaded) loadTmdb()
        return tmdbCached?.takeIf { it.isNotBlank() }
    }

    fun isValidTmdbKey(raw: String?): Boolean {
        val k = normalizeKey(raw)
        return k.length >= 32 && !k.startsWith("eyJ")
    }

    fun tmdbKeyProblem(raw: String?): String? {
        val k = normalizeKey(raw)
        if (k.isEmpty()) return null
        if (k.startsWith("eyJ")) return "That's the Read Access Token - paste the API Key (v3)"
        if (k.length < 32) return "Key looks too short (need the 32-char API Key)"
        return null
    }

    fun saveTmdb(context: Context, raw: String): Boolean {
        val key = normalizeKey(raw)
        if (key.isNotEmpty() && !isValidTmdbKey(key)) return false
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(TMDB_KEY, key).apply()
        }
        tmdbCached = key
        tmdbLoaded = true
        appContext = context.applicationContext
        return true
    }

    fun clearTmdb(context: Context) {
        saveTmdb(context, "")
    }

    private val aiKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var aiModels: Map<String, String> = emptyMap()

    @Volatile
    private var aiBases: Map<String, String> = emptyMap()

    @Volatile
    private var serverOff: Set<String> = emptySet()

    @Volatile
    private var serversLoaded = false

    @Volatile
    private var shuffleRowsCached: Boolean? = null

    @Volatile
    private var aiProviderCached: String? = null

    @Volatile
    private var aiLoaded = false

    fun aiApiKey(provider: String? = null): String? {
        if (!aiLoaded) loadAi()
        val id = provider?.trim()?.takeIf { it.isNotBlank() }
            ?: aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
        return aiKeys[id]?.takeIf { it.isNotBlank() }
    }

    fun aiProviderId(): String {
        if (!aiLoaded) loadAi()
        return aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
    }

    fun aiModel(provider: String? = null): String {
        if (!aiLoaded) loadAi()
        val id = provider?.trim()?.takeIf { it.isNotBlank() }
            ?: aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
        return aiModels[id].orEmpty()
    }

    fun aiBaseUrl(provider: String? = null): String {
        if (!aiLoaded) loadAi()
        val id = provider?.trim()?.takeIf { it.isNotBlank() }
            ?: aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
        return aiBases[id].orEmpty()
    }

    fun saveAi(context: Context, provider: String, rawKey: String, model: String, baseUrl: String) {
        val key = normalizeKey(rawKey)
        val id = provider.trim().ifBlank { "openrouter" }
        val cleanModel = model.trim()
        val cleanBase = baseUrl.trim().trimEnd('/')
        runCatching {
            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(AI_PROVIDER, id)
                .remove(AI_KEY)
            if (key.isBlank()) edit.remove(AI_KEY_PREFIX + id) else edit.putString(AI_KEY_PREFIX + id, key)
            if (cleanModel.isBlank()) edit.remove(AI_MODEL_PREFIX + id)
            else edit.putString(AI_MODEL_PREFIX + id, cleanModel)
            if (cleanBase.isBlank()) edit.remove(AI_BASE_PREFIX + id)
            else edit.putString(AI_BASE_PREFIX + id, cleanBase)
            edit.apply()
        }
        if (key.isBlank()) aiKeys.remove(id) else aiKeys[id] = key
        aiModels = aiModels + (id to cleanModel)
        aiBases = aiBases + (id to cleanBase)
        aiProviderCached = id
        aiLoaded = true
        appContext = context.applicationContext
    }

    fun clearAi(context: Context, provider: String? = null) {
        if (!aiLoaded) loadAi()
        val id = provider?.trim()?.takeIf { it.isNotBlank() }
            ?: aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(AI_KEY_PREFIX + id)
                .remove(AI_MODEL_PREFIX + id)
                .remove(AI_BASE_PREFIX + id)
                .remove(AI_MODEL)
                .remove(AI_BASE)
                .remove(AI_KEY)
                .apply()
        }
        aiKeys.remove(id)
        aiModels = aiModels - id
        aiBases = aiBases - id
        aiLoaded = true
        appContext = context.applicationContext
    }

    @Volatile
    private var forYouModeCached: String? = null

    @Volatile
    private var forYouModeLoaded = false

    fun forYouRefreshMode(): ForYouRefresh.Mode {
        if (!forYouModeLoaded) loadForYouMode()
        return ForYouRefresh.Mode.from(forYouModeCached)
    }

    fun saveForYouRefreshMode(context: Context, mode: ForYouRefresh.Mode) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(FORYOU_MODE, mode.id).apply()
        }
        forYouModeCached = mode.id
        forYouModeLoaded = true
        appContext = context.applicationContext
    }

    private fun loadForYouMode() {
        val ctx = appContext ?: return
        forYouModeCached = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FORYOU_MODE, "").orEmpty()
        }.getOrDefault("")
        forYouModeLoaded = true
    }

    private fun loadAi() {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            aiProviderCached = prefs.getString(AI_PROVIDER, "").orEmpty()
            aiKeys.clear()
            val models = HashMap<String, String>()
            val bases = HashMap<String, String>()
            prefs.all.forEach { (name, value) ->
                val text = (value as? String).orEmpty()
                when {
                    name.startsWith(AI_KEY_PREFIX) -> if (text.isNotBlank()) {
                        aiKeys[name.removePrefix(AI_KEY_PREFIX)] = text
                    }
                    name.startsWith(AI_MODEL_PREFIX) -> models[name.removePrefix(AI_MODEL_PREFIX)] = text
                    name.startsWith(AI_BASE_PREFIX) -> bases[name.removePrefix(AI_BASE_PREFIX)] = text
                }
            }
            val owner = aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
            if (!models.containsKey(owner)) models[owner] = prefs.getString(AI_MODEL, "").orEmpty()
            if (!bases.containsKey(owner)) bases[owner] = prefs.getString(AI_BASE, "").orEmpty()
            aiModels = models
            aiBases = bases
            val legacy = prefs.getString(AI_KEY, "").orEmpty()
            if (legacy.isNotBlank()) aiKeys.putIfAbsent(owner, legacy)
        }
        aiLoaded = true
    }

    fun serverEnabled(id: String): Boolean {
        if (!serversLoaded) loadServers()
        return !serverOff.contains(id)
    }

    fun saveServerEnabled(context: Context, id: String, on: Boolean) {
        runCatching {
            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (on) edit.remove(SERVER_OFF_PREFIX + id)
            else edit.putBoolean(SERVER_OFF_PREFIX + id, true)
            edit.apply()
        }
        serverOff = if (on) serverOff - id else serverOff + id
        serversLoaded = true
        appContext = context.applicationContext
    }

    fun saveAllServersEnabled(context: Context, ids: Collection<String>, on: Boolean) {
        runCatching {
            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            ids.forEach { id ->
                if (on) edit.remove(SERVER_OFF_PREFIX + id)
                else edit.putBoolean(SERVER_OFF_PREFIX + id, true)
            }
            edit.apply()
        }
        serverOff = if (on) serverOff - ids else serverOff + ids
        serversLoaded = true
        appContext = context.applicationContext
    }

    private fun loadServers() {
        val ctx = appContext ?: return
        serverOff = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
                .filter { (name, value) -> name.startsWith(SERVER_OFF_PREFIX) && value == true }
                .map { (name, _) -> name.removePrefix(SERVER_OFF_PREFIX) }
                .toSet()
        }.getOrDefault(emptySet())
        serversLoaded = true
    }

    fun shuffleRows(): Boolean {
        if (shuffleRowsCached == null) {
            val ctx = appContext
            shuffleRowsCached = ctx?.let {
                runCatching {
                    it.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getBoolean(SHUFFLE_ROWS, false)
                }.getOrDefault(false)
            } ?: false
        }
        return shuffleRowsCached == true
    }

    fun saveShuffleRows(context: Context, on: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(SHUFFLE_ROWS, on).apply()
        }
        shuffleRowsCached = on
        appContext = context.applicationContext
    }

    @Volatile
    private var tmdbCached: String? = null

    @Volatile
    private var tmdbLoaded = false

    private fun loadTmdb() {
        val ctx = appContext ?: return
        tmdbCached = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TMDB_KEY, "").orEmpty()
        }.getOrDefault("")
        tmdbLoaded = true
    }

    @Volatile
    private var wyzieFailureNotified = false

    fun notifyWyzieFailed(reason: String) {
        val ctx = appContext ?: return
        synchronized(this) {
            if (wyzieFailureNotified) return
            wyzieFailureNotified = true
        }
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    Toast.makeText(ctx, "Wyzie subtitles failed ($reason) - using built-in", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun load() {
        val ctx = appContext ?: return
        cached = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        }.getOrDefault("")
        loaded = true
    }
    fun openSettings(context: Context) = SettingsUi.open(context)
}
