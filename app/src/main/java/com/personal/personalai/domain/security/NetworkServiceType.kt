package com.personal.personalai.domain.security

enum class NetworkServiceType {
    CLOUD_AI,       // OpenAI Responses API
    CLOUD_SPEECH,   // Whisper audio transcription
    WEB_SEARCH,     // Serper API
    LAN_PRIVATE     // Ollama on local private subnet
}
