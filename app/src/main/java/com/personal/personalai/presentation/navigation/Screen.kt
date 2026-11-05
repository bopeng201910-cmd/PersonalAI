package com.personal.personalai.presentation.navigation

sealed class Screen(val route: String) {
    object Chat : Screen("chat")
    object ScheduledTasks : Screen("scheduled_tasks")
    object LocationTasks : Screen("location_tasks")
    object Library : Screen("library")
    object Settings : Screen("settings")
    object ApiUsage : Screen("api_usage")
    object ModelCenter : Screen("model_center")
}
