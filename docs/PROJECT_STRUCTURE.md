# Lumio Alpha 1.0 — Project Structure

```
PersonalAI/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   ├── wrapper/
│   └── libs.versions.toml
├── docs/
│   ├── SOURCE_OF_TRUTH.md
│   └── PROJECT_STRUCTURE.md
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/personal/personalai/
│       │   │   ├── MainActivity.kt
│       │   │   ├── PersonalAIApplication.kt
│       │   │   ├── data/
│       │   │   │   ├── audio/
│       │   │   │   │   ├── AndroidOfflineSpeechRecognizer.kt
│       │   │   │   │   ├── AudioRecorderImpl.kt
│       │   │   │   │   ├── BluetoothHeadsetManagerImpl.kt
│       │   │   │   │   ├── ContinuousVoiceManager.kt
│       │   │   │   │   ├── LocalVoiceActivityDetector.kt
│       │   │   │   │   └── OfflineMandarinTtsEngine.kt
│       │   │   │   ├── avatar/
│       │   │   │   │   └── SpeechLipSyncEngine.kt
│       │   │   │   ├── datasource/ai/
│       │   │   │   │   └── LocalLlmDataSource.kt
│       │   │   │   ├── local/
│       │   │   │   │   ├── PersonalAIDatabase.kt
│       │   │   │   │   ├── dao/
│       │   │   │   │   └── entity/
│       │   │   │   ├── repository/
│       │   │   │   │   ├── AiRepositoryImpl.kt
│       │   │   │   │   ├── CandidateMemoryRepositoryImpl.kt
│       │   │   │   │   ├── ChatRepositoryImpl.kt
│       │   │   │   │   └── ConversationRepositoryImpl.kt
│       │   │   │   ├── router/
│       │   │   │   │   └── LumioAiRouterImpl.kt
│       │   │   │   └── tools/
│       │   │   ├── di/
│       │   │   │   └── AppModule.kt
│       │   │   ├── domain/
│       │   │   │   ├── audio/
│       │   │   │   ├── avatar/
│       │   │   │   │   └── AvatarModel.kt
│       │   │   │   ├── model/
│       │   │   │   ├── repository/
│       │   │   │   ├── router/
│       │   │   │   ├── tools/
│       │   │   │   └── usecase/
│       │   │   ├── presentation/
│       │   │   │   ├── avatar/
│       │   │   │   │   └── AvatarView.kt
│       │   │   │   ├── chat/
│       │   │   │   ├── modelcenter/
│       │   │   │   ├── navigation/
│       │   │   │   └── settings/
│       │   │   ├── receiver/
│       │   │   │   └── BootReceiver.kt
│       │   │   └── worker/
│       │   │       └── ProactiveCheckInWorker.kt
│       │   └── res/
│       └── test/java/com/personal/personalai/
└── localllm/
    ├── build.gradle.kts
    └── src/
        ├── main/java/com/personal/personalai/localllm/
        │   ├── api/
        │   │   ├── DeviceCapabilityEvaluator.kt
        │   │   └── LocalModel.kt
        │   ├── download/
        │   │   ├── ModelDownloadManager.kt
        │   │   └── ModelDownloadWorker.kt
        │   └── engine/
        │       └── LiteRtLlmEngine.kt
        └── test/java/com/personal/personalai/localllm/
```
