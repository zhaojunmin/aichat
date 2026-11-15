package com.octos.aichat.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which package may import which. Each rule lists everything a package is allowed
 * to use from the rest of the app; anything else fails the build.
 *
 * Allowed entries use Konsist's pattern style: `"data"` is that package only,
 * `"diagnostics.."` includes its subpackages. Single types allowed across a
 * boundary are listed by name, each with the reason it is an exception.
 *
 * Konsist's layer DSL isn't used because it rejects cycles, and `ai` ⇄ `data.memory`
 * is a known one (see [aiOnlyUsesSettingsAndDiagnostics]).
 */
class PackageDependencyRulesTest {

    /** Diagnostics is the leaf everyone reports into; it can't know about any feature. */
    @Test fun diagnosticsDependsOnNothing() = assertPackage("diagnostics..", allowed = emptySet())

    /** The screen reader only reads and fills; it never reaches up into AI, OCR or UI. */
    @Test fun accessibilityOnlyUsesSettingsAndDiagnostics() =
        assertPackage("accessibility..", allowed = setOf("data", "diagnostics.."))

    /** OCR only needs the message geometry type from the reader, not the reader itself. */
    @Test fun ocrOnlyUsesMessageGeometryFromAccessibility() = assertPackage(
        "ocr..",
        allowed = setOf("data", "diagnostics.."),
        allowedTypes = setOf("accessibility.MessageBounds"),
    )

    /**
     * The AI layer works on settings and conversations.
     *
     * Known cycle: it also parses learning results into `data.memory` types, while
     * `data.memory` calls back into `ai` to learn. Moving those result types out of
     * `data.memory` would break the cycle; until then they're listed here so the
     * cycle can't grow.
     */
    @Test fun aiOnlyUsesSettingsAndDiagnostics() = assertPackage(
        "ai..",
        allowed = setOf("data", "diagnostics.."),
        allowedTypes = setOf(
            "data.memory.MemoryDraft",
            "data.memory.MemoryEdit",
            "data.memory.MemoryEntity",
            "data.memory.MemoryLearnResult",
        ),
    )

    /** Settings, conversation and secrets are plain data: no AI, no memory, no UI. */
    @Test fun dataRootOnlyUsesDiagnostics() = assertPackage(
        "data",
        allowed = setOf("diagnostics.."),
    )

    @Test fun memoryUsesSettingsAiAndDiagnostics() =
        assertPackage("data.memory..", allowed = setOf("data", "ai..", "diagnostics.."))

    /**
     * The overlay orchestrates everything below it. From `ui` it may only take the
     * theme, the activity a notification opens, and the shared error wording.
     */
    @Test fun bubbleUsesUiOnlyForThemeNotificationAndErrorText() = assertPackage(
        "bubble..",
        allowed = setOf(
            "accessibility..",
            "ai..",
            "data..",
            "diagnostics..",
            "ocr..",
            "ui.theme..",
        ),
        allowedTypes = setOf("ui.MainActivity", "ui.toUserMessage"),
    )

    /** Settings screens never parse the screen; they only check the reader is enabled. */
    @Test fun uiNeverUsesOcrAndOnlyNamesTheAccessibilityService() = assertPackage(
        "ui..",
        allowed = setOf("ai..", "bubble..", "data..", "diagnostics.."),
        allowedTypes = setOf("accessibility.ChatAccessibilityService"),
    )

    /** The in-process bus wires services to the UI; data, AI and OCR code must not reach for it. */
    @Test fun onlyServicesAndUiUseAppBus() {
        val users = setOf("accessibility", "bubble", "ui")
        val violations = appFiles()
            .filter { file ->
                val top = file.appPackage().substringBefore('.')
                top.isNotEmpty() && top !in users
            }
            .filter { file -> file.imports.any { it.name == "$ROOT.AppBus" } }
            .map { "  ${it.appPackage()}/${it.name}.kt" }
        assertTrue(
            "AppBus is for accessibility / bubble / ui only:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ---- helpers -------------------------------------------------------------

    private fun assertPackage(
        pkg: String,
        allowed: Set<String>,
        allowedTypes: Set<String> = emptySet(),
    ) {
        val files = appFiles().filter { matches(it.appPackage(), pkg) }
        check(files.isNotEmpty()) { "no files in $pkg — rule is stale" }
        val violations = files.flatMap { file ->
            file.imports
                .filter { violates(it.name, pkg, allowed, allowedTypes) }
                .map { "  ${file.appPackage()}/${file.name}.kt imports ${it.name}" }
        }
        val header = "$pkg may only import ${allowed + allowedTypes} from the app:"
        assertTrue(header + "\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    private fun violates(
        import: String,
        pkg: String,
        allowed: Set<String>,
        allowedTypes: Set<String>,
    ): Boolean {
        if (!import.startsWith("$ROOT.")) return false
        val target = import.removePrefix("$ROOT.")
        val targetPkg = packageOf(target)
        // Root types (AppBus, R, BuildConfig) are covered by onlyServicesAndUiUseAppBus.
        if (targetPkg.isEmpty()) return false
        if (matches(targetPkg, pkg)) return false // inside its own package
        if (target in allowedTypes) return false
        return allowed.none { matches(targetPkg, it) }
    }

    private companion object {
        const val ROOT = "com.octos.aichat"

        val scope by lazy { Konsist.scopeFromProduction(moduleName = "app") }

        fun appFiles(): List<KoFileDeclaration> =
            scope.files.filter { it.packagee?.name?.startsWith(ROOT) == true }

        /** The package relative to [ROOT], "" for the root package. */
        fun KoFileDeclaration.appPackage(): String =
            packagee?.name.orEmpty().removePrefix(ROOT).removePrefix(".")

        /** `"data"` matches only that package; `"data.."` also matches its subpackages. */
        fun matches(pkg: String, pattern: String): Boolean = if (pattern.endsWith("..")) {
            val base = pattern.removeSuffix("..")
            pkg == base || pkg.startsWith("$base.")
        } else {
            pkg == pattern
        }

        /**
         * Package part of a relative import: the segments before the first type name.
         * With no type name (`ui.toUserMessage`, a top-level function) the last segment
         * is the member, so it's dropped.
         */
        fun packageOf(relativeImport: String): String {
            val parts = relativeImport.split('.')
            val firstType = parts.indexOfFirst { it.first().isUpperCase() }
            val end = if (firstType >= 0) firstType else parts.size - 1
            return parts.take(end).joinToString(".")
        }
    }
}
