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
    defaultConfig {
        minSdk = 21
        compileSdk = 35
        targetSdk = 35
    }
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}
