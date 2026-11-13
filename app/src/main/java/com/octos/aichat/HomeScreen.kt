package com.octos.aichat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import com.octos.aichat.accessibility.ChatAccessibilityService
import com.octos.aichat.data.AiBackend
import com.octos.aichat.data.PersonaPresets
import com.octos.aichat.data.InferenceMode
import com.octos.aichat.ai.local.LlmEngine
import com.octos.aichat.ai.local.LocalModel
import kotlinx.coroutines.launch

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
internal fun HomeScreen(
    onOpenAdvanced: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(context))
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()

    val memories by vm.memories.collectAsStateWithLifecycle()
    val memStats by vm.stats.collectAsStateWithLifecycle()

    val accessibilityOn = remember(tick) { isAccessibilityEnabled(context) }
    val overlayOn = remember(tick) { Settings.canDrawOverlays(context) }

    val testOutput by vm.testOutput.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val localDownloading by vm.downloading.collectAsStateWithLifecycle()
    val localProgress by vm.progress.collectAsStateWithLifecycle()
    val localError by vm.modelError.collectAsStateWithLifecycle()
    val localReadyTick by vm.readyTick.collectAsStateWithLifecycle()
    val localReady = remember(localReadyTick, settings.localModelId) {
        vm.isModelReady(settings.localModel)
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Scaffold(topBar = { TopAppBar(title = { Text("聊天助手 · AI 回复助手") }) }) { pad ->
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

            // --- AI service (key + model + quick test) ---
            SectionCard("AI 服务") {
                Text("选择推理方式。本地模式:聊天内容不出设备。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !settings.useLocal,
                        onClick = { vm.update { it.copy(inferenceMode = InferenceMode.CLOUD) } },
                        label = { Text("云端") }
                    )
                    FilterChip(
                        selected = settings.useLocal,
                        onClick = { vm.update { it.copy(inferenceMode = InferenceMode.LOCAL) } },
                        label = { Text("本地(隐私)") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (!settings.useLocal) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiBackend.entries.forEach { b ->
                            FilterChip(
                                selected = settings.backend == b,
                                onClick = { vm.update { it.copy(backend = b) } },
                                label = { Text(b.displayName) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LabeledField("API Key", settings.apiKey, isSecret = true) { v ->
                        vm.update { it.withApiKey(v) }
                    }
                    LabeledField("模型", settings.model) { v -> vm.update { it.withModel(v) } }
                } else if (!LlmEngine.isSupported) {
                    Text("本机不支持本地模型(仅 arm64 设备)", color = MaterialTheme.colorScheme.error)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocalModel.entries.forEach { m ->
                            FilterChip(
                                selected = settings.localModelId == m.id,
                                enabled = !localDownloading,
                                onClick = { vm.update { it.copy(localModelId = m.id) }; vm.clearModelError() },
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
                                onClick = { vm.downloadModel(settings.localModel) }
                            ) {
                                if (localDownloading) {
                                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp)); Text("下载中…")
                                } else Text("下载 ${settings.localModel.displayName}")
                            }
                        } else {
                            OutlinedButton(
                                enabled = !localDownloading,
                                onClick = { vm.deleteModel(settings.localModel) }
                            ) { Text("删除") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !testing && (if (settings.useLocal) localReady else settings.apiKey.isNotBlank()),
                    onClick = { vm.runConnectionTest() },
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
                            onClick = { vm.update { it.copy(personaId = preset.id, customPersona = "") } },
                            label = { Text(preset.title) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    "自定义风格(填写后覆盖上面的场景)",
                    settings.customPersona,
                    singleLine = false
                ) { v -> vm.update { it.copy(customPersona = v) } }
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
                        onCheckedChange = { c -> vm.update { it.copy(memoryEnabled = c) } }
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
                            onClick = { vm.learnNow() }
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
                        onClick = { vm.clearMemory() },
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
            onDismissRequest = { vm.dismissTest() },
            confirmButton = { TextButton(onClick = { vm.dismissTest() }) { Text("关闭") } },
            title = { Text("测试结果") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    replies.forEachIndexed { i, r -> Text("${i + 1}. $r") }
                }
            }
        )
    }

}
private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${ChatAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}
