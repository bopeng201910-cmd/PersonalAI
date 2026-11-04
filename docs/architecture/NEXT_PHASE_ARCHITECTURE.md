# PersonalAI Next Phase Architecture Specification

## 1. Coordinated Independent State Domains

PersonalAI coordinates three asynchronous state domains. To guarantee architectural resilience and prevent cross-domain coupling (e.g., cancelling transcription must never kill an ongoing agent loop, and agent cancellation must never abort background memory indexing), each domain maintains strict boundary separation:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        ChatViewModel Lifecycle                         │
├─────────────────────┬───────────────────────────┬──────────────────────┤
│ 1. Conversation     │ 2. Voice & Transcription  │ 3. Background Tasks  │
│    Domain           │    Domain                 │    Domain            │
├─────────────────────┼───────────────────────────┼──────────────────────┤
│ State:              │ State:                    │ State:               │
│ - IDLE              │ - IDLE                    │ - PENDING            │
│ - UNDERSTANDING     │ - RECORDING               │ - RUNNING            │
│ - THINKING          │ - STOPPING                │ - WAITING            │
│ - RESPONDING        │ - TRANSCRIBING            │ - COMPLETED          │
│ - WAITING (User)    │ - COMPLETED               │ - CANCELLED          │
│ - CANCELLED         │ - CANCELLED               │ - FAILED             │
│ - ERROR/RECOVERY    │ - ERROR                   │                      │
│                     │                           │                      │
│ Job:                │ Job:                      │ Job:                 │
│ currentMessageJob   │ currentTranscriptionJob   │ memoryReviewJob      │
└─────────────────────┴───────────────────────────┴──────────────────────┘
```

### Domain 1: Conversation Lifecycle
- **Owner**: `ChatViewModel` (`currentMessageJob`).
- **Valid Transitions**:
  `IDLE` $\to$ `UNDERSTANDING` $\to$ `THINKING` $\to$ `RESPONDING` $\to$ `IDLE` (or $\to$ `WAITING` if `AskUserTool` triggers).
  Any active state $\to$ `CANCELLED` (via `cancelCurrentMessage()`) or $\to$ `ERROR` (on network/model failure).
- **Cancellation**: Cancelling `currentMessageJob` safely halts the `AgentLoopUseCase`, emits clean completion, and resets `isLoading = false` without surfacing false error banners.
- **Independence**: Cancelling a message generation has no effect on audio recording or background memory scans.

### Domain 2: Voice Capture & Transcription Lifecycle
- **Owner**: `ChatViewModel` (`currentTranscriptionJob` and `AudioRecorder`).
- **Valid Transitions**:
  `IDLE` $\to$ `RECORDING` $\to$ `STOPPING` $\to$ `TRANSCRIBING` $\to$ `COMPLETED` $\to$ `IDLE`.
  Any state $\to$ `CANCELLED` or $\to$ `ERROR`.
- **Job & Token Ownership**: Each transcription request generates an incrementing `transcriptionRequestId`. Stale responses from superseded requests are discarded.
- **Underlying Cancellation**: Utilizes `suspendCancellableCoroutine` wrapping OkHttp's asynchronous `Call.enqueue()`. When the job is cancelled, OkHttp's `Call.cancel()` is immediately invoked, closing network sockets and reclaiming bandwidth.
- **Resource Cleanup**: Temporary audio files (`.m4a`) are guaranteed deletion in a `finally` block across success, cancellation, and failure.

### Domain 3: Background Tasks & Daily Memory Review
- **Owner**: `ChatViewModel` (`memoryReviewJob`) and standalone WorkManager tasks.
- **Valid Transitions**:
  `PENDING` $\to$ `RUNNING` $\to$ `COMPLETED` (or $\to$ `FAILED` / `CANCELLED`).
- **Synchronization**: Serialized via `Mutex` in `ExtractCandidateMemoriesUseCase` to ensure concurrent extraction passes cannot execute in parallel or advance the message cursor prematurely.

---

## 2. Personalization Governance and Adaptation Metrics

PersonalAI dynamically tunes interaction parameters without mutating user profile facts without permission.

### Preference Dimensions
1. **Response Length**: Concise vs. Detailed.
2. **Tone**: Direct, Empathetic, Professional, or Casual.
3. **Speaking Pace & Audio Cadence**: Fast, Normal, or Deliberate.
4. **Listening vs. Advice**: Active listening questions vs. Direct actionable recommendations.

### Governance Rules
- **No Transient Escalation**: A one-off prompt instruction (e.g., *"give me a 1-sentence answer"*) is treated as session-scoped and **never** escalated to a permanent preference without user confirmation.
- **Decay & Recency**: Inferred preferences lose weight over a 14-day inactivity window unless reinforced or confirmed.
- **Conflict Resolution**: User-confirmed facts in `memories` unconditionally override AI inferences.

### Natural Interaction Index (NII)
- **Definition**: NII is an internal diagnostic metric evaluating **AI adaptation quality and conversational efficiency**, measuring how effectively the AI adheres to confirmed user preferences and minimizes repetitive corrections.
- **Ethical Boundary**: NII is **never** a score assigned to the user, nor is it used to profile user behavior. It measures model assistant efficacy only.

---

## 3. UI Behavior Specifications across Primary Navigation

The application is structured into four primary destinations:

```
[ Conversation ]  ──  [ Memory Review ]  ──  [ Tasks ]  ──  [ Settings ]
```

### 1. Conversation (`ChatScreen`)
- **Components**: Message history list, dynamic agent status banner, input bar with integrated mic button, interactive question/permission cards.
- **Loading / Empty States**: Clean welcome suggestions when empty; inline typing/tool indicators when active.
- **Error & Retry**: Non-intrusive snackbar with explicit retry button; cancellation cleanly dismisses status without error.

### 2. Memory Review (`LibraryScreen`)
- **Components**: Tabbed interface ("Memories" vs "Review"), candidate review card list with inline edit, bulk action bar.
- **Empty State**: Clear illustration stating *"No memories pending review"*.
- **Confirmation & Undo**: Single-tap approval; batch approval with partial failure isolation; inline edit preserved if save fails.

### 3. Tasks (`LocationTasksScreen` & `ScheduledTasksScreen`)
- **Components**: Geofence task list, schedule trigger management, permission status banners.
- **Error Handling**: Graceful fallback if background location permission is denied.

### 4. Settings (`SettingsScreen`)
- **Components**: AI Model Provider selection (Local Gemini Nano vs Cloud OpenAI), API key storage, usage logging summaries, privacy toggles.

---

## 4. Privacy, Security, and Data Flow Governance

### Data Flow Architecture

```
                    ┌───────────────────────────────────┐
                    │        PersonalAI Device          │
                    │                                   │
                    │  ┌──────────────┐ ┌─────────────┐ │
                    │  │ Room Database│ │ Local LLM   │ │
                    │  │ (SQLite v8)  │ │ (LiteRT-LM) │ │
                    │  └──────┬───────┘ └──────▲──────┘ │
                    │         │                │        │
                    │         ▼                │        │
                    │  ┌───────────────────────┴─────┐  │
                    │  │       LumioAiRouter         │  │
                    │  └──────────────┬──────────────┘  │
                    └─────────────────┼─────────────────┘
                                      │ (Only if Cloud Selected &
                                      │  User Consented)
                                      ▼
                    ┌───────────────────────────────────┐
                    │    External Services (HTTPS)      │
                    │  - OpenAI Whisper (Transcribe)    │
                    │  - OpenAI GPT-4o (Chat/Review)    │
                    └───────────────────────────────────┘
