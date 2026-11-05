# Lumio Alpha 1.0 (PersonalAI)

> On-device personal AI companion for Android — local LLM inference, offline speech recognition, offline Mandarin TTS, Bluetooth continuous hands-free conversation, 2.2D avatar lip synchronization, and grounded long-term memory.

![Android API](https://img.shields.io/badge/API-36%2B%20(Android%2016)-brightgreen)
![RAM Baseline](https://img.shields.io/badge/RAM%20Baseline-12GB-blue)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=android&logoColor=white)
![AI Mode](https://img.shields.io/badge/AI%20Mode-Local%20Only-orange)

---

## 1. Project Overview

Lumio Alpha 1.0 is a strictly local-first personal AI assistant built for Android 16 (API 36+) targeting a 12GB physical RAM hardware baseline. All AI inference, speech processing, and memory indexing execute 100% on-device with zero automatic cloud fallback.

### Authoritative Documentation
- [Source of Truth](docs/SOURCE_OF_TRUTH.md) — Architectural governance, canonical repository specifications, and privacy boundaries.
- [Project Structure](docs/PROJECT_STRUCTURE.md) — Comprehensive directory tree and module breakdowns across `:app` and `:localllm`.

---

## 2. Key Capabilities

### 🧠 Strict On-Device AI Architecture
- **Local-Only Gateway:** Inference is governed by `LumioAiRouter` and executed via Google AI Edge LiteRT-LM (`:localllm` module).
- **Supported Local Models:** Google Gemma (`gemma-4-E2B-it`, `gemma-4-E4B-it`) and Alibaba Qwen (`qwen-2.5-1.5b-it`, `qwen-2.5-3b-it`).
- **Fail-Closed Privacy:** Out-of-memory errors, initialization issues, and timeouts return local warnings and never trigger cloud AI fallback.

### 🎙️ Bluetooth Continuous Hands-Free Conversation
- **Deterministic Mic Ownership:** Seamless handoff between Android `SpeechRecognizer` during user listening and dedicated background PCM monitoring during TTS playback.
- **Sustained Voice Activity Detection:** Evaluates acoustic energy with endpoint calibration (1.2s - 1.5s silence) and requires sustained speech (`>= 3` consecutive frames `>= 8.5 dB`) to reject speaker echo and ambient noise.
- **Instant Playback Interruption:** Halts TTS playback immediately when the user speaks, cancels active generation jobs, invalidates stale responses, and returns directly to listening.
- **Three Selectable Modes:** *Natural Conversation*, *Active Interaction*, and *Full Listening*.

### 👤 2.2D Realistic Avatar & Real-Time Lip Sync
- **Single-Portrait 2.2D Rendering:** Targets 854x480 resolution at 24 FPS with near-zero memory footprint.
- **Speech-Driven Visemes:** Real-time mouth blendshape mapping (`SILENCE`, `AA`, `OH`, `EE`, `IH`, `OU`, `FF`, `MM`) coupled with natural idle breathing and eye blinks.

### 🔊 Fail-Closed Offline Speech
- **On-Device ASR:** Restricts recognition strictly to verified offline language models (`zh-CN` and `en-US`), failing closed with `LOCAL_ASR_UNAVAILABLE` on unsupported languages.
- **Offline Mandarin TTS:** Checks `Voice.isNetworkConnectionRequired == false` before playback; network-dependent voices are rejected.

### ⏰ Context-Aware Proactive Conversation
- **WorkManager Grounded Scheduling:** Recomputes exact millisecond delays to target morning/evening check-in times while respecting quiet hours.
- **User-Controlled Lifecycle:** Disabling proactively cancels pending background work; `BootReceiver` restores scheduling only when explicitly enabled.

---

## 3. Project Architecture & Modules

The project is structured into two Gradle modules:

```
PersonalAI/
├── app/        # Presentation (Compose UI), ViewModels, Audio, Room Database v9, Use Cases
├── localllm/   # Google AI Edge LiteRT-LM runtime, Model Center, Hardware Capability Evaluator
└── docs/       # Authoritative engineering documentation
```

---

## 4. Getting Started & Build Instructions

### Prerequisites
- Android Studio Ladybug / Meerkat or newer
- JDK 17+
- Android 16 (API 36) SDK

### 1. Clone the Canonical Repository

```bash
git clone https://github.com/bopeng201910-cmd/PersonalAI.git
cd PersonalAI
```

### 2. Run Automated Unit Tests

```bash
./gradlew testDebugUnitTest
```

### 3. Build Debug APK

```bash
./gradlew assembleDebug
```

---

## 5. Development & Delivery Rules

- **Canonical Repository:** `bopeng201910-cmd/PersonalAI`
- **Branch Strategy:** Dedicated feature branches only; direct commits or pushes to `main` are strictly prohibited.
- **History Preservation:** Never force-push or rewrite shared commit history.
