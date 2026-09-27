package ru.dragonfly.zapret.ui

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.dragonfly.zapret.ui.screens.ListEditorScreen
import ru.dragonfly.zapret.ui.screens.ListsScreen
import ru.dragonfly.zapret.ui.screens.LogScreen
import ru.dragonfly.zapret.ui.screens.HomeScreen
import ru.dragonfly.zapret.ui.screens.SettingsScreen
import ru.dragonfly.zapret.ui.screens.StrategiesScreen
import ru.dragonfly.zapret.ui.screens.TestScreen

private const val HOME_ROUTE = "home"

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(HOME_ROUTE, "Главная", Icons.Filled.Shield),
    TabItem("strategies", "Стратегии", Icons.Filled.Bolt),
    TabItem("test", "Тест", Icons.Filled.Speed),
    TabItem("lists", "Списки", Icons.Filled.ListAlt),
    TabItem("settings", "Настройки", Icons.Filled.Settings)
)

@Composable
fun ZapretApp(
    viewModel: MainViewModel,
    onRequestVpnPermission: (Intent) -> Unit
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route

    // единая точка перехода между вкладками: без saveState/restoreState,
    // иначе обычный navigate() с главной ломал возврат на неё
    val openTab: (String) -> Unit = { route ->
        if (currentRoute != route) {
            if (route == HOME_ROUTE) {
                // главная всегда достижима простым возвратом по стеку
                if (!navController.popBackStack(HOME_ROUTE, false)) {
                    navController.navigate(HOME_ROUTE) { launchSingleTop = true }
                }
            } else {
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        inclusive = false
                        saveState = false
                    }
                    launchSingleTop = true
                    restoreState = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.RequestVpnPermission -> onRequestVpnPermission(event.intent)
                is UiEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { openTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HOME_ROUTE,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(HOME_ROUTE) {
                HomeScreen(
                    viewModel = viewModel,
                    onOpenStrategies = { openTab("strategies") },
                    onOpenLog = { navController.navigate("log") },
                    onOpenTest = { openTab("test") }
                )
            }
            composable("strategies") { StrategiesScreen(viewModel) }
            composable("test") { TestScreen(viewModel) }
            composable("lists") {
                ListsScreen(
                    viewModel = viewModel,
                    onOpenFile = { name -> navController.navigate("list/$name") }
                )
            }
            composable("list/{name}") { entry ->
                ListEditorScreen(
                    viewModel = viewModel,
                    fileName = entry.arguments?.getString("name").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
            composable("settings") { SettingsScreen(viewModel) }
            composable("log") { LogScreen(viewModel, onBack = { navController.popBackStack() }) }
        }
    }
}
