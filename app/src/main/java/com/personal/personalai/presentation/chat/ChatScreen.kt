package com.personal.personalai.presentation.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.personal.personalai.presentation.common.MarkdownText
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.personal.personalai.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.model.MessageRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val activity = context as? Activity

    // Runtime permission launcher — routes denial through ViewModel so the error
    // surfaces via uiState.error and is shown by the LaunchedEffect below.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) viewModel.onMicPermissionDenied()
        // If granted: user presses the mic again — standard Android convention
    }

    // Single-permission launcher for agent-requested permissions (SMS, Contacts, Notifications, etc.)
    val agentPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        uiState.pendingPermissionRequest?.let { req ->
            if (!granted && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, req.permission)
            ) {
                // shouldShowRationale is false after denial → "Don't ask again" was checked.
                // Open Settings so the user can manually grant it.
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            }
            viewModel.resolvePermission(req.id, granted)
        }
    }

    // Multi-permission launcher used for fine+coarse location together.
    val agentMultiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        uiState.pendingPermissionRequest?.let { req ->
            val granted = results[req.permission] == true
            if (!granted && activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, req.permission)
            ) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            }
            viewModel.resolvePermission(req.id, granted)
        }
    }

    // Fire the appropriate launcher when the agent loop requests a permission.
    val pendingPerm = uiState.pendingPermissionRequest
    LaunchedEffect(pendingPerm?.id) {
        val req = pendingPerm ?: return@LaunchedEffect
        when (req.permission) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    // Request fine+coarse first; the agent will retry and hit the Settings path next
                    agentMultiPermissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                } else {
                    // Fine already granted — Android requires Settings for background location
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                    viewModel.resolvePermission(req.id, false)
                }
            }
            Manifest.permission.ACCESS_FINE_LOCATION -> {
                // Request fine and coarse together for better UX (single dialog)
                agentMultiPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }
            else -> agentPermissionLauncher.launch(req.permission)
        }
    }

    var initialScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            if (!initialScrollDone) {
                listState.scrollToItem(uiState.messages.size - 1)
                initialScrollDone = true
            } else {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    // Wrap in Box so we can position SnackbarHost above the outer bottom navigation bar.
    // If we leave snackbarHost inside the inner Scaffold, it renders at absolute bottom of
    // screen and is hidden behind AppNavGraph's NavigationBar. Moving it to a standalone
    // SnackbarHost with padding(bottom = innerPadding.calculateBottomPadding()) fixes this.
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.toggleHistoryDrawer() }) {
                            Icon(Icons.Default.History, contentDescription = "Conversation History")
                        }
                    },
                    title = { Text(uiState.activeConversationTitle) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    actions = {
                        IconButton(onClick = { viewModel.startNewConversation() }) {
                            Icon(Icons.Default.Add, contentDescription = "New Conversation")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_icon_description))
                        }
                    }
                )
            }
        ) { scaffoldPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding)
            ) {
                if (uiState.messages.isEmpty() && !uiState.isLoading) {
                    WelcomePlaceholder(modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.messages, key = { it.id }) { message ->
                            MessageBubble(message = message)
                        }
                        if (uiState.isLoading) {
                            item(key = "loading") { TypingIndicator() }
                        }
                    }
                }

                uiState.agentStatusMessage?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // Permission rationale card shown while the system dialog is displayed
                uiState.pendingPermissionRequest?.let { req ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("🔐", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = PermissionRationale.get(req.permission),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                // Question card shown when the agent is waiting for the user's answer
                uiState.pendingInputRequest?.let { request ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("❓", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = request.question,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            if (!request.quickReplies.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    request.quickReplies.forEach { reply ->
                                        androidx.compose.material3.OutlinedButton(
                                            onClick = { viewModel.answerQuickReply(reply) }
                                        ) {
                                            Text(reply)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (uiState.isHistoryDrawerOpen) {
                    androidx.compose.material3.ModalBottomSheet(
                        onDismissRequest = { viewModel.toggleHistoryDrawer(false) }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Conversations", style = MaterialTheme.typography.titleLarge)
                                androidx.compose.material3.TextButton(
                                    onClick = { viewModel.startNewConversation() }
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(Modifier.size(4.dp))
                                    Text("New")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(uiState.conversations, key = { it.id }) { conv ->
                                    val isSelected = conv.id == uiState.activeConversationId
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .pointerInput(conv.id) {
                                                // Handle clicks
                                            },
                                        onClick = { viewModel.openConversation(conv.id) }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    conv.title,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    formatTimestamp(conv.updatedAt),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                            if (conv.id != "default") {
                                                IconButton(onClick = { viewModel.deleteConversation(conv.id) }) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Delete conversation",
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                MessageInputBar(
                    text = uiState.inputText,
                    onTextChanged = viewModel::onInputChanged,
                    onSend = viewModel::sendMessage,
                    onStop = viewModel::cancelCurrentMessage,
                    voiceState = uiState.voiceState,
                    onRecordPress = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.onRecordStart()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onRecordRelease = viewModel::onRecordStop,
                    onRecordCancel = viewModel::onRecordCancel,
                    // Allow input while agent waits for an answer; lock during permission requests
                    isLoading = uiState.isLoading && uiState.pendingInputRequest == null
                        && uiState.pendingPermissionRequest == null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun WelcomePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text("👋", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.welcome_headline),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                stringResource(R.string.welcome_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            SelectionContainer {
                MarkdownText(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    codeBackgroundColor = if (isUser)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    codeTextColor = if (isUser)
                        MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        if (message.hasCreatedTask && message.createdTaskTitle != null) {
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = stringResource(R.string.task_scheduled, message.createdTaskTitle ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Text(
            text = formatTimestamp(message.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    // Three dots with staggered 150 ms delays — creates a left-to-right wave
    val dotOffsets = listOf(0, 150, 300).map { delayMs ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = -8f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 900
                    0f  at 0   using LinearEasing
                    -8f at 300 using LinearEasing
                    0f  at 600 using LinearEasing
                    0f  at 900
                },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(delayMs)
            ),
            label = "dot_$delayMs"
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                dotOffsets.forEach { offsetState ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .offset(y = offsetState.value.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                shape = CircleShape
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageInputBar(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit = {},
    voiceState: VoiceState,
    onRecordPress: () -> Unit,
    onRecordRelease: () -> Unit,
    onRecordCancel: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    // Pulse animation for the recording circle background (alpha 15% → 45%)
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Surface(
        modifier = modifier,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message_placeholder)) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )

            // Push-to-talk mic button: hold to record, release to transcribe.
            // Uses awaitEachGesture + awaitFirstDown + waitForUpOrCancellation —
            // the correct Compose API for continuous press-and-hold detection.
            val micEnabled = !isLoading && voiceState != VoiceState.TRANSCRIBING
            Box(
                modifier = Modifier
                    .size(56.dp)               // slightly larger container gives room for the recording circle
                    .pointerInput(micEnabled) {
                        if (micEnabled) {
                            awaitEachGesture {
                                // Fires immediately when the finger touches down
                                awaitFirstDown(requireUnconsumed = false)
                                onRecordPress()
                                // Suspends until the finger lifts (non-null) or
                                // the gesture is cancelled/dragged off (null)
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    up.consume()
                                    onRecordRelease()
                                } else {
                                    onRecordCancel()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                when (voiceState) {
                    VoiceState.IDLE -> Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.mic_hold_description),
                        modifier = Modifier.size(26.dp),
                        tint = if (micEnabled) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.outline
                    )
                    VoiceState.RECORDING -> {
                        // Pulsing red circle background makes recording state unmistakable
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = stringResource(R.string.mic_recording_description),
                                modifier = Modifier.size(34.dp),  // noticeably larger than IDLE
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    VoiceState.TRANSCRIBING -> CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            if (isLoading) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.StopCircle,
                        contentDescription = "Stop response",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send_description),
                        tint = if (text.isNotBlank())
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
