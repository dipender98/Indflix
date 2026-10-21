package com.indstream.foryou

import kotlin.math.exp

data class ProfileTitle(
    val tmdbId: Int?,
    val imdbId: String?,
    val title: String,
    val year: Int?,
    val type: String,
    val watched: Boolean,
    val rating: Double,
    val lastActivityAt: Long,
    val status: String = "",
) {
    val dropped: Boolean get() = status == "dropped"

    val weight: Double
        get() {
            val ageDays = if (lastActivityAt > 0) {
                (System.currentTimeMillis() - lastActivityAt).coerceAtLeast(0L) / 86_400_000.0
            } else {
                null
            }
            val decay = ageDays?.let { exp(-it / 540.0) } ?: 0.55
            val ratingAdjust = when {
                rating <= 0.0 -> 0.0
                rating <= 4.0 -> -1.3
                rating < 7.0 -> (rating - 7.0) * 0.15
                else -> (rating - 7.0) * 0.25
            }
            val base = when {
                dropped -> -1.0
                watched -> 1.0 + ratingAdjust
                status == "hold" -> 0.45 + ratingAdjust.coerceAtMost(0.2)
                else -> 0.55 + ratingAdjust.coerceAtMost(0.2)
            }
            return base * decay
        }
}
