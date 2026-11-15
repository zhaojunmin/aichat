plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.spotless)
}

// Kotlin style check (Android Kotlin style guide, via ktlint's android_studio style).
// Rules live in .editorconfig. `spotlessCheck` fails on violations; `spotlessApply` fixes
// what ktlint can fix automatically (naming, line length etc. need a manual edit).
spotless {
    kotlin {
        target("app/src/**/*.kt", "quality/*/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}
