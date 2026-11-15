plugins {
    // Kotlin/JVM ships with AGP 9's built-in Kotlin, so no version here.
    id("org.jetbrains.kotlin.jvm")
}

// Architecture, package-dependency and naming rules for :app, written as Konsist
// unit tests. Konsist parses the app's sources from disk, so this module needs no
// dependency on :app and adds nothing to the app's own build.
// Run: ./gradlew :quality:architecture:test
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.konsist)
}

tasks.test {
    // Konsist reads source files, not compiled classes: rerun when the app's code changes.
    inputs.dir(rootProject.file("app/src/main")).withPropertyName("appSources")
}
