import com.android.build.api.dsl.LibraryExtension
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

version = 86

plugins {
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    language = "hi"
    authors = listOf("Indflix")
    description = "Federated embed-server resolver keyed by TMDB/IMDB id. Races dozens of independent HLS/DASH servers, picks the fastest CDN per title, and exposes multi-language audio + per-quality links."
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Live")
    requiresResources = false
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/dipender98/Indflix")
    iconUrl = "https://raw.githubusercontent.com/dipender98/Indflix/main/IndStream/icon.png"
}

android {
    namespace = "com.indstream"
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            test.testClassesDirs = files(
                layout.buildDirectory.dir(
                    "intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"
                )
            )
        }
    }
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")

    val csJar = File(project.gradle.gradleUserHomeDir, "caches/cloudstream/cloudstream/cloudstream.jar")
    if (csJar.exists()) {
        testImplementation(files(csJar))
    }
    val cryptoJars = fileTree(
        mapOf(
            "dir" to File(project.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/dev.whyoleg.cryptography"),
            "include" to listOf("**/*.jar"),
        )
    ).files.filter { !it.name.contains("sources") && !it.name.contains("javadoc") }
    if (cryptoJars.isNotEmpty()) {
        testImplementation(files(cryptoJars))
    }
    testImplementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
    testImplementation("dev.whyoleg.cryptography:cryptography-random:0.6.0")
    testImplementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.6.0")
    val serialJars = fileTree(
        mapOf(
            "dir" to File(project.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/org.jetbrains.kotlinx"),
            "include" to listOf("kotlinx-serialization-json-jvm/**/*.jar", "kotlinx-serialization-core-jvm/**/*.jar"),
        )
    ).files.filter { !it.name.contains("sources") && !it.name.contains("javadoc") }
    if (serialJars.isNotEmpty()) {
        testImplementation(files(serialJars))
    }
    val jacksonJars = fileTree(
        mapOf(
            "dir" to File(project.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/com.fasterxml.jackson.core"),
            "include" to listOf("jackson-databind/**/*.jar", "jackson-core/**/*.jar", "jackson-annotations/**/*.jar"),
        )
    ).files.filter { !it.name.contains("sources") && !it.name.contains("javadoc") } +
        fileTree(
            mapOf(
                "dir" to File(project.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/com.fasterxml.jackson.module"),
                "include" to listOf("jackson-module-kotlin/**/*.jar"),
            )
        ).files.filter { !it.name.contains("sources") && !it.name.contains("javadoc") }
    if (jacksonJars.isNotEmpty()) {
        testImplementation(files(jacksonJars))
    }
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
    testImplementation(kotlin("reflect"))
    val reflectJars = fileTree(
        mapOf(
            "dir" to File(project.gradle.gradleUserHomeDir, "caches/modules-2/files-2.1/org.jetbrains.kotlin"),
            "include" to listOf("kotlin-reflect/**/*.jar"),
        )
    ).files.filter { !it.name.contains("sources") && !it.name.contains("javadoc") }
    if (reflectJars.isNotEmpty()) {
        testImplementation(files(reflectJars))
    }
}

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
    description = "Repackages IndStream.cs3 using the R8-minified release classes."
    dependsOn("make", "minifyReleaseWithR8")
    inputs.files(shrunkJar, manifestJson)
    outputs.file(layout.buildDirectory.file("IndStream.cs3"))

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

        val target = layout.buildDirectory.file("IndStream.cs3").get().asFile
        val tmp = File(target.parentFile, target.name + ".tmp")
        ZipOutputStream(tmp.outputStream()).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)
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

tasks.matching { it.name == "writeCacheEntry" }.configureEach {
    mustRunAfter("shrinkCs3")
    dependsOn("shrinkCs3")
}
