package com.ottmirror

/**
 * Response classifier + recovery policy for the NetMirror backend.
 *
 * Live probing (Aug 2026, see project memory) established two facts the
 * HTTP status code alone cannot express:
 *
 *  1. The backend almost never answers HTTP 429. Both rate-limit and
 *     session errors come back as HTTP 200 bodies:
 *       - {"status":"n","error":"Invalid User"}  -> t_hash_t session dead.
 *         Server sessions live ~4-5 minutes, far shorter than any sane
 *         cache TTL, so this shape is the NORM on a warm cache, not an
 *         edge case.
 *       - anti-abuse text ("Too many request in short..")  -> the per-IP
 *         limiter is saturated. Mobile carrier IPs (CGNAT) are shared by
 *         many NetMirror users, so the bucket can be drained before we
 *         send a single request. No spacing policy can prevent that; the
 *         only robust response is detect -> wait -> retry.
 *
 *  2. play.php / playlist.php / the NewTV player and the stream CDN do
 *     not validate the session at all. Only post.php / episodes.php and
 *     OTT-scoped search depend on a live t_hash_t.
 *
 * Every NetMirror request in the backend runs its response through
 * [classify] and reacts per verdict instead of trusting the status code.
 */
internal object NetMirrorGuard {

    enum class Verdict { OK, LIMITED, SESSION_DEAD, DEAD }

    // Their grammar is "Too many request in short.." (singular). Match the
    // family, case-insensitively, in any body position.
    private val LIMIT_PATTERN = Regex("too many request", RegexOption.IGNORE_CASE)
    private val INVALID_USER_PATTERN = Regex("invalid\\s*user", RegexOption.IGNORE_CASE)

    fun classify(httpCode: Int, body: String?): Verdict = when {
        httpCode == 429 -> Verdict.LIMITED
        httpCode == 403 || httpCode == 502 || httpCode == 503 ||
            httpCode == 520 || httpCode == 521 || httpCode == 522 -> Verdict.DEAD
        httpCode !in 200..299 -> Verdict.DEAD
        body == null -> Verdict.OK
        isLimitedBody(body) -> Verdict.LIMITED
        isSessionDeadBody(body) -> Verdict.SESSION_DEAD
        else -> Verdict.OK
    }

    /** Anti-abuse body ("Too many request in short..") on any endpoint. */
    fun isLimitedBody(body: String): Boolean =
        body.length <= 8192 && LIMIT_PATTERN.containsMatchIn(body)

    /**
     * {"status":"n","error":"Invalid User"} — the t_hash_t session is dead.
     * Bounded in size so giant HTML pages never match by coincidence.
     */
    fun isSessionDeadBody(body: String): Boolean =
        body.length <= 4096 &&
            INVALID_USER_PATTERN.containsMatchIn(body) &&
            (body.contains("status") || body.contains("error"))

    /**
     * Reaction to a limit event. Records the cooldown and returns FALSE —
     * fail fast, exactly like the CNCVerse reference, which never waits out
     * the limiter. On a shared/CGNAT IP the bucket is drained by other users
     * and the server never sends Retry-After, so waiting 15-90 s then
     * re-firing the same request only (a) stalls the user and (b) feeds the
     * very limiter we are trying to escape. The actionable
     * [OTTMirrorBackend.limitedMessage] tells the user to retry in ~N s; a
     * later tap starts from a fresh budget.
     */
    suspend fun onLimited(attempt: Int): Boolean {
        HostThrottler.recordLimited(null)
        return false
    }

    /** Session died: drop both caches so the next verify() re-issues. */
    fun invalidateSession() {
        CookieBox.clear()
        NetMirrorCookieStore.clear()
    }
}
