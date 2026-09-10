plugins {
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    // Only used as a fallback when the plugin id/first letters cannot be used,
    // shown in the repository list
    language = "hi"

    // All authors that will be shown on repository
    authors = listOf("Indflix")

    /**
     * Status as follows:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )

    requiresResources = false
    iconUrl = "https://raw.githubusercontent.com/dipender98/Indflix/main/CineVood/icon.png"
}

// Shared root config pre-sets namespace "com.example"; override + make pure JVM
// unit tests tolerate android.jar stubs.
extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    namespace = "com.cinevood"
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")

    // recloudstream/gradle materializes the CloudStream classes jar into
    // <gradle home>/caches/cloudstream/... and wires it into compileOnly;
    // unit tests run outside the app so they need the same jar on the test
    // classpath (ExtractorLink loads its companion via kotlinx-serialization).
    val csJar = File(project.gradle.gradleUserHomeDir, "caches/cloudstream/cloudstream/cloudstream.jar")
    if (csJar.exists()) {
        testImplementation(files(csJar))
    }
}
