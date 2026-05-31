package com.verifyhub.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun VerifyHubApp() {
    VerifyHubTheme {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = "history") {
            composable("history") {
                HistoryScreen(onOpenSettings = { nav.navigate("settings") })
            }
            composable("settings") {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
