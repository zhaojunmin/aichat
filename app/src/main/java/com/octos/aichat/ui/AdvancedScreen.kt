package com.octos.aichat.ui
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.octos.aichat.AppBus
import com.octos.aichat.R
import com.octos.aichat.ai.embedding.Encoder
import com.octos.aichat.bubble.FloatingBubbleService
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.Speaker
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.ui.theme.ThemeVariant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedScreen(onBack: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val vm: AdvancedViewModel = viewModel(factory = AdvancedViewModel.factory(context))
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val memStats by vm.stats.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()

    var preview by remember { mutableStateOf<Conversation?>(null) }
    var rawDump by remember { mutableStateOf<String?>(null) }
    var confirmClearDiagnostics by remember { mutableStateOf(false) }
    if (confirmClearDiagnostics) {
        AlertDialog(
            onDismissRequest = { confirmClearDiagnostics = false },
            title = { Text(stringResource(R.string.adv_diag_clear)) },
            text = { Text(stringResource(R.string.adv_diag_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    DiagLog.clear()
                    confirmClearDiagnostics = false
                }) { Text(stringResource(R.string.adv_diag_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearDiagnostics = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    // Mirrors the process-wide (non-persisted) debug flag in AppBus.
    var captureRawTree by remember { mutableStateOf(AppBus.captureRawTree) }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.adv_title)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.adv_desc),
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionCard(
                stringResource(R.string.adv_appearance_title),
                icon = Icons.Outlined.Palette,
            ) {
                Text(
                    stringResource(R.string.adv_appearance_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = settings.themeVariantId == "system",
                        onClick = { vm.update { it.copy(themeVariantId = "system") } },
                        label = { Text(stringResource(R.string.theme_system)) },
                    )
                    ThemeVariant.entries.forEach { v ->
                        FilterChip(
                            selected = settings.themeVariantId == v.id,
                            onClick = { vm.update { it.copy(themeVariantId = v.id) } },
                            label = { Text(stringResource(v.labelRes)) },
                        )
                    }
                }
            }

            SectionCard(
                stringResource(R.string.adv_bubble_title),
                icon = Icons.Outlined.BubbleChart,
            ) {
                val overlayOn = Settings.canDrawOverlays(context)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.adv_bubble_autostart))
                        Text(
                            if (overlayOn) {
                                stringResource(R.string.adv_bubble_on_desc)
                            } else {
                                stringResource(R.string.adv_bubble_off_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings.bubbleAutoStart,
                        enabled = overlayOn,
                        onCheckedChange = { c ->
                            vm.update { it.copy(bubbleAutoStart = c) }
                            if (c) {
                                FloatingBubbleService.start(context)
                            } else {
                                FloatingBubbleService.stop(context)
                            }
                        },
                    )
                }
            }

            SectionCard(stringResource(R.string.adv_reply_pref_title), icon = Icons.Outlined.Tune) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.adv_suggestion_count, settings.suggestionCount),
                        Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        vm.update {
                            it.copy(suggestionCount = (it.suggestionCount - 1).coerceAtLeast(1))
                        }
                    }) { Text("-") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        vm.update {
                            it.copy(suggestionCount = (it.suggestionCount + 1).coerceAtMost(5))
                        }
                    }) { Text("+") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.adv_autopaste_title))
                        Text(
                            stringResource(R.string.adv_autopaste_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings.autoPaste,
                        onCheckedChange = { c -> vm.update { it.copy(autoPaste = c) } },
                    )
                }
            }

            SectionCard(stringResource(R.string.adv_endpoint_title), icon = Icons.Outlined.Link) {
                Text(
                    stringResource(R.string.adv_endpoint_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    stringResource(R.string.adv_base_url, settings.backend.displayName),
                    settings.baseUrl,
                ) { v ->
                    vm.update { it.withBaseUrl(v) }
                }
            }

            SectionCard(
                stringResource(R.string.adv_recognition_title),
                icon = Icons.Outlined.Visibility,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.adv_ocr_title))
                        Text(
                            stringResource(R.string.adv_ocr_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings.useScreenshotOcr,
                        onCheckedChange = { c -> vm.update { it.copy(useScreenshotOcr = c) } },
                    )
                }
                if (settings.useScreenshotOcr) {
                    LabeledField(
                        stringResource(R.string.adv_ocr_packages_label),
                        settings.ocrPackages,
                    ) { v -> vm.update { it.copy(ocrPackages = v) } }
                }
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    stringResource(R.string.adv_target_packages_label),
                    settings.targetPackages,
                ) { v -> vm.update { it.copy(targetPackages = v) } }
            }

            SectionCard(
                stringResource(R.string.adv_memory_learn_title),
                icon = Icons.Outlined.Psychology,
            ) {
                Text(
                    stringResource(R.string.adv_memory_learn_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.adv_learn_threshold, settings.learnThreshold),
                        Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        vm.update {
                            it.copy(learnThreshold = (it.learnThreshold - 5).coerceAtLeast(5))
                        }
                    }) { Text("-5") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        vm.update {
                            it.copy(learnThreshold = (it.learnThreshold + 5).coerceAtMost(100))
                        }
                    }) { Text("+5") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.adv_inject_limit, settings.memoryInjectLimit))
                        Text(
                            stringResource(R.string.adv_inject_limit_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = {
                        vm.update {
                            it.copy(
                                memoryInjectLimit = (it.memoryInjectLimit - 5).coerceAtLeast(5),
                            )
                        }
                    }) { Text("-5") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        vm.update {
                            it.copy(
                                memoryInjectLimit = (it.memoryInjectLimit + 5).coerceAtMost(200),
                            )
                        }
                    }) { Text("+5") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.adv_semantic_title))
                        val embedded = memories.count { it.embedding != null }
                        // No vectors yet: say why (the failure code, or "not loaded").
                        val reason = Encoder.lastError?.label(context)
                            ?: stringResource(R.string.adv_semantic_not_loaded)
                                .takeUnless { Encoder.isReady }
                        val diag = if (embedded == 0 && reason != null) " · $reason" else ""
                        Text(
                            stringResource(
                                R.string.adv_semantic_desc,
                                embedded,
                                memories.size,
                                diag,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings.memoryEmbeddingEnabled,
                        onCheckedChange = { c ->
                            vm.update { it.copy(memoryEmbeddingEnabled = c) }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.adv_memory_stats,
                        memStats.totalReplies,
                        memStats.pending,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(
                stringResource(R.string.adv_diag_title),
                icon = Icons.Outlined.MonitorHeart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.adv_diag_switch))
                        Text(
                            stringResource(R.string.adv_diag_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings.diagnosticsEnabled,
                        onCheckedChange = { c ->
                            // Re-enabling starts a fresh record: only what happens from now on.
                            vm.update {
                                it.copy(
                                    diagnosticsEnabled = c,
                                    diagnosticsEnabledAt = if (c) {
                                        System.currentTimeMillis()
                                    } else {
                                        it.diagnosticsEnabledAt
                                    },
                                )
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenDiagnostics,
                        enabled = settings.diagnosticsEnabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.adv_diag_export)) }
                    OutlinedButton(
                        onClick = { confirmClearDiagnostics = true },
                        enabled = settings.diagnosticsEnabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.adv_diag_clear)) }
                }
            }

            SectionCard(stringResource(R.string.adv_debug_title), icon = Icons.Outlined.BugReport) {
                Text(
                    stringResource(R.string.adv_debug_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { preview = AppBus.conversation },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.adv_debug_view_chat)) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.adv_debug_capture_title))
                        Text(
                            stringResource(R.string.adv_debug_capture_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = captureRawTree,
                        onCheckedChange = { c ->
                            AppBus.captureRawTree = c
                            captureRawTree = c
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                val treeEmpty = stringResource(
                    if (captureRawTree) {
                        R.string.adv_debug_tree_empty
                    } else {
                        R.string.adv_debug_tree_off
                    },
                )
                OutlinedButton(
                    onClick = { rawDump = AppBus.lastRawDump ?: treeEmpty },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.adv_debug_view_tree)) }
            }
        }
    }

    rawDump?.let { dump ->
        AlertDialog(
            onDismissRequest = { rawDump = null },
            confirmButton = {
                TextButton(onClick = { rawDump = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            title = { Text(stringResource(R.string.adv_raw_tree_title)) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(dump, style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }

    preview?.let { conv ->
        AlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = {
                TextButton(onClick = { preview = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            title = { Text(stringResource(R.string.adv_read_result_title, conv.messages.size)) },
            text = {
                if (conv.isEmpty) {
                    Text(stringResource(R.string.adv_read_empty))
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(R.string.adv_read_source, conv.packageName),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        conv.messages.forEach { m ->
                            val who = if (m.speaker == Speaker.ME) {
                                stringResource(R.string.speaker_me)
                            } else {
                                stringResource(R.string.speaker_other)
                            }
                            Text("$who: ${m.text}")
                        }
                    }
                }
            },
        )
    }
}