```

### Privacy & Cloud Transparency Directives
1. **Strict Local-First Guarantee**: All core functionalities prioritize on-device execution. The app is **not** fully offline-local if cloud providers are enabled; the UI clearly displays when a request routes to an external cloud endpoint.
2. **Zero Silent Fallback**: If a local model fails (e.g., `OutOfMemoryError`), `LumioAiRouter` immediately surfaces the failure to the user. It **never** silently sends data to a cloud provider without explicit user permission.
3. **Redaction & Credential Hygiene**:
   - API keys and Bearer tokens are stored exclusively in encrypted Android DataStore and never written to logs or error messages.
   - Raw audio data and user transcription transcripts are strictly prohibited from telemetry or debug logs.
   - Temporary recording files are purged immediately following transcription processing or failure.

---

## 5. Comprehensive Testing Matrix

| Component / Scenario | Verification Type | Tooling | Required Assertion |
| :--- | :--- | :--- | :--- |
| **Use-Case Cancellation** | Automated Unit Test | `DailyMemoryReviewTest` | `CancellationException` propagates untouched; coroutine finishes as cancelled. |
| **Whisper Request Cancel** | Automated Unit Test | `WhisperDataSourceTest` | Underlying OkHttp `Call.cancel()` is invoked; sockets terminated. |
| **Whisper Late Callback** | Automated Unit Test | `WhisperDataSourceTest` | Late `onResponse` after cancellation causes no double-resume or crash. |
| **ChatViewModel Transcription** | Automated Unit Test | `ChatViewModelTranscriptionLifecycleTest`| Starting transcription #2 cancels #1; stale results ignored; temp audio deleted. |
| **Agent Loop Cancellation** | Automated Unit Test | `AgentLoopCancellationTest` | Tool loop halts immediately on cancellation; UI state resets without error. |
| **Database Migration v7->v8**| Automated Unit Test | `DatabaseMigration7To8Test` | Real SQLite table constraints, defaults, and data preservation verified. |
| **Mutex Concurrency** | Automated Unit Test | `DailyMemoryReviewTest` | Concurrent extraction serialized; cursor updates strictly sequential. |
| **Strict JSON Extraction** | Automated Unit Test | `DailyMemoryReviewTest` | Hallucinated `source_message_id` rejected; invalid JSON fails safely. |
| **UI Dialog & Feedback** | Automated Unit Test | `DailyMemoryReviewTest` | Failed approval retains dialog and preserves user edits; success closes dialog. |
| **Process Recreation** | Manual / Integration | Android Test Orchestrator | ViewModel restores state cleanly from saved state without duplicate jobs. |
| **Logging Hygiene** | Automated Code Audit | Static Analysis / Lint | Zero instances of tokens, audio buffers, or dialogue text logged. |
