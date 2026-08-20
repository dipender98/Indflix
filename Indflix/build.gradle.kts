version = 1

cloudstream {
    // The id of the plugin. This is used to uniquely identify the plugin.
    // It must not contain any uppercase letters.
    pluginName = "indflix"
    // The display name of the plugin. This is shown to the user.
    pluginClassName = "IndflixProvider"
    language = "en"
    authors = listOf("Indflix")
    description = "CloudStream provider that scrapes Multimovies and pulls sources in parallel with a per-source timeout."
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "Cartoon")
    requiresResources = false
}

android {
    namespace = "com.indflix"
    defaultConfig {
        minSdk = 21
        compileSdkVersion(35)
        targetSdk = 35
    }
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}
