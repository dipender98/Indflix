package com.indstream.foryou

object ForYouRefresh {

    enum class Mode(val id: String, val label: String, val summary: String) {
        EVERY_OPEN("open", "Every app open", "Rebuilds each time you open the app."),
        TWICE_DAILY("twice", "Twice a day", "Two rebuilds a day, at least 12 hours apart."),
        EVERY_SIX_HOURS("six", "Every 6 hours", "Four rebuilds a day, six hours apart.");

        companion object {
            fun from(id: String?): Mode = entries.firstOrNull { it.id == id } ?: EVERY_OPEN
        }
    }

    const val TWICE_PER_DAY = 2

    const val DAY_MS = 24L * 60 * 60 * 1000

    const val TWICE_DAILY_GAP_MS = 12L * 60 * 60 * 1000

    const val SIX_HOURS_MS = 6L * 60 * 60 * 1000

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
