package com.octos.aichat.ui
import com.octos.aichat.R

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val inferenceConfigured = settings.isInferenceConfigured(localModelReady = localReady)

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.home_title)) }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Setup checklist ---
            SectionCard(stringResource(R.string.home_permissions_title), icon = Icons.Outlined.Lock) {
                Text(
                    stringResource(R.string.home_permissions_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                PermissionRow(
                    label = stringResource(R.string.home_perm_accessibility),
                    granted = accessibilityOn,
                    action = stringResource(R.string.action_enable)
                ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

                PermissionRow(
                    label = stringResource(R.string.home_perm_overlay),
                    granted = overlayOn,
                    action = stringResource(R.string.action_enable)
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
                    ) { Text(stringResource(R.string.home_perm_notification)) }
                }
            }

            // --- AI service (key + model + quick test) ---
            SectionCard(stringResource(R.string.home_ai_service_title), icon = Icons.Outlined.AutoAwesome) {
                Text(stringResource(R.string.home_inference_desc), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !settings.useLocal,
                        onClick = { vm.update { it.copy(inferenceMode = InferenceMode.CLOUD) } },
                        label = { Text(stringResource(R.string.inference_cloud)) }
                    )
                    FilterChip(
                        selected = settings.useLocal,
                        onClick = { vm.update { it.copy(inferenceMode = InferenceMode.LOCAL) } },
                        label = { Text(stringResource(R.string.inference_local)) }
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
                    LabeledField(stringResource(R.string.label_api_key), settings.apiKey, isSecret = true) { v ->
                        vm.update { it.withApiKey(v) }
                    }
                    LabeledField(stringResource(R.string.label_model), settings.model) { v -> vm.update { it.withModel(v) } }
                } else if (!LlmEngine.isSupported) {
                    Text(stringResource(R.string.home_local_unsupported), color = MaterialTheme.colorScheme.error)
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
                        stringResource(
                            R.string.home_model_size,
                            stringResource(settings.localModel.hintRes),
                            settings.localModel.approxMB
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (localReady) stringResource(R.string.status_ready)
                        else stringResource(R.string.home_model_not_downloaded),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (localDownloading) {
                        Spacer(Modifier.height(6.dp))
                        Text(localProgress, style = MaterialTheme.typography.bodySmall)
                    }
                    localError?.let { err ->
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.home_error_prefix, err), color = MaterialTheme.colorScheme.error,
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
                                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.home_downloading))
                                } else Text(stringResource(R.string.home_download_model, settings.localModel.displayName))
                            }
                        } else {
                            OutlinedButton(
                                enabled = !localDownloading,
                                onClick = { vm.deleteModel(settings.localModel) }
                            ) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !testing && inferenceConfigured,
                    onClick = { vm.runConnectionTest() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (settings.useLocal) stringResource(R.string.home_testing_local)
                             else stringResource(R.string.home_testing_cloud))
                    } else Text(stringResource(R.string.test_connection))
                }
            }

            // --- Reply style ---
            SectionCard(stringResource(R.string.home_reply_style_title), icon = Icons.Outlined.Chat) {
                Text(stringResource(R.string.home_reply_style_desc), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PersonaPresets.DEFAULTS.forEach { preset ->
                        FilterChip(
                            selected = settings.customPersona.isBlank() && settings.personaId == preset.id,
                            onClick = { vm.update { it.copy(personaId = preset.id, customPersona = "") } },
                            label = { Text(stringResource(preset.titleRes)) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    stringResource(R.string.home_custom_persona_label),
                    settings.customPersona,
                    singleLine = false
                ) { v -> vm.update { it.copy(customPersona = v) } }
            }

            // --- Personal memory ---
            SectionCard(stringResource(R.string.home_memory_title), icon = Icons.Outlined.Psychology) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_memory_switch_label))
                        Text(
                            stringResource(R.string.home_memory_desc),
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
                        stringResource(R.string.home_memory_stats, memStats.totalReplies, memories.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = !memStats.learning && memStats.pending > 0 && inferenceConfigured,
                            onClick = { vm.learnNow() }
                        ) {
                            if (memStats.learning) {
                                CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.home_learning))
                            } else Text(stringResource(R.string.home_learn_now))
                        }
                        OutlinedButton(onClick = onOpenMemory) { Text(stringResource(R.string.home_manage_memory)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.clearMemory() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.home_clear_memory)) }
                }
            }

            // --- Entry to advanced ---
            OutlinedButton(
                onClick = onOpenAdvanced,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.home_advanced_entry)) }
            Spacer(Modifier.height(8.dp))
        }
    }

    testOutput?.let { replies ->
        AlertDialog(
            onDismissRequest = { vm.dismissTest() },
            confirmButton = { TextButton(onClick = { vm.dismissTest() }) { Text(stringResource(R.string.action_close)) } },
            title = { Text(stringResource(R.string.home_test_result_title)) },
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
