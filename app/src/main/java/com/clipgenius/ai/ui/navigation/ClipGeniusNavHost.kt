package com.clipgenius.ai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.clipgenius.ai.ClipGeniusApplication
import com.clipgenius.ai.ui.home.HomeScreen
import com.clipgenius.ai.ui.home.HomeViewModel
import com.clipgenius.ai.ui.home.HomeViewModelFactory
import com.clipgenius.ai.ui.project.ProjectScreen
import com.clipgenius.ai.ui.project.ProjectViewModel
import com.clipgenius.ai.ui.project.ProjectViewModelFactory
import com.clipgenius.ai.ui.settings.SettingsScreen
import com.clipgenius.ai.ui.settings.SettingsViewModel
import com.clipgenius.ai.ui.settings.SettingsViewModelFactory

object Destinations {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PROJECT = "project/{projectId}"

    fun projectRoute(projectId: String) = "project/$projectId"
}

@Composable
fun ClipGeniusNavHost(navController: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as ClipGeniusApplication

    NavHost(
        navController = navController,
        startDestination = Destinations.HOME
    ) {
        composable(Destinations.HOME) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = HomeViewModelFactory(app.projectRepository)
            )
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToProject = { projectId ->
                    navController.navigate(Destinations.projectRoute(projectId))
                },
                onNavigateToSettings = {
                    navController.navigate(Destinations.SETTINGS)
                }
            )
        }

        composable(
            route = Destinations.PROJECT,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            val projectViewModel: ProjectViewModel = viewModel(
                factory = ProjectViewModelFactory(projectId, app.projectRepository, context.applicationContext)
            )
            ProjectScreen(
                viewModel = projectViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSettings = { navController.navigate(Destinations.SETTINGS) }
            )
        }

        composable(Destinations.SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(app.securePreferences)
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
