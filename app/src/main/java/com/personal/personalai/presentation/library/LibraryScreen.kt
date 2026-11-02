package com.personal.personalai.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.personalai.R
import com.personal.personalai.domain.model.CandidateMemory
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.model.MemoryCategory
import com.personal.personalai.domain.model.MemorySourceType
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissInfoMessage()
        }
    }

    // File detail dialog
    uiState.openedFile?.let { (filename, content) ->
        FileDetailDialog(
            filename = filename,
            content = content,
            onDismiss = viewModel::closeFile
        )
    }

    // Candidate memory edit dialog
    uiState.editingCandidate?.let { candidate ->
        EditCandidateDialog(
            candidate = candidate,
            onDismiss = viewModel::dismissEditingCandidate,
            onSave = { newContent, newTopic ->
                viewModel.approveCandidate(candidate, newContent, newTopic)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val tabs = listOf(
                stringResource(R.string.library_tab_files),
                "Review (${uiState.candidateMemories.size})",
                stringResource(R.string.library_tab_memories)
            )
            TabRow(selectedTabIndex = uiState.selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = uiState.selectedTab == index,
                        onClick = { viewModel.onTabSelected(index) },
                        text = { Text(title) }
                    )
                }
            }

            when (uiState.selectedTab) {
                0 -> FilesTab(
                    files = uiState.files,
                    isLoading = uiState.isLoadingFiles,
                    onFileClick = viewModel::openFile
                )
                1 -> CandidatesReviewTab(
                    candidates = uiState.candidateMemories,
                    isLoading = uiState.isLoadingCandidates,
                    isExtracting = uiState.isExtracting,
                    onTriggerReview = viewModel::triggerReviewExtraction,
                    onApprove = { viewModel.approveCandidate(it) },
                    onEdit = viewModel::startEditingCandidate,
                    onDiscard = viewModel::discardCandidate,
                    onApproveSelected = { selectedIds ->
                        viewModel.approveCandidates(selectedIds.toList())
                    },
                    onApproveAll = viewModel::approveAllCandidates
                )
                2 -> MemoriesTab(
                    memories = uiState.memories,
                    isLoading = uiState.isLoadingMemories,
                    onDeleteMemory = viewModel::deleteMemory
                )
            }
        }
    }
}

@Composable
private fun FilesTab(
    files: List<FileEntry>,
    isLoading: Boolean,
    onFileClick: (FileEntry) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            files.isEmpty() -> EmptyState(
                icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                text = stringResource(R.string.library_no_files)
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files, key = { it.name }) { file ->
                    FileCard(file = file, onClick = { onFileClick(file) })
                }
            }
        }
    }
}

@Composable
private fun FileCard(file: FileEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleMedium)
            if (file.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    file.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatBytes(file.sizeBytes)}  •  ${formatDate(file.lastModified)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CandidatesReviewTab(
    candidates: List<CandidateMemory>,
    isLoading: Boolean,
    isExtracting: Boolean,
    onTriggerReview: () -> Unit,
    onApprove: (CandidateMemory) -> Unit,
    onEdit: (CandidateMemory) -> Unit,
    onDiscard: (CandidateMemory) -> Unit,
    onApproveSelected: (Set<Long>) -> Unit,
    onApproveAll: () -> Unit
) {
    var selectedIds by remember(candidates) { mutableStateOf(emptySet<Long>()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onTriggerReview,
                enabled = !isExtracting,
                modifier = Modifier.weight(1f)
            ) {
                if (isExtracting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Scanning...")
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan Chats")
                }
            }

            if (candidates.isNotEmpty()) {
                if (selectedIds.isNotEmpty()) {
                    Button(onClick = { onApproveSelected(selectedIds) }) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save Selected (${selectedIds.size})")
                    }
                } else {
                    Button(onClick = onApproveAll) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save All (${candidates.size})")
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                candidates.isEmpty() -> EmptyState(
                    icon = {
                        Icon(
                            Icons.Default.RateReview,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    text = "No pending candidate memories.\nTap 'Check Recent Chats' to scan for new ones."
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(candidates, key = { it.id }) { candidate ->
                        CandidateCard(
                            candidate = candidate,
                            isSelected = candidate.id in selectedIds,
                            onToggleSelect = {
                                selectedIds = if (candidate.id in selectedIds) {
                                    selectedIds - candidate.id
                                } else {
                                    selectedIds + candidate.id
                                }
                            },
                            onApprove = { onApprove(candidate) },
                            onEdit = { onEdit(candidate) },
                            onDiscard = { onDiscard(candidate) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: CandidateMemory,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onApprove: () -> Unit,
    onEdit: () -> Unit,
    onDiscard: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text(formatCategoryLabel(candidate.category), style = MaterialTheme.typography.labelSmall) }
                    )
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                if (candidate.sourceType == MemorySourceType.EXPLICIT_FACT) "Direct Fact" else "Inferred",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(candidate.content, style = MaterialTheme.typography.bodyLarge)

            if (candidate.originalSnippet.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Source: \"${candidate.originalSnippet}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDiscard) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onApprove) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
            }
        }
    }
}

private fun formatCategoryLabel(category: MemoryCategory): String = when (category) {
    MemoryCategory.USER_PREFERENCE    -> "Preference"
    MemoryCategory.IMPORTANT_DECISION -> "Decision"
    MemoryCategory.LONG_TERM_GOAL     -> "Goal"
    MemoryCategory.PROJECT_PROGRESS   -> "Progress"
    MemoryCategory.TODO_ITEM          -> "Todo"
    MemoryCategory.OTHER_IMPORTANT    -> "Important"
}

@Composable
private fun EditCandidateDialog(
    candidate: CandidateMemory,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var content by remember { mutableStateOf(candidate.content) }
    var topic by remember { mutableStateOf(candidate.topic) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Candidate Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Topic (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Memory Content") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(content, topic) },
                enabled = content.isNotBlank()
            ) {
                Text("Approve & Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun MemoriesTab(
    memories: List<Memory>,
    isLoading: Boolean,
    onDeleteMemory: (Memory) -> Unit
) {
    var memoryToDelete by remember { mutableStateOf<Memory?>(null) }

    memoryToDelete?.let { memory ->
        AlertDialog(
            onDismissRequest = { memoryToDelete = null },
            title = { Text(stringResource(R.string.library_delete_memory_title)) },
            text = { Text(stringResource(R.string.library_delete_memory_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteMemory(memory)
                    memoryToDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { memoryToDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            memories.isEmpty() -> EmptyState(
                icon = { Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                text = stringResource(R.string.library_no_memories)
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(memories, key = { it.id }) { memory ->
                    MemoryCard(memory = memory, onDelete = { memoryToDelete = memory })
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(memory: Memory, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (memory.topic.isNotBlank()) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(memory.topic, style = MaterialTheme.typography.labelSmall) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    formatTimestamp(memory.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun FileDetailDialog(filename: String, content: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            SelectionContainer {
                Text(
                    content,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(content))
                onDismiss()
            }) { Text(stringResource(R.string.library_copy_all)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun EmptyState(icon: @Composable () -> Unit, text: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            icon()
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
}

private fun formatDate(iso: String): String = try {
    val instant = Instant.parse(iso)
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(instant)
} catch (e: Exception) {
    iso
}

private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))
