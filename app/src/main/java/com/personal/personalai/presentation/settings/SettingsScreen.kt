package com.personal.personalai.presentation.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.personalai.R
import com.personal.personalai.localllm.api.LocalModel
import com.personal.personalai.localllm.engine.LiteRtLlmEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToApiUsage: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_description)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── AI Backend card ───────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.ai_backend_title),
                        style = MaterialTheme.typography.titleMedium
                    )

                    // Provider toggle
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = uiState.aiProvider == AiProvider.OPENAI,
                            onClick  = { viewModel.setAiProvider(AiProvider.OPENAI) },
                            shape    = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) { Text("OpenAI\n(Cloud)", textAlign = TextAlign.Center) }

                        SegmentedButton(
                            selected = uiState.aiProvider == AiProvider.OLLAMA,
                            onClick  = { viewModel.setAiProvider(AiProvider.OLLAMA) },
                            shape    = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) { Text("Ollama\n(Dev)", textAlign = TextAlign.Center) }

                        SegmentedButton(
                            selected = uiState.aiProvider == AiProvider.LOCAL,
                            onClick  = { viewModel.setAiProvider(AiProvider.LOCAL) },
                            shape    = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) { Text("Local\n(Device)", textAlign = TextAlign.Center) }
                    }

                    // ── OpenAI tab content ────────────────────────────────────
                    if (uiState.aiProvider == AiProvider.OPENAI) {
                        Text(
                            stringResource(R.string.ai_backend_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value             = uiState.apiKey,
                            onValueChange     = viewModel::onApiKeyChanged,
                            label             = { Text(stringResource(R.string.api_key_label)) },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier          = Modifier.fillMaxWidth(),
                            singleLine        = true
                        )
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            if (uiState.savedSuccessfully) {
                                Text(
                                    stringResource(R.string.api_key_saved),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Button(
                                onClick = viewModel::saveApiKey,
                                enabled = !uiState.isSaving
                            ) {
                                if (uiState.isSaving) {
                                    CircularProgressIndicator(
                                        modifier    = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(stringResource(R.string.save))
                                }
                            }
                        }
                    }

                    // ── Local on-device tab content ───────────────────────────
                    if (uiState.aiProvider == AiProvider.LOCAL) {
                        LocalAiSection(uiState = uiState, viewModel = viewModel)
                    }

                    // ── Ollama Dev Mode tab content ───────────────────────────
                    if (uiState.aiProvider == AiProvider.OLLAMA) {
                        Text(
                            stringResource(R.string.ollama_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value             = uiState.ollamaUrl,
                            onValueChange     = viewModel::onOllamaUrlChanged,
                            label             = { Text(stringResource(R.string.ollama_url_label)) },
                            placeholder       = { Text("http://10.100.102.75:11434") },
                            modifier          = Modifier.fillMaxWidth(),
                            singleLine        = true
                        )
                        OutlinedTextField(
                            value             = uiState.ollamaModel,
                            onValueChange     = viewModel::onOllamaModelChanged,
                            label             = { Text(stringResource(R.string.ollama_model_label)) },
                            placeholder       = { Text("qwen3.5:4b") },
                            modifier          = Modifier.fillMaxWidth(),
                            singleLine        = true
                        )
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            if (uiState.ollamaSavedSuccessfully) {
                                Text(
                                    stringResource(R.string.ollama_saved),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Button(
                                onClick = viewModel::saveOllamaSettings,
                                enabled = !uiState.isOllamaSaving
                            ) {
                                if (uiState.isOllamaSaving) {
                                    CircularProgressIndicator(
                                        modifier    = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(stringResource(R.string.save))
                                }
                            }
                        }
                    }
                }
            }

            // ── Web Search card ───────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier              = Modifier.padding(16.dp),
                    verticalArrangement   = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.web_search_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.web_search_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value             = uiState.serperApiKey,
                        onValueChange     = viewModel::onSerperApiKeyChanged,
                        label             = { Text(stringResource(R.string.serper_api_key_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier          = Modifier.fillMaxWidth(),
                        singleLine        = true
                    )
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        if (uiState.serperSavedSuccessfully) {
                            Text(
                                stringResource(R.string.serper_saved),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(
                            onClick = viewModel::saveSerperApiKey,
                            enabled = !uiState.isSerperSaving
                        ) {
                            if (uiState.isSerperSaving) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.save))
                            }
                        }
                    }
                }
            }

            // ── Proactive Check-in card ───────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Proactive Conversation", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Allow Lumio to initiate check-ins and memory follow-ups.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.isProactiveEnabled,
                            onCheckedChange = { viewModel.toggleProactiveEnabled(it) }
                        )
                    }

                    if (uiState.isProactiveEnabled) {
                        OutlinedTextField(
                            value = uiState.proactiveMorningTime,
                            onValueChange = {
                                viewModel.setProactiveTimes(it, uiState.proactiveEveningTime, uiState.proactiveQuietStart, uiState.proactiveQuietEnd)
                            },
                            label = { Text("Morning Check-in Time") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = uiState.proactiveEveningTime,
                            onValueChange = {
                                viewModel.setProactiveTimes(uiState.proactiveMorningTime, it, uiState.proactiveQuietStart, uiState.proactiveQuietEnd)
                            },
                            label = { Text("Evening Check-in Time") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = uiState.proactiveQuietStart,
                                onValueChange = {
                                    viewModel.setProactiveTimes(uiState.proactiveMorningTime, uiState.proactiveEveningTime, it, uiState.proactiveQuietEnd)
                                },
                                label = { Text("Quiet Hours Start") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = uiState.proactiveQuietEnd,
                                onValueChange = {
                                    viewModel.setProactiveTimes(uiState.proactiveMorningTime, uiState.proactiveEveningTime, uiState.proactiveQuietStart, it)
                                },
                                label = { Text("Quiet Hours End") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            // ── Quick Assistant card ──────────────────────────────────────────
            val context = LocalContext.current
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier              = Modifier.padding(16.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Quick Assistant",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Use the AI from any app — even when PersonalAI is closed — by setting it as your device's default assistant.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "How to activate: Settings → Apps → Default Apps → Digital Assistant → select PersonalAI. " +
                        "Then long-press the Home button (or Side button on Samsung) to open the quick chat overlay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Default Apps Settings")
                    }
                }
            }

            // ── API Usage card ────────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToApiUsage)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Analytics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "API Usage",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Tokens, history, and estimated costs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Data card ─────────────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier              = Modifier.padding(16.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.data_section_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedButton(
                        onClick = viewModel::showClearHistoryDialog,
                        colors  = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border   = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.clear_chat_history))
                    }
                }
            }
        }
    }

    // ── Clear history dialog ──────────────────────────────────────────────────
    if (uiState.showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearHistoryDialog,
            title            = { Text(stringResource(R.string.clear_chat_history)) },
            text             = { Text(stringResource(R.string.clear_chat_confirm_message)) },
            confirmButton    = {
                TextButton(
                    onClick = viewModel::clearChatHistory,
                    colors  = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearHistoryDialog) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Local AI section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LocalAiSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val isDeviceSupported = Build.VERSION.SDK_INT >= LiteRtLlmEngine.MIN_API_LEVEL

    if (!isDeviceSupported) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "On-device AI requires Android 12 (API 31) or higher.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }

    // HuggingFace token (optional, for gated model access)
    Text(
        "Download Gemma models to run AI fully on-device with no internet or API key required.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value         = uiState.hfToken,
        onValueChange = viewModel::onHfTokenChanged,
        label         = { Text("HuggingFace Token (optional)") },
        placeholder   = { Text("hf_…") },
        modifier      = Modifier.fillMaxWidth(),
        singleLine    = true,
        visualTransformation = PasswordVisualTransformation(),
        supportingText = {
            Text(
                "Only needed for gated models. Leave blank for public models.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (uiState.hfTokenSavedSuccessfully) {
            Text(
                "Saved",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
        }
        Button(
            onClick  = viewModel::saveHfToken,
            enabled  = !uiState.isHfTokenSaving
        ) {
            if (uiState.isHfTokenSaving) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text("Save Token")
            }
        }
    }

    Spacer(Modifier.height(4.dp))

    // One card per available model
    uiState.modelStatuses.forEach { status ->
        ModelCard(
            status         = status,
            isSelected     = uiState.localSelectedModel == status.model.modelId,
            onSelect       = { viewModel.selectLocalModel(status.model.modelId) },
            onDownload     = { viewModel.downloadModel(status.model) },
            onCancelDownload = { viewModel.cancelDownload(status.model) },
            onDelete       = { viewModel.deleteModel(status.model) }
        )
    }
}

@Composable
private fun ModelCard(
    status: ModelDownloadUiState,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isSelected, onClick = onSelect)
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(status.model.displayName, style = MaterialTheme.typography.bodyMedium)
                    if (status.isDownloaded) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint   = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "Downloaded",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Error message
            if (status.error != null) {
                Text(
                    "Error: ${status.error}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Progress bar while downloading
            if (status.isDownloading) {
                LinearProgressIndicator(
                    progress = { status.downloadPercent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${status.downloadPercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    status.isDownloading -> {
                        OutlinedButton(
                            onClick = onCancelDownload,
                            modifier = Modifier.weight(1f)
                        ) { Text("Cancel") }
                    }
                    status.isDownloaded -> {
                        OutlinedButton(
                            onClick = onDelete,
                            colors  = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border  = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) { Text("Delete") }
                    }
                    else -> {
                        Button(
                            onClick  = onDownload,
                            modifier = Modifier.weight(1f)
                        ) { Text("Download") }
                    }
                }
            }
        }
    }
}

