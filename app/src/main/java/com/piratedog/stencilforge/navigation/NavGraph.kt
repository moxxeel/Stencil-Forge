package com.piratedog.stencilforge.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.piratedog.stencilforge.ui.editor.EditorScreen
import com.piratedog.stencilforge.ui.home.HomeScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Editor : Screen("editor?stencilId={stencilId}") {
        fun newRoute() = "editor"
        fun editRoute(id: Long) = "editor?stencilId=$id"
    }
}

@Composable
fun StencilNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Home.route) {

        composable(Screen.Home.route) {
            HomeScreen(
                onNewStencil = { navController.navigate(Screen.Editor.newRoute()) },
                onOpenStencil = { id -> navController.navigate(Screen.Editor.editRoute(id)) }
            )
        }

        composable(
            route = Screen.Editor.route,
            arguments = listOf(
                navArgument("stencilId") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { backStack ->
            val stencilId = backStack.arguments?.getLong("stencilId")?.takeIf { it != -1L }
            EditorScreen(
                onBack = { navController.popBackStack() },
                stencilId = stencilId
            )
        }
    }
}
