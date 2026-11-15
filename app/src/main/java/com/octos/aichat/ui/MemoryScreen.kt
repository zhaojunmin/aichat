package com.octos.aichat.ui
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.octos.aichat.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryScreen(onBack: () -> Unit) {
    val vm: MemoryViewModel = viewModel(factory = MemoryViewModel.factory(LocalContext.current))
    val memories by vm.memories.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.mem_title, memories.size)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (memories.isEmpty()) {
                Text(
                    stringResource(R.string.mem_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    stringResource(R.string.mem_intro),
                    style = MaterialTheme.typography.bodySmall,
                )
                memories.forEach { m ->
                    MemoryEditRow(
                        category = m.category,
                        content = m.content,
                        onSave = { c -> vm.edit(m.id, c) },
                        onDelete = { vm.delete(m.id) },
                    )
                }
            }

            if (deleted.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.mem_recycle_title, deleted.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.mem_recycle_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
                deleted.forEach { m ->
                    DeletedMemoryRow(
                        category = m.category,
                        content = m.content,
                        byLearner = m.deletedBy == "learner",
                        onRestore = { vm.restore(m.id) },
                        onPurge = { vm.purge(m.id) },
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
                "【$category】" + if (byLearner) {
                    stringResource(R.string.mem_removed_by_learner)
                } else {
                    stringResource(R.string.mem_removed_by_user)
                },
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
                    Text(
                        stringResource(R.string.mem_purge),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onRestore) { Text(stringResource(R.string.mem_restore)) }
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
            Text(
                "【$category】",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = false,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.width(4.dp))
                TextButton(enabled = changed, onClick = {
                    onSave(text)
                }) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
