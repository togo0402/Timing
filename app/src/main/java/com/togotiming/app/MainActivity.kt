package com.togotiming.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.togotiming.app.data.ThemeMode
import com.togotiming.app.ui.TimingRoot
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val routeRequest = MutableStateFlow<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer
        askNotificationPermission()
        container.nfcController.attach(this)
        routeRequest.value = intent?.getStringExtra(EXTRA_ROUTE)
        container.nfcController.handleIntent(intent)

        setContent {
            val mode by appContainer.themePreferences.mode.collectAsState(initial = ThemeMode.SYSTEM)
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
            }
            com.togotiming.app.ui.theme.TimingTheme(darkTheme = dark) {
                TimingRoot(
                    container = container,
                    routeRequest = routeRequest,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appContainer.nfcController.enableReaderMode()
    }

    override fun onPause() {
        appContainer.nfcController.disableReaderMode()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {        super.onNewIntent(intent)
        setIntent(intent)
        routeRequest.value = intent.getStringExtra(EXTRA_ROUTE)
        appContainer.nfcController.handleIntent(intent)
    }

    override fun onDestroy() {
        appContainer.nfcController.setBindingSink(null)
        super.onDestroy()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
