# Lumio Alpha 1.0 — Source of Truth

## 1. Canonical Repository

- **Repository:** `https://github.com/bopeng201910-cmd/PersonalAI`
- **Primary Integration Branch (PR #2):** `jules-13940602427104912650-e600ec00-9400594660536782428`
- **Application Baseline:** Native Android Kotlin, Android 16 (API 36+), 12GB physical RAM baseline.

## 2. Project Architecture & Modules

The repository follows Clean Architecture principles partitioned across two Gradle modules:

- `:app` — Presentation (Jetpack Compose UI, Navigation, ViewModels), Data Sources (Room Database v9, DataStore, Bluetooth SCO audio routing, Android offline ASR & TTS, WorkManager background workers), and Domain Use Cases.
- `:localllm` — On-device Google AI Edge LiteRT-LM runtime, local model catalog (Google Gemma, Alibaba Qwen), device hardware capability evaluator, and transactional model download manager.

## 3. Core Technical Pillars

1. **Strict Local-Only AI Architecture:** All model inference is routed through `LumioAiRouter` and `LocalLlmDataSource`. Cloud AI providers (OpenAI, remote Ollama) and cloud Whisper uploads are blocked. Model OOM or execution failures fail closed with local explanations.
2. **Deterministic Microphone Ownership & Bluetooth Continuous Voice:**
   - Single-owner microphone state machine: `SpeechRecognizer` owns capture during user listening, and is stopped before dedicated PCM `AudioRecord` monitoring begins during TTS playback.
   - Sustained voice activity detection (`>= 3` consecutive frames `>= 8.5 dB`) prevents acoustic speaker echo false interruptions.
   - On interruption: stops TTS playback immediately, cancels active turn jobs, invalidates stale callbacks, and returns cleanly to `LISTENING`.
   - `PAUSED` and `STANDBY` release all microphone recording.
3. **Fail-Closed Offline Speech Services:**
   - `AndroidOfflineSpeechRecognizer` enforces on-device recognizer presence and explicitly supports verified `zh-CN` and `en-US` locales without falling back to network speech recognition.
   - `OfflineMandarinTtsEngine` verifies `voice.isNetworkConnectionRequired == false` before playback.
4. **Model Download Safety & Atomic Installation:**
   - Production models require trusted official SHA-256 checksums and exact expected lengths before installation.
   - Downloads stage to unique timestamped files with atomic rename and backup rollback to preserve active working models.
5. **Proactive Interaction Lifecycle:**
   - DataStore-backed schedule calculation for morning/evening check-in occurrences respecting quiet hours.
   - Disabling cancels pending WorkManager tasks; `BootReceiver` restores scheduling only when enabled.

## 4. Build & Test Instructions

```bash
# Run all unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```
