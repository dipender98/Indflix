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
    description = "CloudStream provider that scrapes Vegamovies (Hollywood + Bollywood) with TMDB metadata and live-fill download servers."
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/dipender98/Indflix")
    iconUrl = "https://raw.githubusercontent.com/dipender98/Indflix/main/Vegamovies/icon.png"
}

android {
    namespace = "com.vegamovies"
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")

    val csJar = File(project.gradle.gradleUserHomeDir, "caches/cloudstream/cloudstream/cloudstream.jar")
    if (csJar.exists()) {
        testImplementation(files(csJar))
    }
    // MainAPI's companion initializes a kotlinx-serialization Json builder at
    // class-load time — the json artifact must be on the unit-test runtime path.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // ...and a Jackson JsonMapper (cloudstream3's NiceHttp JSON layer).
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
}

// Mirrors the Multimovies shrinkCs3 task: re-dex the R8-minified release classes
// into the shipped .cs3 instead of the unshrunk d8 output.
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
    description = "Repackages Vegamovies.cs3 using the R8-minified release classes."
    dependsOn("make", "minifyReleaseWithR8")
    inputs.files(shrunkJar, manifestJson)
    outputs.file(layout.buildDirectory.file("Vegamovies.cs3"))

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

        val target = layout.buildDirectory.file("Vegamovies.cs3").get().asFile
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

tasks.named("writeCacheEntry") { dependsOn("shrinkCs3") }
