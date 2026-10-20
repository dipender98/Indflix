package com.indstream.foryou

import com.indstream.HttpKit
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.AccountManager

object SimklHistory {

    private const val API = "https://api.simkl.com"
    private const val PREFIX = "simkl"

    @Volatile
    private var signedIn: Boolean? = null

    fun isSignedIn(): Boolean = signedIn ?: refresh()

    fun refresh(): Boolean {
        val ok = runCatching { accounts().isNotEmpty() }.getOrDefault(false)
        signedIn = ok
        return ok
    }

    private fun accounts() = runCatching {
        AccountManager.accounts(PREFIX).filter { !it.token.accessToken.isNullOrBlank() }
    }.getOrDefault(emptyList())

    suspend fun fetch(): List<ProfileTitle> {
        val token = accounts().firstOrNull()?.token?.accessToken ?: return emptyList()
        val body = runCatching {
            app.get("$API/sync/all-items/", timeout = 15, headers = headers(token)).text
        }.getOrNull() ?: return emptyList()
        return SimklParser.parse(body)
    }

    private fun headers(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "simkl-api-key" to BuildConfig.SIMKL_CLIENT_ID,
        "User-Agent" to HttpKit.userAgent,
    )
}
