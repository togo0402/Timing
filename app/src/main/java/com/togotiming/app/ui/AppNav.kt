package com.togotiming.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.togotiming.app.AppContainer
import com.togotiming.app.domain.TimeMath
import com.togotiming.app.nfc.NfcEvent
import com.togotiming.app.ui.components.AppBackground
import com.togotiming.app.ui.screens.AboutScreen
import com.togotiming.app.ui.screens.HomeScreen
import com.togotiming.app.ui.screens.ImportExportScreen
import com.togotiming.app.ui.screens.NfcScreen
import com.togotiming.app.ui.screens.PermissionsScreen
import com.togotiming.app.ui.screens.RecordsImportExportScreen
import com.togotiming.app.ui.screens.SettingsScreen
import com.togotiming.app.ui.screens.StatsScreen
import com.togotiming.app.ui.screens.TypesScreen
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val TIMER = "timer"
    const val STATS = "stats"
    const val TYPES = "types"
    const val NFC = "nfc"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val ABOUT = "about"
    const val IMPORT_EXPORT = "import_export"
    const val RECORD_IO = "record_io"
}

private data class BottomDest(val route: String, val label: String, val icon: ImageVector)

private val bottomDests = listOf(
    BottomDest(Routes.TIMER, "计时", Icons.Filled.Timer),
    BottomDest(Routes.STATS, "统计", Icons.Filled.BarChart),
    BottomDest(Routes.TYPES, "分类", Icons.Filled.Category),
    BottomDest(Routes.NFC, "NFC", Icons.Filled.Nfc),
    BottomDest(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

@Composable
fun TimingRoot(
    container: AppContainer,
    routeRequest: MutableStateFlow<String?>,
) {
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        routeRequest.collect { route ->
            if (!route.isNullOrBlank()) {
                navController.navigate(route) { launchSingleTop = true }
                routeRequest.value = null
            }
        }
    }

    var switchPrompt by remember { mutableStateOf<NfcEvent.AskSwitch?>(null) }
    val pendingSwitch by container.nfcController.pendingSwitch.collectAsState()
    LaunchedEffect(pendingSwitch) {
        if (pendingSwitch != null) switchPrompt = pendingSwitch
    }

    switchPrompt?.let { prompt ->
        val activeType = vm.types.value.firstOrNull { it.id == prompt.activeRecord.typeId }
        val targetType = vm.types.value.firstOrNull { it.id == prompt.targetTypeId }
        val elapsed = System.currentTimeMillis() - prompt.activeRecord.startAt
        AlertDialog(
            onDismissRequest = {
                container.nfcController.cancelSwitch()
                switchPrompt = null
            },
            title = { Text("切换计时？") },
            text = {
                Text(
                    "当前正在 ${activeType?.name ?: "活动"} ${TimeMath.formatDuration(elapsed)}，" +
                        "是否切换到 ${targetType?.name ?: "新活动"}？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    container.nfcController.confirmSwitch(prompt.targetTypeId)
                    switchPrompt = null
                }) { Text("切换") }
            },
            dismissButton = {
                TextButton(onClick = {
                    container.nfcController.cancelSwitch()
                    switchPrompt = null
                }) { Text("保持当前") }
            },
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                val dark = com.togotiming.app.ui.theme.TimingColors.isDark(MaterialTheme.colorScheme.background)
                val navShape = RoundedCornerShape(26.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    NavigationBar(
                        modifier = Modifier
                            .clip(navShape)
                            .background(if (dark) Color(0xE6121A29) else Color(0xF2FFFFFF))
                            .border(1.dp, if (dark) Color(0x1FFFFFFF) else Color(0xE6FFFFFF), navShape),
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp,
                    ) {
                        bottomDests.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                alwaysShowLabel = false,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            inclusive = false
                                        }
                                        launchSingleTop = true
                                    }
                                },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.TIMER,
                modifier = Modifier.padding(padding),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(Routes.TIMER) { HomeScreen(vm, navController) }
                composable(Routes.STATS) { StatsScreen(vm) }
                composable(Routes.TYPES) { TypesScreen(vm) }
                composable(Routes.NFC) { NfcScreen(vm, container.nfcController) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(vm, navController)
                }
                composable(Routes.PERMISSIONS) { PermissionsScreen(vm) }
                composable(Routes.ABOUT) { AboutScreen() }
                composable(Routes.IMPORT_EXPORT) { ImportExportScreen(vm) }
                composable(Routes.RECORD_IO) { RecordsImportExportScreen(vm) }
            }
        }
    }
}

@Composable
fun navigateSingle(navController: NavHostController, route: String) {
    navController.navigate(route) { launchSingleTop = true }
}
