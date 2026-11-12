package com.octos.aichat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import com.octos.aichat.accessibility.ChatAccessibilityService
import com.octos.aichat.ai.AiRepository
import com.octos.aichat.data.AiBackend
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.PersonaPresets
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.data.InferenceMode
import com.octos.aichat.data.Speaker
import com.octos.aichat.data.local.LocalLlmEngine
import com.octos.aichat.data.local.LocalModel
import com.octos.aichat.data.local.LocalModelManager
import com.octos.aichat.data.memory.MemoryRepository
import com.octos.aichat.overlay.FloatingBubbleService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

/** State-based nav (no nav library): home, advanced, and memory management. */
private enum class Screen { Home, Advanced, Memory }

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    var screen by remember { mutableStateOf(Screen.Home) }
    MaterialTheme {
        when (screen) {
            Screen.Home -> HomeScreen(
                repo,
                onOpenAdvanced = { screen = Screen.Advanced },
                onOpenMemory = { screen = Screen.Memory },
            )
            Screen.Advanced -> AdvancedScreen(repo, onBack = { screen = Screen.Home })
            Screen.Memory -> MemoryScreen(onBack = { screen = Screen.Home })
        }
    }
}

/** Bumps a counter on every ON_RESUME so permission state re-reads. */
@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

// ======================= Consumer home =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    repo: SettingsRepository,
    onOpenAdvanced: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    val context = LocalContext.current
    val settings by repo.state.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val scope = rememberCoroutineScope()

    val memoryRepo = remember { MemoryRepository.get(context) }
    val memories by memoryRepo.memories.collectAsStateWithLifecycle()
    val memStats by memoryRepo.stats.collectAsStateWithLifecycle()

    val accessibilityOn = remember(tick) { isAccessibilityEnabled(context) }
    val overlayOn = remember(tick) { Settings.canDrawOverlays(context) }
    val ready = accessibilityOn && overlayOn && settings.apiKey.isNotBlank()

    var testOutput by remember { mutableStateOf<List<String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var localDownloading by remember { mutableStateOf(false) }
    var localProgress by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var localReadyTick by remember { mutableIntStateOf(0) }
    val localReady = remember(localReadyTick, settings.localModelId) {
        LocalModelManager.isReady(context, settings.localModel)
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Scaffold(topBar = { TopAppBar(title = { Text("聊天搭子 · AI 回复助手") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Setup checklist ---
            SectionCard("开启权限") {
                Text(
                    "助手需要这两项权限才能读取聊天并弹出悬浮球。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                PermissionRow(
                    label = "读取聊天内容(无障碍)",
                    granted = accessibilityOn,
                    action = "去开启"
                ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

                PermissionRow(
                    label = "显示悬浮球(悬浮窗)",
                    granted = overlayOn,
                    action = "去开启"
                ) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("允许通知(让助手保持运行)") }
                }
            }

            // --- Bubble on/off ---
            SectionCard("悬浮助手") {
                Text(
                    when {
                        !overlayOn -> "请先在上面开启悬浮窗权限。"
                        ready -> "一切就绪!打开微信 / Soul 等聊天界面,点悬浮球即可生成回复。"
                        else -> "开启后,在聊天界面点悬浮球即可生成回复。"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { FloatingBubbleService.start(context) },
                        enabled = overlayOn
                    ) { Text("启动悬浮球") }
                    OutlinedButton(onClick = { FloatingBubbleService.stop(context) }) {
                        Text("关闭")
                    }
                }
            }

            // --- AI service (key + model + quick test) ---
            SectionCard("AI 服务") {
                Text("选择推理方式。本地模式:聊天内容不出设备。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !settings.useLocal,
                        onClick = { repo.update { it.copy(inferenceMode = InferenceMode.CLOUD) } },
                        label = { Text("云端") }
                    )
                    FilterChip(
                        selected = settings.useLocal,
                        onClick = { repo.update { it.copy(inferenceMode = InferenceMode.LOCAL) } },
                        label = { Text("本地(隐私)") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (!settings.useLocal) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiBackend.entries.forEach { b ->
                            FilterChip(
                                selected = settings.backend == b,
                                onClick = { repo.update { it.copy(backend = b) } },
                                label = { Text(b.displayName) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("API Key", settings.apiKey, isSecret = true) { v ->
                        repo.update { it.withApiKey(v) }
                    }
                    LabeledField("模型", settings.model) { v -> repo.update { it.withModel(v) } }
                } else if (!LocalLlmEngine.isSupported) {
                    Text("本机不支持本地模型(仅 arm64 设备)", color = MaterialTheme.colorScheme.error)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocalModel.entries.forEach { m ->
                            FilterChip(
                                selected = settings.localModelId == m.id,
                                enabled = !localDownloading,
                                onClick = { repo.update { it.copy(localModelId = m.id) }; localError = null },
                                label = { Text(m.displayName) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${settings.localModel.hint} · 约 ${settings.localModel.approxMB}MB",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (localReady) "✅ 已就绪" else "未下载(建议 Wi-Fi)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (localDownloading) {
                        Spacer(Modifier.height(6.dp))
                        Text(localProgress, style = MaterialTheme.typography.bodySmall)
                    }
                    localError?.let { err ->
                        Spacer(Modifier.height(6.dp))
                        Text("⚠ $err", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!localReady) {
                            Button(
                                enabled = !localDownloading,
                                onClick = {
                                    val model = settings.localModel
                                    localDownloading = true; localError = null; localProgress = "连接中…"
                                    scope.launch {
                                        val r = LocalModelManager.download(context, model) { p ->
                                            localProgress = "${p.percent}% · ${p.file} (${p.index}/${p.total}) " +
                                                "${p.doneBytes / 1_000_000}/${p.totalBytes / 1_000_000}MB"
                                        }
                                        localDownloading = false
                                        if (r.isSuccess) localProgress = "下载完成"
                                        else { localProgress = ""; localError = r.exceptionOrNull()?.message ?: "下载失败" }
                                        localReadyTick++
                                    }
                                }
                            ) {
                                if (localDownloading) {
                                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp)); Text("下载中…")
                                } else Text("下载 ${settings.localModel.displayName}")
                            }
                        } else {
                            OutlinedButton(
                                enabled = !localDownloading,
                                onClick = {
                                    val model = settings.localModel
                                    scope.launch {
                                        LocalLlmEngine.unload(); LocalModelManager.delete(context, model); localReadyTick++
                                    }
                                }
                            ) { Text("删除") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !testing && (if (settings.useLocal) localReady else settings.apiKey.isNotBlank()),
                    onClick = {
                        testing = true
                        scope.launch {
                            val sample = Conversation(
                                "test",
                                listOf(
                                    ChatMessage(Speaker.OTHER, "在吗?周末有空一起吃饭吗"),
                                    ChatMessage(Speaker.ME, "在的"),
                                    ChatMessage(Speaker.OTHER, "那你想吃什么呀"),
                                )
                            )
                            testOutput = runCatching {
                                if (settings.useLocal) {
                                    check(
                                        LocalLlmEngine.ensureLoaded(
                                            LocalModelManager.configPath(context, settings.localModel),
                                            LocalModelManager.tmpDir(context)
                                        )
                                    ) { "本地模型加载失败" }
                                }
                                AiRepository.generateReplies(settings, sample)
                            }.getOrElse { listOf("错误:${it.message}") }
                            testing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (settings.useLocal) "本地生成中…" else "测试中…")
                    } else Text("测试连接")
                }
            }

            // --- Reply style ---
            SectionCard("回复风格") {
                Text("选一个场景,或自定义你想要的语气。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PersonaPresets.DEFAULTS.forEach { preset ->
                        FilterChip(
                            selected = settings.customPersona.isBlank() && settings.personaId == preset.id,
                            onClick = { repo.update { it.copy(personaId = preset.id, customPersona = "") } },
                            label = { Text(preset.title) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    "自定义风格(填写后覆盖上面的场景)",
                    settings.customPersona,
                    singleLine = false
                ) { v -> repo.update { it.copy(customPersona = v) } }
            }

            // --- Personal memory ---
            SectionCard("专属记忆(越用越懂你)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("记住我的说话风格和习惯")
                        Text(
                            "助手会悄悄学习你的回复,让生成的内容越来越像你本人。所有记录只存在本机。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = settings.memoryEnabled,
                        onCheckedChange = { c -> repo.update { it.copy(memoryEnabled = c) } }
                    )
                }
                if (settings.memoryEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已记录 ${memStats.totalReplies} 条 · 记忆 ${memories.size} 条",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = !memStats.learning && memStats.pending > 0 && settings.apiKey.isNotBlank(),
                            onClick = { scope.launch { memoryRepo.learnNow() } }
                        ) {
                            if (memStats.learning) {
                                CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("学习中…")
                            } else Text("立即学习")
                        }
                        OutlinedButton(onClick = onOpenMemory) { Text("管理记忆") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { scope.launch { memoryRepo.clearAll() } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("清空记忆与回复记录") }
                }
            }

            // --- Entry to advanced ---
            OutlinedButton(
                onClick = onOpenAdvanced,
                modifier = Modifier.fillMaxWidth()
            ) { Text("⚙  高级设置 / 开发者选项") }
            Spacer(Modifier.height(8.dp))
        }
    }

    testOutput?.let { replies ->
        AlertDialog(
            onDismissRequest = { testOutput = null },
            confirmButton = { TextButton(onClick = { testOutput = null }) { Text("关闭") } },
            title = { Text("测试结果") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    replies.forEachIndexed { i, r -> Text("${i + 1}. $r") }
                }
            }
        )
    }

}

// ======================= Memory management =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val memoryRepo = remember { MemoryRepository.get(context) }
    val memories by memoryRepo.memories.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆管理 · ${memories.size} 条") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (memories.isEmpty()) {
                Text(
                    "还没有学到记忆。先在聊天里多用几次,攒够回复后点\"立即学习\"或等待自动学习。",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    "这些是助手学到的关于你的信息,会用于生成回复。可修改或删除不准确的条目。",
                    style = MaterialTheme.typography.bodySmall
                )
                memories.forEach { m ->
                    MemoryEditRow(
                        category = m.category,
                        content = m.content,
                        onSave = { c -> scope.launch { memoryRepo.editMemory(m.id, c) } },
                        onDelete = { scope.launch { memoryRepo.deleteMemory(m.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryEditRow(
    category: String,
    content: String,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember(content) { mutableStateOf(content) }
    val changed = text.trim().isNotEmpty() && text != content
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("【$category】", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = false,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(enabled = changed, onClick = { onSave(text) }) { Text("保存") }
            }
        }
    }
}

// ======================= Advanced / developer =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by repo.state.collectAsStateWithLifecycle()
    val memoryRepo = remember { MemoryRepository.get(context) }
    val memStats by memoryRepo.stats.collectAsStateWithLifecycle()

    var preview by remember { mutableStateOf<Conversation?>(null) }
    var rawDump by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("高级设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "这些选项面向进阶用户和适配调试,普通使用无需改动。",
                style = MaterialTheme.typography.bodyMedium
            )

            SectionCard("回复偏好") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每次候选条数:${settings.suggestionCount}", Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        repo.update { it.copy(suggestionCount = (it.suggestionCount - 1).coerceAtLeast(1)) }
                    }) { Text("-") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        repo.update { it.copy(suggestionCount = (it.suggestionCount + 1).coerceAtMost(5)) }
                    }) { Text("+") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动填入输入框")
                        Text("选好回复后直接填进聊天输入框", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = settings.autoPaste,
                        onCheckedChange = { c -> repo.update { it.copy(autoPaste = c) } }
                    )
                }
            }

            SectionCard("接口地址") {
                Text(
                    "仅在使用自建 / 代理服务时才需修改,默认即可。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                LabeledField("Base URL(当前:${settings.backend.displayName})", settings.baseUrl) { v ->
                    repo.update { it.withBaseUrl(v) }
                }
            }

            SectionCard("识别设置") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("截图 OCR 读取")
                        Text(
                            "仅对下面列出的 App 生效(默认微信);Soul 等仍走无障碍精准识别",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = settings.useScreenshotOcr,
                        onCheckedChange = { c -> repo.update { it.copy(useScreenshotOcr = c) } }
                    )
                }
                if (settings.useScreenshotOcr) {
                    LabeledField(
                        "OCR 适用 App 包名(逗号分隔)",
                        settings.ocrPackages
                    ) { v -> repo.update { it.copy(ocrPackages = v) } }
                }
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    "目标 App 包名(逗号分隔)",
                    settings.targetPackages
                ) { v -> repo.update { it.copy(targetPackages = v) } }
            }

            SectionCard("记忆学习") {
                Text(
                    "记忆全部永久保存在本机,只增不删;学习时合并新信息、冲突以最新为准。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("学习阈值:${settings.learnThreshold} 条", Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        repo.update { it.copy(learnThreshold = (it.learnThreshold - 5).coerceAtLeast(5)) }
                    }) { Text("-5") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        repo.update { it.copy(learnThreshold = (it.learnThreshold + 5).coerceAtMost(100)) }
                    }) { Text("+5") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("注入上限:最近 ${settings.memoryInjectLimit} 条")
                        Text(
                            "仅把最近更新的若干条带进回复生成,避免 prompt 过长",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(onClick = {
                        repo.update { it.copy(memoryInjectLimit = (it.memoryInjectLimit - 5).coerceAtLeast(5)) }
                    }) { Text("-5") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        repo.update { it.copy(memoryInjectLimit = (it.memoryInjectLimit + 5).coerceAtMost(200)) }
                    }) { Text("+5") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "已记录 ${memStats.totalReplies} 条 · 待学习 ${memStats.pending} 条",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SectionCard("读取调试") {
                Text(
                    "先在微信 / Soul 的聊天界面停留一下,再回到这里查看无障碍服务读到的内容," +
                        "可据此判断\"我 / 对方\"是否区分正确。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { preview = AppBus.conversation },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("查看当前读取到的聊天记录") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { rawDump = AppBus.lastRawDump },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("查看原始节点树(适配新 App 用)") }
            }
        }
    }

    rawDump?.let { dump ->
        AlertDialog(
            onDismissRequest = { rawDump = null },
            confirmButton = { TextButton(onClick = { rawDump = null }) { Text("关闭") } },
            title = { Text("原始节点树") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(dump, style = MaterialTheme.typography.bodySmall)
                }
            }
        )
    }

    preview?.let { conv ->
        AlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } },
            title = { Text("读取结果 · ${conv.messages.size} 条") },
            text = {
                if (conv.isEmpty) {
                    Text("未读取到聊天内容。请确认已开启无障碍服务,并在微信 / Soul 聊天界面停留片刻后再试。")
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("来源: ${conv.packageName}", style = MaterialTheme.typography.bodySmall)
                        conv.messages.forEach { m ->
                            val who = if (m.speaker == Speaker.ME) "我" else "对方"
                            Text("$who: ${m.text}")
                        }
                    }
                }
            }
        )
    }
}

// ======================= shared =======================

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (granted) "✅ " else "⬜ ", )
        Text(label, Modifier.weight(1f))
        if (!granted) TextButton(onClick = onClick) { Text(action) }
        else Text("已开启", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    isSecret: Boolean = false,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        visualTransformation = if (isSecret)
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${ChatAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}
