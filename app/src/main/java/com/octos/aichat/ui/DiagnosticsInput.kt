package com.octos.aichat.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.octos.aichat.accessibility.AccessibilityBridge
import com.octos.aichat.accessibility.ChatAccessibilityService
import com.octos.aichat.data.AppSettings
import com.octos.aichat.diagnostics.DeviceInfo

/**
 * The settings that go into the diagnostics export: enum names, switches and ids
 * only. Never the API key, base URL, model name, persona text or app lists —
 * PrivacyTest checks this with sentinel values.
 */
fun diagnosticsSettings(s: AppSettings): List<Pair<String, String>> = listOf(
    "inference" to s.inferenceMode.name,
    "backend" to s.backend.name,
    "apiKeySet" to s.apiKey.isNotBlank().toString(),
    "localModel" to s.localModelId,
    "persona" to s.personaId,
    "suggestions" to s.suggestionCount.toString(),
    "ocrApps" to s.ocrPackageSet.size.toString(), // a count: package names stay private
    "autoPaste" to s.autoPaste.toString(),
    "memory" to s.memoryEnabled.toString(),
    "semanticRecall" to s.memoryEmbeddingEnabled.toString(),
)

/** Permission state as the user sees it, plus whether the system has actually bound the reader. */
fun diagnosticsPermissions(context: Context): List<Pair<String, Boolean>> = listOf(
    "accessibilityEnabled" to isAccessibilityEnabled(context),
    "accessibilityConnected" to AccessibilityBridge.isConnected,
    "overlay" to Settings.canDrawOverlays(context),
    "notifications" to NotificationManagerCompat.from(context).areNotificationsEnabled(),
)

fun deviceInfo(context: Context): DeviceInfo {
    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
    val debuggable =
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    return DeviceInfo(
        appVersion = pkg.versionName ?: "?",
        versionCode = if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.P
        ) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        },
        buildType = if (debuggable) "debug" else "release",
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidRelease = Build.VERSION.RELEASE,
        sdk = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
        locale = context.resources.configuration.locales[0].toLanguageTag(),
    )
}

/** Whether the user has switched our accessibility service on in system settings. */
internal fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${ChatAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}
