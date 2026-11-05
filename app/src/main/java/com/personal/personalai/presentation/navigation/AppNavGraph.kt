package com.personal.personalai.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.personal.personalai.R
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.personal.personalai.presentation.apiusage.ApiUsageScreen
import com.personal.personalai.presentation.chat.ChatScreen
import com.personal.personalai.presentation.library.LibraryScreen
import com.personal.personalai.presentation.locationtasks.LocationTasksScreen
import com.personal.personalai.presentation.schedule.ScheduledTasksScreen
import com.personal.personalai.presentation.settings.SettingsScreen

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val bottomNavScreens = listOf(Screen.Chat, Screen.ScheduledTasks, Screen.LocationTasks, Screen.Library)

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavScreens.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            when (screen) {
                                Screen.Chat -> Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_chat))
                                Screen.ScheduledTasks -> Icon(Icons.Default.DateRange, contentDescription = stringResource(R.string.nav_schedule))
                                Screen.LocationTasks -> Icon(Icons.Default.LocationOn, contentDescription = stringResource(R.string.nav_places))
                                Screen.Library -> Icon(Icons.Default.Bookmarks, contentDescription = stringResource(R.string.nav_library))
                                else -> Unit
                            }
                        },
                        label = {
                            Text(
                                when (screen) {
                                    Screen.Chat -> stringResource(R.string.nav_chat)
                                    Screen.ScheduledTasks -> stringResource(R.string.nav_schedule)
                                    Screen.LocationTasks -> stringResource(R.string.nav_places)
                                    Screen.Library -> stringResource(R.string.nav_library)
                                    else -> ""
                                }
                            )
                        },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            if (currentDestination?.route == Screen.Settings.route) {
                                navController.popBackStack()
                            }
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
        ) {
            composable(Screen.Chat.route) {
                ChatScreen(
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
            composable(Screen.ScheduledTasks.route) {
                ScheduledTasksScreen()
            }
            composable(Screen.LocationTasks.route) {
                LocationTasksScreen()
            }
            composable(Screen.Library.route) {
                LibraryScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                onNavigateToApiUsage = { navController.navigate(Screen.ApiUsage.route) },
                onNavigateToModelCenter = { navController.navigate(Screen.ModelCenter.route) }
                )
            }
            composable(Screen.ApiUsage.route) {
                ApiUsageScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

        composable(Screen.ModelCenter.route) {
            com.personal.personalai.presentation.modelcenter.ModelCenterScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        }
    }
}
