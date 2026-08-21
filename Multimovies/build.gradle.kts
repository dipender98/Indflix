version = 480

cloudstream {
    name = "Multimovies"
    language = "en"
    authors = listOf("Indflix")
    description = "CloudStream provider that scrapes Multimovies and pulls sources in parallel with a per-source timeout."
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "Cartoon")
    requiresResources = false
}

android {
    namespace = "com.multimovies"
}
