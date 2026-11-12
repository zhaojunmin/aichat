package com.octos.aichat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import com.octos.aichat.data.Speaker
import com.octos.aichat.data.memory.MemoryRepository
import com.octos.aichat.overlay.FloatingBubbleService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    MaterialTheme { SettingsScreen(repo) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(repo: SettingsRepository) {
    val context = LocalContext.current
    val settings by repo.state.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val scope = rememberCoroutineScope()

    val memoryRepo = remember { MemoryRepository.get(context) }
    val memories by memoryRepo.memories.collectAsStateWithLifecycle()
    val memStats by memoryRepo.stats.collectAsStateWithLifecycle()

    val accessibilityOn = remember(tick) { isAccessibilityEnabled(context) }
    val overlayOn = remember(tick) { Settings.canDrawOverlays(context) }

    var testOutput by remember { mutableStateOf<List<String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Conversation?>(null) }
    var rawDump by remember { mutableStateOf<String?>(null) }
    var showMemories by remember { mutableStateOf(false) }

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
            SectionCard("① 权限") {
                PermissionRow(
                    label = "无障碍服务(读取聊天内容)",
                    granted = accessibilityOn,
                    action = "去开启"
                ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

                PermissionRow(
                    label = "悬浮窗(显示悬浮球)",
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
                    OutlinedButton(
                        onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("授予通知权限(悬浮服务需要)") }
                }
            }

            SectionCard("② 悬浮球") {
                Text(
                    if (overlayOn) "开启后,在微信/Soul 聊天界面点击悬浮球即可生成回复"
                    else "请先授予悬浮窗权限",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { FloatingBubbleService.start(context) },
                        enabled = overlayOn
                    ) { Text("启动悬浮球") }
                    OutlinedButton(onClick = { FloatingBubbleService.stop(context) }) {
                        Text("关闭悬浮球")
                    }
                }
            }

            SectionCard("③ AI 后端") {
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
                LabeledField("Base URL", settings.baseUrl) { v -> repo.update { it.withBaseUrl(v) } }
                LabeledField("模型", settings.model) { v -> repo.update { it.withModel(v) } }
                LabeledField("API Key", settings.apiKey, isSecret = true) { v ->
                    repo.update { it.withApiKey(v) }
                }
            }

            SectionCard("④ 聊天人设 / 场景") {
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
                    "自定义人设(填写后覆盖上面的预设)",
                    settings.customPersona,
                    singleLine = false
                ) { v -> repo.update { it.copy(customPersona = v) } }
            }

            SectionCard("⑤ 其他") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("候选条数:${settings.suggestionCount}", Modifier.weight(1f))
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
                    Text("自动填入输入框(需无障碍)", Modifier.weight(1f))
                    Switch(
                        checked = settings.autoPaste,
                        onCheckedChange = { c -> repo.update { it.copy(autoPaste = c) } }
                    )
                }
                Spacer(Modifier.height(8.dp))
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

            SectionCard("⑥ 全局记忆") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("记录我的回复并学习画像")
                        Text(
                            "我说过的话会追加到本地文本文件;攒够一定条数后由大模型提炼成\"我的画像/事实/偏好/风格\",并自动用于后续回复。",
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
                                "记忆全部永久保存,仅把最近更新的若干条带进回复生成,避免 prompt 过长",
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
                        "已记录 ${memStats.totalReplies} 条 · 待学习 ${memStats.pending} 条 · 记忆 ${memories.size} 条",
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
                        OutlinedButton(onClick = { showMemories = true }) { Text("查看记忆") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { scope.launch { memoryRepo.clearAll() } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("清空记忆与回复记录") }
                }
            }

            SectionCard("⑦ 读取调试") {
                Text(
                    "先在微信/Soul 的聊天界面停留一下,再回到这里查看无障碍服务读到的内容," +
                        "可据此判断\"我/对方\"是否区分正确。",
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
                ) { Text("查看原始节点树(开发者 / 适配新 App 用)") }
            }

            SectionCard("⑧ 测试") {
                Text("用一段示例对话测试当前 AI 配置是否可用。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !testing && settings.apiKey.isNotBlank(),
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
                            testOutput = runCatching { AiRepository.generateReplies(settings, sample) }
                                .getOrElse { listOf("错误:${it.message}") }
                            testing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("生成中…")
                    } else Text("测试生成回复")
                }
            }
        }
    }

    testOutput?.let { replies ->
        AlertDialog(
            onDismissRequest = { testOutput = null },
            confirmButton = { TextButton(onClick = { testOutput = null }) { Text("关闭") } },
            title = { Text("回复候选") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    replies.forEachIndexed { i, r -> Text("${i + 1}. $r") }
                }
            }
        )
    }

    if (showMemories) {
        AlertDialog(
            onDismissRequest = { showMemories = false },
            confirmButton = { TextButton(onClick = { showMemories = false }) { Text("关闭") } },
            title = { Text("我的记忆 · ${memories.size} 条") },
            text = {
                if (memories.isEmpty()) {
                    Text("还没有学到记忆。先在聊天里多用几次,攒够回复后点\"立即学习\"或等待自动学习。")
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        memories.forEach { m -> Text("【${m.category}】${m.content}") }
                    }
                }
            }
        )
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
                    Text("未读取到聊天内容。请确认已开启无障碍服务,并在微信/Soul 聊天界面停留片刻后再试。")
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
