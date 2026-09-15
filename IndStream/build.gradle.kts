import com.android.build.api.dsl.LibraryExtension
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

version = 38

plugins {
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    language = "hi"
    authors = listOf("Indflix")
    description = "Federated embed-server resolver keyed by TMDB/IMDB id. Races dozens of independent HLS/DASH servers, picks the fastest CDN per title, and exposes multi-language audio + per-quality links."
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/dipender98/Indflix")
    // Icon served from the repo; CloudStream fetches it from plugins.json.
    iconUrl = "https://raw.githubusercontent.com/dipender98/Indflix/main/IndStream/icon.png"
}

android {
    namespace = "com.indstream"
    // JVM unit tests (AutoPlayPickTest etc.) exercise engine code that logs
    // via android.util.Log — return no-op defaults instead of "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")

    // The recloudstream gradle plugin materializes the CloudStream classes jar
    // at <gradle home>/caches/cloudstream/... and wires it into `compileOnly`
    // via a file dependency — which is why `cloudstream3:pre-release` shows
    // FAILED in dependency reports yet compilation works. Unit tests that touch
    // ExtractorLink (NeutralOrderTest asserts emitted link quality/shape) need
    // the SAME jar on the test classpath. Run any compile task once to
    // materialize it.
    val csJar = File(project.gradle.gradleUserHomeDir, "caches/cloudstream/cloudstream/cloudstream.jar")
    if (csJar.exists()) {
        testImplementation(files(csJar))
    }
    // newExtractorLink's class-init needs the cryptography provider at runtime;
    // same guarded pattern (globbed so artifact versions can roll).
    val cryptoJars = fileTree(
        mapOf(
            "dir" to File(project.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/dev.whyoleg.cryptography"),
            "include" to listOf("**/*.jar"),
        )
    ).files.filter { !it.name.contains("sources") && !it.name.contains("javadoc") }
    if (cryptoJars.isNotEmpty()) {
        testImplementation(files(cryptoJars))
    }
}

// The CloudStream gradle plugin's `make` dexes the unshrunk classes with plain
// d8, ignoring AGP's minify settings, so the default .cs3 ships every class and
// method unminified. shrinkCs3 re-dexes the R8 output of the release variant
// (minifyReleaseWithR8 -> shrunkClasses.jar, built from proguard-rules.pro) and
// repackages IndStream.cs3 with the shrunken dex instead.
val androidExtension = extensions.getByType(LibraryExtension::class.java)
val shrunkJar = layout.buildDirectory.file(
    "intermediates/shrunk_classes/release/minifyReleaseWithR8/shrunkClasses.jar"
)
val manifestJson = layout.buildDirectory.file("intermediates/manifest.json")
val androidJar = providers.provider {
    val props = Properties()
