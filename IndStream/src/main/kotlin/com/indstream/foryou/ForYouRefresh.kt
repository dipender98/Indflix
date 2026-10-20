package com.indstream.foryou

/** How often the personalised rows are rebuilt. */
object ForYouRefresh {

    /** Rebuild modes the user can pick. */
    enum class Mode(val id: String, val label: String, val summary: String) {
        EVERY_OPEN("open", "Every app open", "Rebuilds each time you open the app."),
        TWICE_DAILY("twice", "Twice a day", "Two rebuilds a day, at least 12 hours apart."),
        EVERY_SIX_HOURS("six", "Every 6 hours", "Four rebuilds a day, six hours apart.");

        companion object {
            /** Mode for a stored id; unknown ids fall back to the first one. */
            fun from(id: String?): Mode = entries.firstOrNull { it.id == id } ?: EVERY_OPEN
        }
    }

    /** Rebuilds allowed per day in the twice-a-day mode. */
    const val TWICE_PER_DAY = 2

    /** The daily budget window. */
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** Minimum gap between the two daily rebuilds. */
    const val TWICE_DAILY_GAP_MS = 12L * 60 * 60 * 1000

    /** Lifetime of a row in the six-hours mode. */
    const val SIX_HOURS_MS = 6L * 60 * 60 * 1000

    /** True when the stored row has to be rebuilt. Pure. */
    fun isStale(
        mode: Mode,
        now: Long,
        builtAt: Long,
        builtSession: Long,
        currentSession: Long,
        buildsInWindow: Int,
    ): Boolean {
        if (builtAt <= 0L) return true
        return when (mode) {
            Mode.EVERY_OPEN -> builtSession != currentSession
            Mode.TWICE_DAILY ->
                buildsInWindow < TWICE_PER_DAY &&
                    (buildsInWindow == 0 || now - builtAt >= TWICE_DAILY_GAP_MS)
            Mode.EVERY_SIX_HOURS -> now - builtAt > SIX_HOURS_MS
        }
    }
}
