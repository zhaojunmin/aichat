plugins {
    alias(libs.plugins.android.test)
}

// Macrobenchmarks: drive the real, R8-optimised release app from outside
// (startup time, scroll jank). Run on a physical device; see docs/performance-testing.md.
android {
    namespace = "com.octos.aichat.macrobenchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Measure the release variant: what users actually run (minified, non-debuggable).
    buildTypes {
        create("release") {
            // The test APK itself just needs to install; sign it with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}

// Only the release variant is meaningful here.
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}
