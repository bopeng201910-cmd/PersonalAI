package com.personal.personalai.domain.audio

enum class ContinuousVoiceState {
    STANDBY,
    LISTENING,
    THINKING,
    SPEAKING,
    PAUSED
}

enum class ConversationMode {
    NATURAL_CONVERSATION, // Standard continuous turn-taking
    ACTIVE_INTERACTION,   // Proactive concise follow-ups
    FULL_LISTENING        // Extended listening pause threshold
}
