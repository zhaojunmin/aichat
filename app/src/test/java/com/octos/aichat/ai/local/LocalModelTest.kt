package com.octos.aichat.ai.local

import com.octos.aichat.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelTest {

    /** The default id lives in `data`; if it named no model, LocalModel would fail to initialise. */
    @Test fun settingsDefaultNamesARealModel() {
        assertEquals(AppSettings.DEFAULT_LOCAL_MODEL_ID, LocalModel.DEFAULT.id)
        assertEquals(LocalModel.DEFAULT, AppSettings().localModel)
    }

    @Test fun unknownIdFallsBackToTheDefault() {
        assertEquals(LocalModel.DEFAULT, LocalModel.of("gone-model"))
        assertEquals(LocalModel.QWEN_3B, AppSettings(localModelId = "qwen3b").localModel)
    }
}
