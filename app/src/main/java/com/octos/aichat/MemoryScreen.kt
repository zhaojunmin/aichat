package com.octos.aichat

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octos.aichat.data.memory.MemoryRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val memoryRepo = remember { MemoryRepository.get(context) }
    val memories by memoryRepo.memories.collectAsStateWithLifecycle()
    val deleted by memoryRepo.deleted.collectAsStateWithLifecycle()
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

            if (deleted.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "回收站 · ${deleted.size} 条",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "已移除的记忆(含学习时自动判定失效的),不再用于回复。可恢复或彻底删除。",
                    style = MaterialTheme.typography.bodySmall,
                )
                deleted.forEach { m ->
                    DeletedMemoryRow(
                        category = m.category,
                        content = m.content,
                        byLearner = m.deletedBy == "learner",
                        onRestore = { scope.launch { memoryRepo.restoreMemory(m.id) } },
                        onPurge = { scope.launch { memoryRepo.purgeMemory(m.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeletedMemoryRow(
    category: String,
    content: String,
    byLearner: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "【$category】" + if (byLearner) " · 学习时移除" else " · 手动移除",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onPurge) {
                    Text("彻底删除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onRestore) { Text("恢复") }
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
