package com.example.osislogin.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.max

@Composable
fun PlaterakScreen(
    tableId: Int,
    erreserbaId: Int,
    kategoriKey: String,
    viewModel: PlaterakViewModel,
    onLogout: () -> Unit,
    chatEnabled: Boolean,
    onChat: () -> Unit,
    onReservations: () -> Unit,
    chatUnreadCount: Int,
    draftQtyByProduktuaId: Map<Int, Int>,
    baseQtyByProduktuaId: Map<Int, Int>,
    onAddDraftItem: (produktuaId: Int, unitPrice: Double, delta: Int) -> Unit,
    onOpenDraftConfirm: () -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp >= configuration.screenHeightDp

    LaunchedEffect(tableId, erreserbaId, kategoriKey) {
        viewModel.load(tableId = tableId, erreserbaId = erreserbaId, kategoriKey = kategoriKey)
    }

    AppChrome(
        onLogout = onLogout,
        onLogoClick = onBack,
        navigationIcon = Icons.Filled.Apps,
        navigationIconContentDescription = "Kategoriak",
        showMiddleAction = true,
        middleIconContentDescription = "Erreserbak",
        onMiddleAction = onReservations,
        showRightAction = chatEnabled,
        rightIconResId = com.example.osislogin.R.drawable.chat,
        rightIconContentDescription = "Txata",
        onRightAction = onChat,
        rightBadgeCount = chatUnreadCount
    ) { contentModifier ->
        Box(modifier = contentModifier.fillMaxSize()) {
            val cellMinSize = if (isLandscape) 240.dp else 200.dp
            val columns = remember(configuration) {
                max(2, (configuration.screenWidthDp / cellMinSize.value).toInt())
            }

            Column(modifier = Modifier.fillMaxSize()) {
                val hasDraft = draftQtyByProduktuaId.isNotEmpty()
                val tableLabel = uiState.tableLabel?.takeIf { it.isNotBlank() } ?: tableId.toString()
                val guestsText = uiState.guestCount?.toString() ?: "—"
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TableRestaurant,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tableLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(
                            imageVector = Icons.Filled.People,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = guestsText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Box(
                        modifier =
                            Modifier.align(Alignment.CenterEnd)
                                .clip(CircleShape)
                                .background(if (hasDraft) Color(0xFFF3863A) else Color(0xFFBDBDBD))
                    ) {
                        IconButton(
                            enabled = hasDraft,
                            onClick = onOpenDraftConfirm,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Eskaera",
                                tint = Color.White
                            )
                        }
                    }
                }

                Text(
                    text = kategoriKey,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    textAlign = TextAlign.Center
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.produktuak, key = { it.id }) { produktua ->
                        val pendingQty = draftQtyByProduktuaId[produktua.id] ?: 0
                        val baseQty = baseQtyByProduktuaId[produktua.id] ?: 0
                        val displayedQty = baseQty + pendingQty
                        val canAdd = pendingQty < produktua.stock
                        val canRemove = pendingQty > 0

                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(18.dp),
                            tonalElevation = 0.dp
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = produktua.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = "${"%.2f".format(produktua.price)}€",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    text = "Stocka: ${produktua.stock}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    QuantityButton(
                                        enabled = canRemove,
                                        onClick = {
                                            if (pendingQty > 0) onAddDraftItem(produktua.id, produktua.price, -1)
                                        },
                                        icon = Icons.Filled.Remove
                                    )

                                    Box(
                                        modifier =
                                            Modifier
                                                .size(56.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFF3863A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = displayedQty.toString(),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White
                                        )
                                    }

                                    QuantityButton(
                                        enabled = canAdd,
                                        onClick = { onAddDraftItem(produktua.id, produktua.price, +1) },
                                        icon = Icons.Filled.Add
                                    )
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
        }
    }
}

@Composable
private fun QuantityButton(enabled: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val color by animateColorAsState(
        targetValue = when {
            !enabled -> Color.LightGray
            pressed -> Color(0xFFB35D22)
            else -> Color(0xFFF3863A)
        },
        label = "qtyBtn"
    )

    IconButton(onClick = onClick, enabled = enabled, interactionSource = interactionSource) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = Color.White)
        }
    }
}
