package com.octos.aichat

import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.data.Speaker
import com.octos.aichat.data.memory.EmbeddingEngine
import com.octos.aichat.data.memory.MemoryRepository
import com.octos.aichat.bubble.FloatingBubbleService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedScreen(repo: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by repo.state.collectAsStateWithLifecycle()
    val memoryRepo = remember { MemoryRepository.get(context) }
    val memStats by memoryRepo.stats.collectAsStateWithLifecycle()
    val memories by memoryRepo.memories.collectAsStateWithLifecycle()

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

            SectionCard("悬浮球") {
                val overlayOn = Settings.canDrawOverlays(context)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("随应用启动自动开启")
                        Text(
                            if (overlayOn) "打开微信 / QQ / Soul 等聊天界面即可用;关闭则不自动启动"
                            else "需先在首页授予悬浮窗权限",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = settings.bubbleAutoStart,
                        enabled = overlayOn,
                        onCheckedChange = { c ->
                            repo.update { it.copy(bubbleAutoStart = c) }
                            if (c) FloatingBubbleService.start(context) else FloatingBubbleService.stop(context)
                        }
                    )
                }
            }

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
                    "记忆全部保存在本机;学习时合并新信息、冲突以最新为准,明显失效的会移入回收站(可在管理记忆里恢复)。",
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("语义召回(实验)")
                        val embedded = memories.count { it.embedding != null }
                        val diag = if (embedded == 0) " · ${EmbeddingEngine.status}" else ""
                        Text(
                            "端上向量按语义挑选相关记忆 · 已向量化 $embedded/${memories.size} 条$diag",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = settings.memoryEmbeddingEnabled,
                        onCheckedChange = { c -> repo.update { it.copy(memoryEmbeddingEnabled = c) } }
                    )
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
