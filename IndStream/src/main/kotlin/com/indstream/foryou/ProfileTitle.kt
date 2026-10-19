package com.indstream.foryou

/** One title the user saw or liked, with the signal that produced it. */
data class ProfileTitle(
    val tmdbId: Int?,
    val imdbId: String?,
    val title: String,
    val year: Int?,
    /** "movie" or "tv". */
    val type: String,
    /** True when the title was played, false when it only sits in a list. */
    val watched: Boolean,
    /** Personal score 0-10, 0 when unknown. */
    val rating: Double,
    /** Last activity in epoch millis, 0 when unknown. */
    val lastActivityAt: Long,
) {
    /** How strongly this title hints at the user's taste. */
    val weight: Double
        get() {
            var w = if (watched) 1.0 else 0.6
            if (rating > 0) w += (rating / 10.0) * 0.4
            return w
        }
}
