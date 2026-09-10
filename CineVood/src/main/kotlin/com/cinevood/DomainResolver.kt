package com.cinevood

/*
 * Mirror resolution for the cinevood.loan network. The pure helpers (banner
 * extraction + host normalization) are unit-testable; the health probe lives
 * in SiteApi so this file stays Android/network free.
 */

object DomainResolver {

    // Live mirror seeds, tried in this order (verified 2026-09-10).
    val SEED_MIRRORS = listOf(
        "https://cinevood.loan",
        "https://cinevoods.com",
        "https://cinevood.ltd",
        "https://cinevoodc.ltd",
        "https://moviesflixi.com"
    )

    private val BANNER_DOMS = Regex(
        """(?i)((?:www\.)?cinevood[a-z]*(?:s)?\.[a-z]{2,10}|(?:www\.)?cinevoods?\.[a-z]{2,10})"""
    )

    /** Pull candidate domains out of a "Stay updated ... ournew domain is X" banner. */
    fun bannerDomains(html: String): List<String> =
        BANNER_DOMS.findAll(html).map { normalizeDomain(it.value) }
            .filterNotNull().distinct().toList()

    /** Turn "cinevood.loan", "www.cinevood.loan" or a URL into "https://<host>". */
    fun normalizeDomain(text: String): String? {
        var t = text.trim().lowercase().removeSuffix("/")
        if (t.startsWith("http://")) t = t.removePrefix("http://")
        if (t.startsWith("https://")) t = t.removePrefix("https://")
        if (t.startsWith("www.")) t = t.removePrefix("www.")
        if (!t.contains(".")) return null
        val host = t.substringBefore("/")
        if (!host.matches(Regex("""^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$"""))) return null
        return "https://$host".removeSuffix("/")
    }

    /** True when [url] belongs to a known network base (used to rewrite cache/links). */
    fun isNetworkHost(url: String): Boolean =
        SEED_MIRRORS.any { url.startsWith(it) } ||
            Regex("""^https?://(?:www\.)?cinevood[a-z]*\.[a-z]{2,}""").matches(url.substringBeforeLast("/"))

    /** Rewrite any network URL onto the currently healthy base. */
    fun rewriteTo(url: String, base: String): String {
        val path = url.substringAfter("://").substringAfter("/")
        return if (path.isBlank()) base else "$base/$path"
    }
}
