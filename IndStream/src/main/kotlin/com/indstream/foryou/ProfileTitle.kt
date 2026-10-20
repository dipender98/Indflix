package com.indstream.foryou

data class ProfileTitle(
    val tmdbId: Int?,
    val imdbId: String?,
    val title: String,
    val year: Int?,
    val type: String,
    val watched: Boolean,
    val rating: Double,
    val lastActivityAt: Long,
) {
    val weight: Double
        get() {
            var w = if (watched) 1.0 else 0.6
            if (rating > 0) w += (rating / 10.0) * 0.4
            return w
        }
}
