package com.newsradar.app.ui

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.newsradar.app.ui.theme.NewsRadarTheme

class MainActivity : ComponentActivity() {

    private lateinit var vm: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: MainViewModel = viewModel()
            vm = viewModel
            val theme by vm.themeMode.collectAsState()
            val scheme by vm.colorScheme.collectAsState()

            // Request notification permission once, lifecycle-safe.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val context = LocalContext.current
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {}
                LaunchedEffect(Unit) {
                    val granted = ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            NewsRadarTheme(themeMode = theme, colorScheme = scheme) {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "feed") {
                    composable("feed") {
                        FeedScreen(vm = vm, onOpenSettings = { nav.navigate("settings") })
                    }
                    composable("settings") {
                        SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    // Show the welcome/greeting popup whenever the app is opened (incl. returning
    // to the foreground), and re-pull the feed only if it's stale (>15 min) so we
    // don't hammer RSS endpoints on every resume (e.g. closing the notification
    // shade). Timestamps/age-colours stay current without abuse.
    private var firstResume = true
    override fun onResume() {
        super.onResume()
        if (::vm.isInitialized) {
            if (firstResume) firstResume = false else vm.showGreeting()
            vm.refreshIfStale()
        }
    }
}
