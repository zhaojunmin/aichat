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
 * The rules are written out per package rather than with Konsist's layer DSL, so each
 * can carry the reason for its exceptions.
 */
class PackageDependencyRulesTest {

    /** Diagnostics is the leaf everyone reports into; it can't know about any feature. */
    @Test fun diagnosticsDependsOnNothing() = assertPackage("diagnostics..", allowed = emptySet())

    /**
     * Developer-tool state (last read, raw tree dump) lives in `capture` next to the read
     * it records, but holds plain data and nothing else.
     */
    @Test fun debugCaptureOnlyUsesData() {
        val file = appFiles().singleOrNull {
            it.appPackage() == "capture" &&
                it.name == "DebugCapture"
        }
        checkNotNull(file) { "capture/DebugCapture.kt not found — rule is stale" }
        val violations = file.appReferences()
            .map { it.removePrefix("$ROOT.") }
            .filter { packageOf(it).isNotEmpty() && packageOf(it) != "data" }
        assertTrue("DebugCapture may only use data: $violations", violations.isEmpty())
    }

    /**
     * Only the reader (feeds the raw-tree dump), the capture flow (records what it read)
     * and the settings screens (show both) touch the developer-tool state; the overlay
     * may use `capture` for reading, but not this.
     */
    @Test fun onlyReaderCaptureAndUiUseDebugCapture() {
        val users = setOf("accessibility", "capture", "ui")
        val violations = appFiles()
            .filter { it.appPackage().substringBefore('.') !in users }
            .filter { file -> file.appReferences().any { it == "$ROOT.capture.DebugCapture" } }
            .map { "  ${it.appPackage()}/${it.name}.kt" }
        assertTrue(
            "only $users may use DebugCapture:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * The screen reader only reads and fills; it never reaches up into AI, OCR or UI.
     * It may feed the raw-tree dump to the developer tools.
     *
     * Known cycle: `capture` depends on this package (the `ScreenAccess` port), and this
     * package writes the raw-tree dump into `capture.DebugCapture`. Only that one type is
     * allowed, so the cycle can't grow.
     */
    @Test fun accessibilityOnlyUsesSettingsDiagnosticsAndDebugCapture() = assertPackage(
        "accessibility..",
        allowed = setOf("data", "diagnostics.."),
        allowedTypes = setOf("capture.DebugCapture"),
    )

    /** OCR only needs the message geometry type from the reader, not the reader itself. */
    @Test fun ocrOnlyUsesMessageGeometryFromAccessibility() = assertPackage(
        "ocr..",
        allowed = setOf("data", "diagnostics.."),
        allowedTypes = setOf("accessibility.MessageBounds"),
    )

    /**
     * The AI layer talks to models: settings and conversations in, text out. It knows
     * nothing of memory — learning (`data.memory.MemoryLearner`) calls into it, never the
     * other way round.
     */
    @Test fun aiOnlyUsesSettingsAndDiagnostics() =
        assertPackage("ai..", allowed = setOf("data", "diagnostics.."))

    /**
     * Outside the AI layer, models are reached only through `AiClient`: the interface, the
     * failure it throws, the factory that picks cloud or on-device, and the diagnostics hook.
     * Prompting and parsing live with their feature (suggestions in `bubble`, learning in
     * `data.memory`). The on-device runtime (`ai.local`) and embeddings (`ai.embedding`)
     * are a separate concern and not covered here.
     */
    @Test fun aiIsReachedOnlyThroughAiClient() {
        val port = setOf("AiClient", "AiException", "aiClientFor", "recordAiFailure")
        val violations = appFiles()
            .filterNot { matches(it.appPackage(), "ai..") }
            .flatMap { file ->
                file.appReferences()
                    .map { it.removePrefix("$ROOT.") }
                    .filter { packageOf(it) == "ai" }
                    .filter { it.removePrefix("ai.").substringBefore('.') !in port }
                    .map { "  ${file.appPackage()}/${file.name}.kt uses $it" }
            }
        assertTrue(
            "outside ai, only $port may be used from the ai package:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /** Settings, conversation and secrets are plain data: no AI, no memory, no UI. */
    @Test fun dataRootOnlyUsesDiagnostics() = assertPackage(
        "data",
        allowed = setOf("diagnostics.."),
    )

    @Test fun memoryUsesSettingsAiAndDiagnostics() =
        assertPackage("data.memory..", allowed = setOf("data", "ai..", "diagnostics.."))

    /** Reading the chat for a tap: the screen port (never the concrete reader) and OCR. */
    @Test fun captureUsesScreenPortAndOcr() = assertPackage(
        "capture..",
        allowed = setOf("data", "diagnostics..", "ocr.."),
        allowedTypes = setOf("accessibility.ScreenAccess", "accessibility.ChatScreenshot"),
    )

    /**
     * The overlay: window, panel and the tap → suggestions flow. It reads the screen
     * through [capture] and only takes the bridge (connection, foreground app) from
     * the reader. From `ui` it may only take the theme, the activity a notification
     * opens, and the shared error wording.
     */
    @Test fun bubbleReadsViaCaptureAndUsesUiOnlyForThemeAndErrorText() = assertPackage(
        "bubble..",
        allowed = setOf("ai..", "capture", "data..", "diagnostics..", "ui.theme.."),
        allowedTypes = setOf(
            "accessibility.AccessibilityBridge",
            "ui.toUserMessage",
        ),
    )

    /** Settings screens never parse the screen; they only check the reader is enabled / connected. */
    @Test fun uiNeverUsesOcrAndOnlyNamesTheAccessibilityService() = assertPackage(
        "ui..",
        allowed = setOf("ai..", "bubble..", "data..", "diagnostics.."),
        allowedTypes = setOf(
            "accessibility.ChatAccessibilityService",
            "accessibility.AccessibilityBridge",
            "capture.DebugCapture",
        ),
    )

    /**
     * The root package holds only process-level components: the Application and the
     * keep-alive service it starts. Process-wide hubs that every layer reaches for (the
     * old `AppBus`) belong to the package that owns the state.
     */
    @Test fun rootPackageHoldsOnlyProcessLevelComponents() {
        val rootFiles = setOf("ChatAssistantApp", "KeepLiveService")
        val stray = appFiles().filter { it.appPackage().isEmpty() && it.name !in rootFiles }
        assertTrue(
            "only $rootFiles live in the root package:\n" +
                stray.joinToString("\n") { "  ${it.name}.kt" },
            stray.isEmpty(),
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
            file.appReferences()
                .filter { violates(it, pkg, allowed, allowedTypes) }
                .map { "  ${file.appPackage()}/${file.name}.kt uses $it" }
        }
        val header = "$pkg may only use ${allowed + allowedTypes} from the app:"
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
        // Root types (R, BuildConfig, the Application) are fine to import from anywhere.
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

        /** A fully qualified app type, e.g. `com.octos.aichat.ai.local.LocalModel`. */
        val QUALIFIED_APP_TYPE =
            Regex("""\bcom\.octos\.aichat\.(?:[a-z][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_]*""")

        /**
         * Everything a file references from the app: its imports, plus types written fully
         * qualified in code — those need no import, so an import-only check misses them
         * (it once let `data` depend on `ai.local.LocalModel` unnoticed). Comments don't count.
         */
        fun KoFileDeclaration.appReferences(): List<String> {
            val code = text
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")
            val qualified = QUALIFIED_APP_TYPE.findAll(code).map { it.value }
            return (imports.map { it.name } + qualified).distinct()
        }

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
