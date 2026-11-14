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
    }

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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        // Fail the build on new lint errors; pre-existing issues are frozen in the
        // baseline so only regressions are flagged. Regenerate with :app:updateLintBaseline.
        abortOnError = true
        checkDependencies = false
        baseline = file("lint-baseline.xml")
        // "A newer version is available" checks depend on the network and the date,
        // not on our code — they'd make CI results drift. Track upgrades separately.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        xmlReport = true
        htmlReport = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
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

    // Global memory: learned user profile/facts live in a Room DB.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // On-device Chinese OCR, bundled model (no Google Play services needed — works on Huawei).
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}
