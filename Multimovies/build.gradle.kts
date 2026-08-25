import com.android.build.api.dsl.LibraryExtension
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

version = 2

plugins {
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    language = "hi"
    authors = listOf("Indflix")
    description = "CloudStream provider that scrapes Multimovies and pulls sources in parallel with a per-source timeout."
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "Cartoon")
    requiresResources = false
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/dipender98/Indflix")
    // Icon served from the repo; CloudStream fetches it from plugins.json.
    iconUrl = "https://raw.githubusercontent.com/dipender98/Indflix/main/Multimovies/icon.png"
}

android {
    namespace = "com.multimovies"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}

// The CloudStream gradle plugin's `make` dexes the unshrunk classes with plain
// d8, ignoring AGP's minify settings, so the default .cs3 ships every class and
// method unminified. shrinkCs3 re-dexes the R8 output of the release variant
// (minifyReleaseWithR8 -> shrunkClasses.jar, built from proguard-rules.pro) and
// repackages Multimovies.cs3 with the shrunken dex instead.
val androidExtension = extensions.getByType(LibraryExtension::class.java)
val shrunkJar = layout.buildDirectory.file(
    "intermediates/shrunk_classes/release/minifyReleaseWithR8/shrunkClasses.jar"
)
val manifestJson = layout.buildDirectory.file("intermediates/manifest.json")
val androidJar = providers.provider {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    val sdkDir = props.getProperty("sdk.dir")?.replace("\\", File.separator)
        ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: throw GradleException("Android SDK directory not found")
    val compileSdk = androidExtension.compileSdk ?: throw GradleException("compileSdk not set")
    File(sdkDir, "platforms/android-$compileSdk/android.jar")
}

tasks.register("shrinkCs3") {
    group = "build"
    description = "Repackages Multimovies.cs3 using the R8-minified release classes."
    dependsOn("make", "minifyReleaseWithR8")
    inputs.files(shrunkJar, manifestJson)
    outputs.file(layout.buildDirectory.file("Multimovies.cs3"))

    doLast {
        val boot = androidJar.get()
        if (!boot.isFile) throw GradleException("android.jar not found at $boot")

        val outDir = layout.buildDirectory.dir("shrunkDex").get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
        val d8Args = listOf(
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
            "-cp", rootProject.buildscript.configurations.getByName("classpath").asPath,
            "com.android.tools.r8.D8",
            "--release",
            "--min-api", "21",
            "--lib", boot.absolutePath,
            "--output", outDir.absolutePath,
            shrunkJar.get().asFile.absolutePath,
        )
        val proc = Runtime.getRuntime().exec(d8Args.toTypedArray())
        val errText = proc.errorStream.bufferedReader().readText()
        val exit = proc.waitFor()
        if (exit != 0) throw GradleException("D8 failed (exit $exit): $errText")

        val target = layout.buildDirectory.file("Multimovies.cs3").get().asFile
        val tmp = File(target.parentFile, target.name + ".tmp")
        ZipOutputStream(tmp.outputStream()).use { zos ->
            fun put(name: String, data: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
            put("manifest.json", manifestJson.get().asFile.readBytes())
            put("classes.dex", outDir.resolve("classes.dex").readBytes())
        }
        if (target.exists() && !target.delete()) throw GradleException("Cannot replace $target")
        if (!tmp.renameTo(target)) throw GradleException("Cannot finalize $target")
        println("Shrunk CloudStream package at $target (${target.length()} bytes)")
    }
}

tasks.named("make") { finalizedBy("shrinkCs3") }

// makePluginsJson -> writeCacheEntry reads build/Multimovies.cs3, which shrinkCs3
// replaces. Declare the dependency explicitly (Gradle 9 fails otherwise).
tasks.named("writeCacheEntry") { dependsOn("shrinkCs3") }
