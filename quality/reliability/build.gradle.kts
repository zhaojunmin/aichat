plugins {
    alias(libs.plugins.android.test)
}

// Device-level reliability tests: drive the real release app from outside and
// disturb it the way a phone does — process crash, memory pressure, permissions
// revoked, network loss, configuration changes — then check it recovers without
// crashing. Run on a physical device; see docs/reliability-testing.md.
android {
    namespace = "com.octos.aichat.reliability"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Test what users run: the minified, non-debuggable release build.
    buildTypes {
        create("release") {
            // The test APK itself just needs to install; sign it with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    targetProjectPath = ":app"
    // Instrument this test APK itself (the release app isn't instrumentable);
    // the app is driven from outside via UiAutomator and shell commands.
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
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}

// Only the release variant is meaningful here.
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}
