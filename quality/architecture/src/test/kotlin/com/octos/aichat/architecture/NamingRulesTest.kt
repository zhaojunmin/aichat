package com.octos.aichat.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming conventions the codebase has settled on, so a name keeps meaning one thing.
 * Each rule says what the name promises; renaming something to dodge a rule means
 * the rule (or the design) needs a conversation instead.
 */
class NamingRulesTest {

    /** "Bubble" means the floating ball only (#40); chat messages are "Message". */
    @Test fun bubbleNamesLiveInTheBubblePackage() {
        typeDeclarations()
            .filter { it.name.contains("Bubble") }
            .assertTrue(additionalMessage = BUBBLE_MEANING) { it.resideInPackage("$ROOT.bubble..") }
    }

    /** A ViewModel is a real androidx ViewModel and belongs to the UI layer. */
    @Test fun viewModelsAreAndroidxViewModelsInUi() {
        scope.classes()
            .filter { it.name.endsWith("ViewModel") }
            .assertTrue {
                it.resideInPackage("$ROOT.ui..") &&
                    it.hasParentWithName("ViewModel", "AndroidViewModel")
            }
    }

    /** One ViewModel per screen: side effects stay out of composables (MVVM). */
    @Test fun everyScreenHasItsViewModel() {
        val viewModels = scope.classes().map { it.name }.toSet()
        val screens = scope.files
            .filter { it.name.endsWith("Screen") }
            .filter { it.packagee?.name?.startsWith("$ROOT.ui") == true }
            .map { it.name.removeSuffix("Screen") }
        assertTrue("no *Screen files found — rule is stale", screens.isNotEmpty())
        val missing = screens.filter { "${it}ViewModel" !in viewModels }
        assertTrue("screens without a ViewModel: $missing", missing.isEmpty())
    }

    /**
     * "Repository" is reserved for the data layer, and each one sits behind its
     * `*Store` interface so callers depend on the abstraction (DIP).
     */
    @Test fun repositoriesAreDataLayerImplementationsOfTheirStore() {
        scope.classes()
            .filter { it.name.endsWith("Repository") }
            .assertTrue(additionalMessage = "XRepository lives in data and implements XStore") {
                it.resideInPackage("$ROOT.data..") &&
                    it.hasParentWithName(it.name.removeSuffix("Repository") + "Store")
            }
    }

    /** Backends share one naming axis: `CloudClient`, `LocalClient`, … */
    @Test fun aiClientImplementationsAreNamedClient() {
        scope.classes()
            .filter { it.hasParentWithName("AiClient") }
            .assertTrue { it.name.endsWith("Client") && it.resideInPackage("$ROOT.ai..") }
    }

    /** Vague suffixes hide what a type does; name it after its job instead. */
    @Test fun noVagueTypeSuffixes() {
        val vague = listOf("Impl", "Manager", "Helper", "Util", "Utils")
        typeDeclarations().assertFalse(additionalMessage = "avoid the suffixes $vague") { decl ->
            vague.any { decl.name.endsWith(it) }
        }
    }

    // ---- coding conventions ---------------------------------------------------

    /**
     * Errors go through `DiagLog` (error codes, exportable). The app class is the one
     * place allowed to touch logcat: it wires DiagLog's debug-build echo to it.
     */
    @Test fun onlyTheAppClassUsesLogcat() {
        scope.files
            .filter { it.name != "ChatAssistantApp" }
            .assertFalse(additionalMessage = "use DiagLog instead of android.util.Log") { file ->
                file.imports.any { it.name == "android.util.Log" }
            }
    }

    /** Compose is the settings UI only; the overlay uses classic Views. */
    @Test fun composablesLiveInUi() {
        scope.functions()
            .filter { it.hasAnnotationWithName("Composable") }
            .assertTrue { it.resideInPackage("$ROOT.ui..") }
    }

    private companion object {
        const val ROOT = "com.octos.aichat"
        const val BUBBLE_MEANING = "\"Bubble\" is the floating ball; a chat message is \"Message\""

        val scope by lazy { Konsist.scopeFromProduction(moduleName = "app") }

        fun typeDeclarations() = scope.classes() + scope.interfaces() + scope.objects()
    }
}
