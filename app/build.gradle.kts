plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

android {
    namespace = "io.github.joelee.passalong"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.joelee.passalong"
        // 29: MediaStore Downloads without a storage permission.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // The UniFFI bindings scripts/rust-android.sh generates.
            kotlin.srcDir(rootProject.projectDir.resolve("rust/generated/kotlin"))
        }
    }
}

// Builds the Rust library for every ABI and generates the bindings. Its
// inputs are the Rust sources, so an unchanged bridge is not rebuilt.
val buildRust by tasks.registering(Exec::class) {
    val rustDir = rootProject.projectDir.resolve("rust")
    inputs.dir(rustDir.resolve("src"))
    inputs.files(rustDir.resolve("Cargo.toml"), rustDir.resolve("Cargo.lock"), rustDir.resolve("uniffi.toml"))
    inputs.file(rootProject.projectDir.resolve("scripts/rust-android.sh"))
    outputs.dir(rustDir.resolve("generated/kotlin"))
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
    workingDir = rootProject.projectDir
    commandLine("./scripts/rust-android.sh")
}

tasks.named("preBuild") {
    dependsOn(buildRust)
}

// The generated bindings are in the main source set, which the linters
// read; Gradle wants their order against the task that writes them stated.
tasks.matching { it.name.contains("ktlint", ignoreCase = true) || it.name.contains("detekt", ignoreCase = true) }
    .configureEach { mustRunAfter(buildRust) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // UniFFI's Kotlin bindings load the Rust library through JNA.
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

ktlint {
    filter {
        exclude { it.file.absolutePath.contains("rust/generated") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt> {
    exclude("**/generated/**")
}

kover {
    reports {
        filters {
            excludes {
                // Generated bindings, and the Android and Compose glue that
                // only instrumented tests can exercise.
                packages(
                    "io.github.joelee.passalong.ffi",
                    "io.github.joelee.passalong.android",
                    "io.github.joelee.passalong.ui",
                )
                classes("io.github.joelee.passalong.MainActivity")
            }
        }
        verify {
            rule("Kotlin line coverage") {
                bound {
                    minValue = 80
                }
            }
        }
    }
}
