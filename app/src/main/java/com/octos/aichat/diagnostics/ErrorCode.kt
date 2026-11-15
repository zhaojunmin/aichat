package com.octos.aichat.diagnostics

/**
 * Every failure point the app records, with a stable id like `AI-108`.
 * See docs/observability-design.md §4.
 *
 * Rules: an id, once released, is never reused and never changes meaning — new
 * failures are appended; retired ones stay (marked deprecated). ErrorCodeTest checks
 * this against a committed snapshot. The hundreds digit encodes the [Domain].
 * The descriptions are fixed text shown in the export; they never carry runtime data.
 */
enum class ErrorCode(val id: String, val level: Level, val zh: String, val en: String) {
    // AI — suggestion generation (cloud request, result parsing). Mirrors AiException.Kind.
    AI_101("AI-101", Level.ERROR, "Key 无效或无权限", "Key invalid or not permitted"),
    AI_102("AI-102", Level.ERROR, "余额不足或欠费", "Out of credit / account in arrears"),
    AI_103("AI-103", Level.ERROR, "被限流", "Rate limited"),
    AI_104("AI-104", Level.ERROR, "地址或模型不存在", "Wrong base URL or model"),
    AI_105("AI-105", Level.ERROR, "请求被拒", "Request rejected"),
    AI_106("AI-106", Level.ERROR, "服务端错误", "Server error"),
    AI_107("AI-107", Level.ERROR, "云端超时", "Cloud timeout"),
    AI_108("AI-108", Level.ERROR, "网络不可用", "Network unavailable"),
    AI_109("AI-109", Level.ERROR, "返回内容不可用", "Unusable response"),
    AI_110("AI-110", Level.ERROR, "其他失败", "Other failure"),

    // LLM — on-device inference engine, model download.
    LLM_201("LLM-201", Level.ERROR, "本地推理超时", "On-device inference timed out"),
    LLM_202("LLM-202", Level.ERROR, "本地模型加载失败", "On-device model failed to load"),
    LLM_203("LLM-203", Level.WARN, "原生推理库不可用", "Native inference libraries unavailable"),
    LLM_204("LLM-204", Level.ERROR, "模型下载失败", "Model download failed"),
    LLM_205("LLM-205", Level.ERROR, "本地模型未就绪", "On-device model not ready"),

    // A11Y — accessibility reading, screenshot, auto-fill.
    A11Y_301(
        "A11Y-301",
        Level.WARN,
        "处理无障碍事件时出异常(已兜底)",
        "Accessibility event handling failed (contained)",
    ),
    A11Y_302("A11Y-302", Level.ERROR, "点击时解析聊天失败", "Parsing the chat on tap failed"),
    A11Y_303("A11Y-303", Level.WARN, "点击时找不到聊天窗口", "Chat window not found on tap"),
    A11Y_304("A11Y-304", Level.ERROR, "截图失败", "Screenshot failed"),
    A11Y_305("A11Y-305", Level.WARN, "自动填入失败,已改为复制", "Auto-fill failed; copied instead"),
    A11Y_306("A11Y-306", Level.WARN, "读取键盘区域失败", "Reading the keyboard bounds failed"),

    // OCR — screenshot text recognition.
    OCR_401("OCR-401", Level.ERROR, "文字识别失败", "Text recognition failed"),
    OCR_402("OCR-402", Level.WARN, "识别成功但没读出消息", "Recognised, but no messages found"),

    // BUB — floating bubble, foreground service.
    BUB_501("BUB-501", Level.ERROR, "启动前台服务失败", "Starting the foreground service failed"),
    BUB_502("BUB-502", Level.WARN, "移除悬浮窗失败", "Removing an overlay window failed"),

    // MEM — memory recording and learning.
    MEM_601("MEM-601", Level.ERROR, "学习失败(已回滚,下次重试)", "Learning failed (rolled back, will retry)"),
    MEM_602("MEM-602", Level.WARN, "学习器返回不可用结果", "Learner returned an unusable result"),
    MEM_603("MEM-603", Level.ERROR, "回复日志写入失败", "Writing the reply log failed"),

    // EMB — on-device embedding model.
    EMB_701("EMB-701", Level.WARN, "向量原生库载入失败", "Embedding native library failed to load"),
    EMB_702("EMB-702", Level.WARN, "向量模型或词表资源拷贝失败", "Copying embedding model / vocabulary failed"),
    EMB_703("EMB-703", Level.WARN, "向量模型加载失败", "Embedding model failed to load"),
    EMB_704("EMB-704", Level.WARN, "单次向量计算失败", "Computing an embedding failed"),

    // SET — settings, key storage.
    SET_801("SET-801", Level.ERROR, "API Key 加密失败,未保存", "API key encryption failed; not saved"),
    SET_802("SET-802", Level.WARN, "API Key 无法解密,已清空", "API key could not be decrypted; cleared"),

    // APP — process-level events.
    APP_901("APP-901", Level.ERROR, "未捕获异常(进程崩溃)", "Uncaught exception (process crash)"),
    APP_902("APP-902", Level.INFO, "上次进程的退出原因", "How the previous process exited"),
    ;

    val domain: Domain get() = Domain.entries.first { id.startsWith(it.prefix + "-") }

    enum class Level { ERROR, WARN, INFO }

    /** Error-code domains; the id's hundreds digit is [hundreds]. */
    enum class Domain(val prefix: String, val hundreds: Int) {
        AI("AI", 1),
        LLM("LLM", 2),
        A11Y("A11Y", 3),
        OCR("OCR", 4),
        BUB("BUB", 5),
        MEM("MEM", 6),
        EMB("EMB", 7),
        SET("SET", 8),
        APP("APP", 9),
    }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun of(id: String): ErrorCode? = byId[id]
    }
}
