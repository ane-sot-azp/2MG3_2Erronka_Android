package com.example.osislogin

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.osislogin.data.AppDatabase
import com.example.osislogin.ui.CategoriesScreen
import com.example.osislogin.ui.CategoriesViewModel
import com.example.osislogin.ui.ChatScreen
import com.example.osislogin.ui.ChatViewModel
import com.example.osislogin.ui.HomeScreen
import com.example.osislogin.ui.HomeViewModel
import com.example.osislogin.ui.LoginScreen
import com.example.osislogin.ui.LoginViewModel
import com.example.osislogin.ui.OrdersHistoryScreen
import com.example.osislogin.ui.OrdersHistoryViewModel
import com.example.osislogin.ui.PlaterakScreen
import com.example.osislogin.ui.PlaterakViewModel
import com.example.osislogin.ui.ReservationsScreen
import com.example.osislogin.ui.ReservationsViewModel
import com.example.osislogin.util.SessionManager
import kotlinx.coroutines.launch

sealed class Route(val route: String) {
    object Login : Route("login")
    object Home : Route("home")
    object Reservations : Route("reservations")
    object Categories : Route("categories/{tableId}/{erreserbaId}") {
        fun create(tableId: Int, erreserbaId: Int = 0) = "categories/$tableId/$erreserbaId"
    }
    object Platerak : Route("platerak/{tableId}/{erreserbaId}/{kategoriKey}") {
        fun create(tableId: Int, erreserbaId: Int, kategoriKey: String) =
                "platerak/$tableId/$erreserbaId/$kategoriKey"
    }
    object OrdersHistory : Route("ordersHistory/{tableId}/{erreserbaId}") {
        fun create(tableId: Int, erreserbaId: Int) = "ordersHistory/$tableId/$erreserbaId"
    }
    object Chat : Route("chat")
}

@Composable
fun AppNavigation(database: AppDatabase, sessionManager: SessionManager, startDestination: String) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appContext = context.applicationContext

    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(initialUserName = "Anonimoa"))
    val chatUiState by chatViewModel.uiState.collectAsState()
    val userName by sessionManager.userName.collectAsState(initial = null)

    LaunchedEffect(userName) {
        val name = userName?.trim().orEmpty()
        if (name.isNotBlank()) {
            chatViewModel.updateUserName(name)
            chatViewModel.connect()
        }
    }

    val logoutAndGoToLogin: () -> Unit = {
        scope.launch { sessionManager.clearSession() }
        chatViewModel.reset()
        navController.navigate(Route.Login.route) { popUpTo(Route.Home.route) { inclusive = true } }
    }
    val goToReservations: () -> Unit = {
        navController.navigate(Route.Reservations.route) { launchSingleTop = true }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Route.Login.route) {
            val viewModel = remember { LoginViewModel(database, sessionManager) }
            LoginScreen(
                    viewModel = viewModel,
                    onLoginSuccess = {
                        navController.navigate(Route.Home.route) {
                            popUpTo(Route.Login.route) { inclusive = true }
                        }
                    }
            )
        }

        composable(Route.Home.route) {
            val viewModel = remember { HomeViewModel(sessionManager) }
            HomeScreen(
                    viewModel = viewModel,
                    onLogout = logoutAndGoToLogin,
                    onChat = { navController.navigate(Route.Chat.route) },
                    onReservations = goToReservations,
                    chatUnreadCount = chatUiState.unreadCount,
                    onTableClick = { tableId, erreserbaId ->
                        navController.navigate(Route.Categories.create(tableId, erreserbaId ?: 0))
                    }
            )
        }

        composable(Route.Reservations.route) {
            val viewModel = remember { ReservationsViewModel(sessionManager) }
            ReservationsScreen(
                viewModel = viewModel,
                onLogout = logoutAndGoToLogin,
                onChat = { navController.navigate(Route.Chat.route) },
                onHome = { navController.navigate(Route.Home.route) { launchSingleTop = true } },
                onReservations = goToReservations,
                chatUnreadCount = chatUiState.unreadCount
            )
        }

        composable(
                route = Route.Categories.route,
                arguments =
                    listOf(
                        navArgument("tableId") { type = NavType.IntType },
                        navArgument("erreserbaId") { type = NavType.IntType }
                    )
        ) { backStackEntry ->
            val tableId = backStackEntry.arguments?.getInt("tableId") ?: 0
            val erreserbaId = backStackEntry.arguments?.getInt("erreserbaId") ?: 0
            val viewModel = remember { CategoriesViewModel(sessionManager) }
            CategoriesScreen(
                    tableId = tableId,
                    initialErreserbaId = erreserbaId,
                    viewModel = viewModel,
                    onLogout = logoutAndGoToLogin,
                    onChat = { navController.navigate(Route.Chat.route) },
                    onReservations = goToReservations,
                    chatUnreadCount = chatUiState.unreadCount,
                    onBack = { navController.popBackStack() },
                    onTicketClick = { tId, eId ->
                        navController.navigate(Route.OrdersHistory.create(tId, eId))
                    },
                    onCategorySelected = { tId, erreserbaId, kategoriKey ->
                        navController.navigate(Route.Platerak.create(tId, erreserbaId, kategoriKey))
                    }
            )
        }

        composable(
            route = Route.OrdersHistory.route,
            arguments =
                listOf(
                    navArgument("tableId") { type = NavType.IntType },
                    navArgument("erreserbaId") { type = NavType.IntType }
                )
        ) { backStackEntry ->
            val tableId = backStackEntry.arguments?.getInt("tableId") ?: 0
            val erreserbaId = backStackEntry.arguments?.getInt("erreserbaId") ?: 0
            val viewModel = remember { OrdersHistoryViewModel(sessionManager) }
            OrdersHistoryScreen(
                tableId = tableId,
                erreserbaId = erreserbaId,
                viewModel = viewModel,
                onLogout = logoutAndGoToLogin,
                onChat = { navController.navigate(Route.Chat.route) },
                onReservations = goToReservations,
                chatUnreadCount = chatUiState.unreadCount,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
                route = Route.Platerak.route,
                arguments =
                        listOf(
                                navArgument("tableId") { type = NavType.IntType },
                                navArgument("erreserbaId") { type = NavType.IntType },
                                navArgument("kategoriKey") { type = NavType.StringType }
                        )
        ) { backStackEntry ->
            val tableId = backStackEntry.arguments?.getInt("tableId") ?: 0
            val erreserbaId = backStackEntry.arguments?.getInt("erreserbaId") ?: 0
            val kategoriKey = backStackEntry.arguments?.getString("kategoriKey").orEmpty()
            val viewModel = remember { PlaterakViewModel() }
            PlaterakScreen(
                    tableId = tableId,
                    erreserbaId = erreserbaId,
                    kategoriKey = kategoriKey,
                    viewModel = viewModel,
                    onLogout = logoutAndGoToLogin,
                    onChat = { navController.navigate(Route.Chat.route) },
                    onReservations = goToReservations,
                    chatUnreadCount = chatUiState.unreadCount,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(Route.Categories.create(tableId, erreserbaId)) {
                                launchSingleTop = true
                            }
                        }
                    }
            )
        }

        composable(Route.Chat.route) {
            ChatScreen(
                    viewModel = chatViewModel,
                    onLogout = logoutAndGoToLogin,
                    onBack = { navController.popBackStack() },
                    onReservations = goToReservations
            )
        }
    }
}
