package com.octos.aichat.ui

import android.content.Context
import com.octos.aichat.diagnostics.ErrorCode

/** "EMB-703 向量模型加载失败" — the code plus its fixed description in the UI language. */
fun ErrorCode.label(context: Context): String {
    val chinese = context.resources.configuration.locales[0].language == "zh"
    return "$id ${if (chinese) zh else en}"
}
