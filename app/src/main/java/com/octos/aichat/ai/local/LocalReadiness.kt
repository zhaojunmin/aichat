package com.octos.aichat.ai.local

/** Whether the selected on-device model can run right now — and if not, why. */
internal sealed interface LocalReadiness {
    data object Ready : LocalReadiness

    /** This device can't run the native engine (e.g. not arm64). */
    data object Unsupported : LocalReadiness

    /** The selected model's files aren't on the device yet. */
    data class NotDownloaded(val modelName: String) : LocalReadiness

    /** The files are there, but the engine failed to load them. */
    data object LoadFailed : LocalReadiness
}
