package com.indstream.foryou

import com.indstream.HttpKit
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.AccountManager

/** Reads the watch and list history of the account linked inside the host app. */
object SimklHistory {

    private const val API = "https://api.simkl.com"
    private const val PREFIX = "simkl"

    @Volatile
    private var signedIn: Boolean? = null

    /** True when the host app holds an account for the sync provider. */
    fun isSignedIn(): Boolean = signedIn ?: refresh()

    /** Re-checks the stored accounts and caches the result. */
    fun refresh(): Boolean {
        val ok = runCatching { accounts().isNotEmpty() }.getOrDefault(false)
        signedIn = ok
        return ok
    }

    /** Stored accounts that carry a usable token. */
    private fun accounts() = runCatching {
        AccountManager.accounts(PREFIX).filter { !it.token.accessToken.isNullOrBlank() }
    }.getOrDefault(emptyList())

    /** Full history list. Empty when signed out or the call fails. */
    suspend fun fetch(): List<ProfileTitle> {
        val token = accounts().firstOrNull()?.token?.accessToken ?: return emptyList()
        val body = runCatching {
            app.get("$API/sync/all-items/", timeout = 15, headers = headers(token)).text
        }.getOrNull() ?: return emptyList()
        return SimklParser.parse(body)
    }

    /** Auth headers for the account API. */
    private fun headers(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "simkl-api-key" to BuildConfig.SIMKL_CLIENT_ID,
        "User-Agent" to HttpKit.userAgent,
    )
}
