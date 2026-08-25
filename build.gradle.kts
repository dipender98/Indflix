import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.0")
        classpath("com.github.recloudstream.gradle:gradle:81b1d424d2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.android(configuration: LibraryExtension.() -> Unit) =
    extensions.getByName<LibraryExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")

    android {
        namespace = "com.example"
        compileSdk = 36
        defaultConfig {
            minSdk = 21
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        testOptions {
            unitTests.all { it.useJUnitPlatform() }
        }
        buildTypes {
            release {
                isMinifyEnabled = true
                isShrinkResources = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            debug {
                isMinifyEnabled = false
                isShrinkResources = false
            }
        }
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                listOf(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            )
        }
    }

    dependencies {
        val compileOnly by configurations
        val testImplementation by configurations
        testImplementation(kotlin("test"))
        testImplementation(kotlin("test-junit5"))
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        testImplementation("org.mockito:mockito-core:5.7.0")
        testImplementation("org.mockito:mockito-inline:5.2.0")
        // These are all provided by the CloudStream app at runtime, so they must be
        // compileOnly: bundling them into the plugin dex is what made the .cs3 ~100KB.
        compileOnly(kotlin("stdlib"))
        compileOnly("com.github.Blatzar:NiceHttp:0.4.18")
        compileOnly("org.jsoup:jsoup:1.22.2")
        compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        // JVM unit tests execute outside the app, so re-add the libs for test runtime.
        testImplementation("com.github.Blatzar:NiceHttp:0.4.18")
        testImplementation("org.jsoup:jsoup:1.22.2")
        testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        // android.jar stubs org.json; the real implementation is needed so
        // NxshaProtocol's envelope/parsing logic runs in unit tests.
        testImplementation("org.json:json:20240303")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}