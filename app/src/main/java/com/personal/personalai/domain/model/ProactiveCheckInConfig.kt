package com.personal.personalai.domain.model

data class ProactiveCheckInConfig(
    val isEnabled: Boolean = false,
    val morningTime: String = "09:00",
    val eveningTime: String = "20:00",
    val quietHoursStart: String = "22:00",
    val quietHoursEnd: String = "07:00",
    val allowVoicePrompt: Boolean = false
)
