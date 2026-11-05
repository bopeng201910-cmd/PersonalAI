package com.personal.personalai.presentation.modelcenter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelCenterScreen(
    onNavigateBack: () -> Unit,
    viewModel: ModelCenterViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.infoMessage, uiState.error) {
        val msg = uiState.infoMessage ?: uiState.error
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lumio Model Center") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Device assessment card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Device Assessment (HONOR 500 Baseline)", style = MaterialTheme.typography.titleMedium)
                        }
                        val assess = uiState.deviceAssessment
                        if (assess != null) {
                            Text("• Android Version: API ${assess.androidVersion} (Android 16)")
                            Text("• Physical RAM: %.1f GB (12GB Baseline: %s)".format(assess.totalPhysicalRamGb, if (assess.is12GbRamBaselineMet) "Met" else "Under"))
                            Text("• Available Storage: %.1f GB".format(assess.availableStorageGb))
                            Text("• Recommended Model: ${assess.recommendedModel.displayName}", color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("Evaluating device hardware capabilities…")
                        }
                    }
                }
            }

            // Model Catalog list
            items(uiState.models) { cardState ->
                val model = cardState.model
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (cardState.isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = cardState.isSelected,
                                onClick = { if (cardState.isDownloaded) viewModel.selectModel(model) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                                Text("Provider: ${model.provider} | License: ${model.license}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (cardState.isDownloaded) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Installed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        Text("Languages: ${model.supportedLanguages.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        Text("Est. Runtime RAM: ~%.1f GB".format(model.estimatedRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)), style = MaterialTheme.typography.bodySmall)

                        if (cardState.error != null) {
                            Text("Error: ${cardState.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        if (cardState.isDownloading) {
                            LinearProgressIndicator(
                                progress = { cardState.downloadPercent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("${cardState.downloadPercent}%", style = MaterialTheme.typography.labelSmall)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                cardState.isDownloading -> {
                                    OutlinedButton(onClick = { viewModel.cancelDownload(model) }, modifier = Modifier.weight(1f)) {
                                        Text("Cancel")
                                    }
                                }
                                cardState.isDownloaded -> {
                                    if (!cardState.isSelected) {
                                        Button(onClick = { viewModel.selectModel(model) }, modifier = Modifier.weight(1f)) {
                                            Text("Select Active")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.deleteModel(model) },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Remove")
                                    }
                                }
                                else -> {
                                    Button(onClick = { viewModel.downloadModel(model) }, modifier = Modifier.weight(1f)) {
                                        Text("Download & Install")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
