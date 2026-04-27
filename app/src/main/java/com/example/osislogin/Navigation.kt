package com.example.osislogin

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

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
    var orderDraft by remember { mutableStateOf<OrderDraft?>(null) }
    var showConfirmDraftDialog by remember { mutableStateOf(false) }
    var draftSubmitting by remember { mutableStateOf(false) }
    var draftError by remember { mutableStateOf<String?>(null) }

    val chatViewModel: ChatViewModel =
        viewModel(
            factory =
                ChatViewModel.factory(
                    initialUserName = "Anonimoa",
                    appContext = appContext
                )
        )
    val chatUiState by chatViewModel.uiState.collectAsState()
    val userName by sessionManager.userName.collectAsState(initial = null)
    val userId by sessionManager.userId.collectAsState(initial = null)
    val chatEnabled by sessionManager.chatEnabled.collectAsState(initial = false)

    LaunchedEffect(userName, chatEnabled) {
        if (!chatEnabled) {
            chatViewModel.reset()
            return@LaunchedEffect
        }
        val name = userName?.trim().orEmpty()
        if (name.isNotBlank()) {
            chatViewModel.updateUserName(name)
            chatViewModel.connect()
        }
    }

    LaunchedEffect(userId) {
        val id = userId ?: return@LaunchedEffect
        val enabled =
            withContext(Dispatchers.IO) {
                fetchChatPermission(userId = id)
            }
        sessionManager.setChatEnabled(enabled)
    }

    val logoutAndGoToLogin: () -> Unit = {
        scope.launch { sessionManager.clearSession() }
        chatViewModel.reset()
        navController.navigate(Route.Login.route) { popUpTo(Route.Home.route) { inclusive = true } }
    }
    val goToReservations: () -> Unit = {
        navController.navigate(Route.Reservations.route) { launchSingleTop = true }
    }

    val goToHome: () -> Unit = {
        navController.navigate(Route.Home.route) {
            popUpTo(Route.Home.route) { inclusive = true }
            launchSingleTop = true
        }
    }

    val goToOrdersHistory: (tableId: Int, erreserbaId: Int) -> Unit = { tableId, erreserbaId ->
        navController.navigate(Route.OrdersHistory.create(tableId, erreserbaId)) {
            popUpTo(Route.Home.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    val openDraftConfirmDialog: () -> Unit = {
        val draft = orderDraft
        if (draft != null && draft.items.isNotEmpty()) {
            draftError = null
            showConfirmDraftDialog = true
        }
    }

    if (showConfirmDraftDialog) {
        AlertDialog(
            onDismissRequest = { if (!draftSubmitting) showConfirmDraftDialog = false },
            title = { Text(text = "Eskaera bidali?") },
            text = {
                val draft = orderDraft
                val count = draft?.items?.values?.sumOf { it.qty } ?: 0
                val total = draft?.items?.values?.sumOf { it.qty * it.unitPrice } ?: 0.0
                val msg =
                    if (count <= 0) {
                        "Ez dago produkturik aukeratuta."
                    } else {
                        "Aukeratutako produktuak: $count\nGuztira: ${"%.2f".format(total)}€\n\nEskaera bidali nahi duzu?"
                    }
                Column {
                    Text(text = msg)
                    if (!draftError.isNullOrBlank()) {
                        Text(text = draftError.orEmpty())
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !draftSubmitting,
                    onClick = {
                        val draft = orderDraft ?: return@Button
                        if (draft.items.isEmpty()) {
                            showConfirmDraftDialog = false
                            if (draft.targetEskariaId != null) {
                                goToOrdersHistory(draft.tableId, draft.erreserbaId)
                            } else {
                                goToHome()
                            }
                            return@Button
                        }
                        draftSubmitting = true
                        draftError = null
                        scope.launch {
                            val result =
                                withContext(Dispatchers.IO) {
                                    submitDraft(draft)
                                }
                            if (result.ok) {
                                val tableId = draft.tableId
                                val erreserbaId = draft.erreserbaId
                                orderDraft = null
                                showConfirmDraftDialog = false
                                draftSubmitting = false
                                goToOrdersHistory(tableId, erreserbaId)
                            } else {
                                draftSubmitting = false
                                draftError = result.errorMessage ?: "Errorea"
                            }
                        }
                    }
                ) { Text(text = if (draftSubmitting) "Bidaltzen..." else "Bai, bidali") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !draftSubmitting,
                        onClick = {
                            orderDraft = null
                            showConfirmDraftDialog = false
                            draftSubmitting = false
                            draftError = null
                        }
                    ) { Text(text = "Ez, baztertu") }
                    TextButton(
                        enabled = !draftSubmitting,
                        onClick = { showConfirmDraftDialog = false }
                    ) { Text(text = "Utzi") }
                }
            }
        )
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
                    chatEnabled = chatEnabled,
                    onChat = { navController.navigate(Route.Chat.route) },
                    onReservations = goToReservations,
                    chatUnreadCount = if (chatEnabled) chatUiState.unreadCount else 0,
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
                chatEnabled = chatEnabled,
                onChat = { navController.navigate(Route.Chat.route) },
                onHome = { navController.navigate(Route.Home.route) { launchSingleTop = true } },
                onReservations = goToReservations,
                chatUnreadCount = if (chatEnabled) chatUiState.unreadCount else 0
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

            LaunchedEffect(tableId, erreserbaId) {
                val current = orderDraft
                if (current == null || current.tableId != tableId || current.erreserbaId != erreserbaId) {
                    orderDraft = OrderDraft(tableId = tableId, erreserbaId = erreserbaId)
                }
            }

            CategoriesScreen(
                    tableId = tableId,
                    initialErreserbaId = erreserbaId,
                    viewModel = viewModel,
                    onLogout = logoutAndGoToLogin,
                    chatEnabled = chatEnabled,
                    onChat = { navController.navigate(Route.Chat.route) },
                    onReservations = goToReservations,
                    chatUnreadCount = if (chatEnabled) chatUiState.unreadCount else 0,
                    hasDraft = (orderDraft?.items?.isNotEmpty() == true),
                    onDraftTick = openDraftConfirmDialog,
                    onGoToTables = goToHome,
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
                chatEnabled = chatEnabled,
                onChat = { navController.navigate(Route.Chat.route) },
                onReservations = goToReservations,
                chatUnreadCount = if (chatEnabled) chatUiState.unreadCount else 0,
                onBack = { navController.popBackStack() },
                onAddProducts = { order ->
                    if (viewModel.isLocked(order.egoera)) return@OrdersHistoryScreen
                    val base =
                        order.products.associate { p ->
                            p.produktuaId to DraftItem(qty = p.qty, unitPrice = p.unitPrice)
                        }
                    orderDraft =
                        OrderDraft(
                            tableId = tableId,
                            erreserbaId = erreserbaId,
                            targetEskariaId = order.id,
                            targetEgoera = order.egoera,
                            baseByProductId = base
                        )
                    navController.navigate(Route.Categories.create(tableId, erreserbaId)) {
                        launchSingleTop = true
                    }
                }
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

            LaunchedEffect(tableId, erreserbaId) {
                val current = orderDraft
                if (current == null || current.tableId != tableId || current.erreserbaId != erreserbaId) {
                    orderDraft = OrderDraft(tableId = tableId, erreserbaId = erreserbaId)
                }
            }

            PlaterakScreen(
                    tableId = tableId,
                    erreserbaId = erreserbaId,
                    kategoriKey = kategoriKey,
                    viewModel = viewModel,
                    onLogout = logoutAndGoToLogin,
                    chatEnabled = chatEnabled,
                    onChat = { navController.navigate(Route.Chat.route) },
                    onReservations = goToReservations,
                    chatUnreadCount = if (chatEnabled) chatUiState.unreadCount else 0,
                    draftQtyByProduktuaId = orderDraft?.items?.mapValues { it.value.qty } ?: emptyMap(),
                    baseQtyByProduktuaId = orderDraft?.baseByProductId?.mapValues { it.value.qty } ?: emptyMap(),
                    onAddDraftItem = { produktuaId, unitPrice, delta ->
                        val currentDraft = orderDraft ?: return@PlaterakScreen
                        val nextItems = currentDraft.items.toMutableMap()
                        val current = nextItems[produktuaId]?.qty ?: 0
                        val next = (current + delta).coerceAtLeast(0)
                        if (next == 0) {
                            nextItems.remove(produktuaId)
                        } else {
                            nextItems[produktuaId] = DraftItem(qty = next, unitPrice = unitPrice)
                        }
                        orderDraft = currentDraft.copy(items = nextItems)
                    },
                    onOpenDraftConfirm = openDraftConfirmDialog,
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

private data class DraftItem(val qty: Int, val unitPrice: Double)

private data class OrderDraft(
    val tableId: Int,
    val erreserbaId: Int,
    val targetEskariaId: Int? = null,
    val targetEgoera: String? = null,
    val baseByProductId: Map<Int, DraftItem> = emptyMap(),
    val items: Map<Int, DraftItem> = emptyMap()
)

private data class SubmitDraftResult(val ok: Boolean, val errorMessage: String? = null)

private fun apiBaseUrlCandidates(): List<String> {
    val basePrimary = "http://192.168.10.5:5000/api"
    val base = basePrimary.trimEnd('/')
    val noApi =
        if (base.endsWith("/api")) {
            base.removeSuffix("/api").trimEnd('/')
        } else {
            base
        }
    return listOf(
        base,
        "$noApi/api",
        "http://192.168.10.5:5000/api",
        "http://192.168.10.5:5000/api"
    ).distinct()
}

private fun httpGet(url: String): Pair<Int, String> {
    val conn =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15000
            readTimeout = 15000
        }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    return code to body
}

private fun httpPostJson(url: String, jsonBody: JSONObject): Pair<Int, String> {
    val conn =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true
            connectTimeout = 20000
            readTimeout = 20000
        }
    conn.outputStream.use { os ->
        os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    return code to body
}

private fun httpPutJson(url: String, jsonBody: JSONObject): Pair<Int, String> {
    val conn =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true
            connectTimeout = 20000
            readTimeout = 20000
        }
    conn.outputStream.use { os ->
        os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    return code to body
}

private fun submitDraft(draft: OrderDraft): SubmitDraftResult {
    if (draft.items.isEmpty()) return SubmitDraftResult(ok = true)

    var lastError: String? = null

    val mergedByProduktuaId = linkedMapOf<Int, DraftItem>()
    draft.baseByProductId.forEach { (id, item) ->
        mergedByProduktuaId[id] = item
    }
    draft.items.forEach { (id, add) ->
        val base = mergedByProduktuaId[id]
        if (base == null) {
            mergedByProduktuaId[id] = add
        } else {
            mergedByProduktuaId[id] = DraftItem(qty = base.qty + add.qty, unitPrice = base.unitPrice)
        }
    }

    val produktuak = JSONArray()
    var total = 0.0
    for ((produktuaId, item) in mergedByProduktuaId) {
        if (item.qty <= 0) continue
        total += item.qty * item.unitPrice
        produktuak.put(
            JSONObject()
                .put("ProduktuaId", produktuaId)
                .put("Kantitatea", item.qty)
                .put("Prezioa", item.unitPrice)
        )
    }
    if (produktuak.length() == 0) return SubmitDraftResult(ok = true)

    val egoera = draft.targetEgoera?.takeIf { it.isNotBlank() } ?: "Bidalita"
    val payload =
        JSONObject()
            .put("ErreserbaId", draft.erreserbaId)
            .put("Prezioa", total)
            .put("Egoera", egoera)
            .put("Produktuak", produktuak)

    for (baseUrl in apiBaseUrlCandidates()) {
        try {
            val url =
                if (draft.targetEskariaId != null) {
                    "${baseUrl.trimEnd('/')}/Eskariak/${draft.targetEskariaId}"
                } else {
                    "${baseUrl.trimEnd('/')}/Eskariak"
                }
            val (code, body) =
                if (draft.targetEskariaId != null) {
                    httpPutJson(url, payload)
                } else {
                    httpPostJson(url, payload)
                }
            if (code in 200..299) return SubmitDraftResult(ok = true)
            lastError = "HTTP $code: ${body.take(300)}"
        } catch (e: Exception) {
            lastError = e.message ?: "Errorea"
        }
    }

    return SubmitDraftResult(ok = false, errorMessage = lastError)
}
private fun fetchChatPermission(userId: Int): Boolean {
    for (baseUrl in apiBaseUrlCandidates()) {
        val url = "${baseUrl.trimEnd('/')}/Langileak/$userId"
        try {
            val (code, body) = httpGet(url)
            if (code !in 200..299) continue
            val obj = (JSONTokener(body).nextValue() as? JSONObject) ?: continue
            return obj.optBoolean("txat_sarbidea", obj.optBoolean("Txat_sarbidea", false))
        } catch (_: Exception) {}
    }
    return false
}
