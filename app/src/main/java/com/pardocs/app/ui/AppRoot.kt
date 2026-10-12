package com.pardocs.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pardocs.app.tools.ToolCatalog
import com.pardocs.app.ui.screens.HomeScreen
import com.pardocs.app.ui.screens.MetadataScreen
import com.pardocs.app.ui.screens.OrganizeScreen
import com.pardocs.app.ui.screens.RecentScreen
import com.pardocs.app.ui.screens.SettingsScreen
import com.pardocs.app.ui.screens.ToolScreen

object Routes {
    const val HOME = "home"
    const val RECENT = "recent"
    const val SETTINGS = "settings"
    const val TOOL = "tool/{toolId}"
    fun tool(id: String) = "tool/$id"
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun ParDocsAppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val items = listOf(
        NavItem(Routes.HOME, "Inicio", Icons.Outlined.Home),
        NavItem(Routes.RECENT, "Recientes", Icons.Outlined.History),
        NavItem(Routes.SETTINGS, "Ajustes", Icons.Outlined.Settings),
    )
    val showBottomBar = items.any { it.route == currentRoute }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(item.icon, item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) { HomeScreen(navController) }
            composable(Routes.RECENT) { RecentScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(
                Routes.TOOL,
                arguments = listOf(navArgument("toolId") { type = NavType.StringType }),
            ) { entry ->
                val toolId = entry.arguments?.getString("toolId").orEmpty()
                val spec = ToolCatalog.byId(toolId)
                when {
                    spec == null -> Text("Herramienta no encontrada")
                    spec.customScreen == "organizer" -> OrganizeScreen(spec) { navController.popBackStack() }
                    spec.customScreen == "metadata" -> MetadataScreen(spec) { navController.popBackStack() }
                    else -> ToolScreen(spec) { navController.popBackStack() }
                }
            }
        }
    }
}