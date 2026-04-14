package com.example.osislogin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun OrdersHistoryScreen(
    tableId: Int,
    erreserbaId: Int,
    viewModel: OrdersHistoryViewModel,
    onLogout: () -> Unit,
    onChat: () -> Unit,
    onReservations: () -> Unit,
    chatUnreadCount: Int,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var goBackAfterClose by remember { mutableStateOf(false) }

    LaunchedEffect(tableId, erreserbaId) {
        viewModel.load(tableId = tableId, erreserbaId = erreserbaId)
    }

    AppChrome(
        onLogout = onLogout,
        onLogoClick = onBack,
        showMiddleAction = true,
        middleIconContentDescription = "Erreserbak",
        onMiddleAction = onReservations,
        rightIconResId = com.example.osislogin.R.drawable.chat,
        rightIconContentDescription = "Txata",
        onRightAction = onChat,
        rightBadgeCount = chatUnreadCount
    ) { contentModifier ->
        Box(modifier = contentModifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val tableLabel = uiState.tableLabel?.takeIf { it.isNotBlank() } ?: tableId.toString()
                val guestsText = uiState.guestCount?.toString() ?: "—"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.TableRestaurant, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = tableLabel, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "·", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(imageVector = Icons.Filled.People, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = guestsText, style = MaterialTheme.typography.titleMedium)
                }

                uiState.totalWithVat?.let { total ->
                    Text(
                        text = "Totala: ${"%.2f".format(total)}€",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(
                        enabled = !uiState.isLoading && uiState.totalWithVat != null,
                        onClick = {
                            goBackAfterClose = true
                            viewModel.closeErreserbaAndPay()
                        }
                    ) {
                        Text(text = "Faktura itxi")
                    }
                }

                if (uiState.orders.isEmpty() && !uiState.isLoading && uiState.error.isNullOrBlank()) {
                    Text(
                        text = "Ez dago eskariarik",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(uiState.orders, key = { it.id }) { order ->
                            val isLocked = viewModel.isLocked(order.egoera)
                            val statusColor = statusColor(order.egoera)
                            Surface(
                                color = statusColor.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(text = "Eskaria #${order.id}", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = "Egoera: ${order.egoera}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = "${"%.2f".format(order.total)}€",
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            if (!isLocked) {
                                                TextButton(onClick = { viewModel.cancelEskaria(order.id) }) {
                                                    Text(text = "Ezeztatu", color = MaterialTheme.colorScheme.error)
                                                }
                                            } else {
                                                Text(
                                                    text = "Blokeatuta",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }

                                    if (order.products.isEmpty()) {
                                        Text(
                                            text = "Ez dago produkturik",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            order.products.forEach { p ->
                                                Text(text = "x${p.qty} · ${p.name}", style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            if (!uiState.error.isNullOrBlank() && !uiState.isLoading) {
                Text(
                    text = uiState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                )
            }

            LaunchedEffect(goBackAfterClose, uiState.isLoading, uiState.error) {
                if (goBackAfterClose && !uiState.isLoading && uiState.error.isNullOrBlank()) {
                    onBack()
                }
            }
        }
    }
}

@Composable
private fun statusColor(egoera: String?): Color {
    val e = egoera?.trim().orEmpty().lowercase()
    val orange = Color(0xFFF3863A)
    return when {
        e == "prest" -> Color(0xFF2E7D32)
        e == "zerbitzatua" -> Color(0xFF546E7A)
        e.isBlank() -> orange
        e.contains("bidali") || e.contains("sortu") -> orange
        else -> orange
    }
}
