package com.octos.aichat.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    private val withKey = AppSettings(
        backendConfigs = mapOf(AiBackend.DEEPSEEK to BackendConfig("https://x", "m", "sk-test")),
    )
    private val noKey = AppSettings()

    @Test fun cloud_requiresApiKey() {
        assertTrue(withKey.isInferenceConfigured(localModelReady = false))
        assertFalse(noKey.isInferenceConfigured(localModelReady = true))
    }

    /** Regression: local mode used to be gated on the cloud API key (memory never learned). */
    @Test fun local_dependsOnModelNotOnCloudKey() {
        val local = noKey.copy(inferenceMode = InferenceMode.LOCAL)
        assertTrue(local.isInferenceConfigured(localModelReady = true))
        assertFalse(local.isInferenceConfigured(localModelReady = false))
        assertFalse(
            withKey.copy(
                inferenceMode = InferenceMode.LOCAL,
            ).isInferenceConfigured(localModelReady = false),
        )
    }
}
