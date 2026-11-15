import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

// Read signing credentials from local.properties (kept out of version control).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = localProps.getProperty("RELEASE_STORE_FILE") != null

android {
    namespace = "com.octos.aichat"
    compileSdk = 36

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(localProps.getProperty("RELEASE_STORE_FILE"))
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.octos.aichat"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // androidTest holds only microbenchmarks (see testBuildType below).
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    // Microbenchmarks must not run against a debuggable build (JIT/ART debug paths
    // distort timings), so androidTest targets the non-debuggable "benchmark" type.
    testBuildType = "benchmark"

    buildTypes {
        release {
            // R8 shrink + obfuscate code, and strip unused resources — the big
            // levers for a Compose + ML Kit app.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // ML Kit bundles native .so for 4 ABIs; real phones are ARM, so drop
            // the x86/x86_64 (emulator-only) copies to roughly halve native size.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildTypes {
        // Release behaviour minus R8 (so androidTest microbenchmarks can call app
        // classes directly). Not shipped. Signed like release when that key is
        // available, so it installs over the user's release build without wiping
        // its data (API key, memories, downloaded models); else debug-signed.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName(if (hasReleaseSigning) "release" else "debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        // No baseline: the codebase is lint-clean, so any new warning fails the build.
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        // "A newer version is available" checks depend on the network and the date,
        // not on our code — they'd make CI results drift. Track upgrades separately.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // ARM-only on purpose (see abiFilters below): the native libs ship for ARM, and x86_64
        // ChromeOS devices aren't a target.
        disable += "ChromeOsAbiSupport"
        xmlReport = true
        htmlReport = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room writes one schema JSON per database version here (checked in), so future
// migrations can be verified against the exact previous schema.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.okhttp)

    // Installs the bundled baseline profiles (Compose ships them) on sideloaded
    // installs. Compose only pulls in 1.3.1, which can't install on API 34+, so
    // newer phones started without them. Pin a version that supports them.
    implementation(libs.androidx.profileinstaller)

    // Global memory: learned user profile/facts live in a Room DB.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // On-device Chinese OCR, bundled model (no Google Play services needed — works on Huawei).
    implementation(libs.mlkit.text.recognition.chinese)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.benchmark.junit4)
    // The benchmark runner first launches IsolationActivity in the *app* process;
    // in an application module it must therefore ship in the app's benchmark
    // variant (its manifest merges in). Benchmark build type only — not release.
    "benchmarkImplementation"(libs.androidx.benchmark.common)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
