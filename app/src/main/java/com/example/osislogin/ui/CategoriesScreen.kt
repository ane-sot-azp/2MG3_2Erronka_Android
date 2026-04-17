package com.example.osislogin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.osislogin.R

@Composable
fun CategoriesScreen(
    tableId: Int,
    initialErreserbaId: Int,
    viewModel: CategoriesViewModel,
    onLogout: () -> Unit,
    chatEnabled: Boolean,
    onChat: () -> Unit,
    onReservations: () -> Unit,
    chatUnreadCount: Int,
    hasDraft: Boolean,
    onDraftTick: () -> Unit,
    onGoToTables: () -> Unit,
    onTicketClick: (tableId: Int, erreserbaId: Int) -> Unit,
    onCategorySelected: (tableId: Int, erreserbaId: Int, kategoriKey: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(tableId, initialErreserbaId) {
        viewModel.load(tableId, initialErreserbaId)
    }

    AppChrome(
        onLogout = onLogout,
        onLogoClick = if (hasDraft) onDraftTick else onGoToTables,
        navigationIcon = if (hasDraft) Icons.Filled.CheckCircle else Icons.Filled.TableRestaurant,
        navigationIconContentDescription = if (hasDraft) "Eskaera" else "Mahaiak",
        showMiddleAction = true,
        middleIconContentDescription = "Erreserbak",
        onMiddleAction = onReservations,
        showRightAction = chatEnabled,
        rightIconResId = R.drawable.chat,
        rightIconContentDescription = "Txata",
        onRightAction = onChat,
        rightBadgeCount = chatUnreadCount
    ) { contentModifier ->
        Box(modifier = contentModifier.fillMaxSize()) {
            val categories = uiState.categories
            val orange = remember { Color(0xFFF3863A) }
            val shape = remember { RoundedCornerShape(18.dp) }
            val elevation = 10.dp

            if (uiState.error != null && !uiState.isLoading) {
                Text(
                    text = uiState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (!uiState.isLoading && categories.isEmpty()) {
                Text(
                    text = "Ez daude kategoriak",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                val c0 = categories.getOrNull(0)
                val c1 = categories.getOrNull(1)
                val c2 = categories.getOrNull(2)
                val c3 = categories.getOrNull(3)

                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val tableLabel = uiState.tableLabel?.takeIf { it.isNotBlank() } ?: tableId.toString()
                    val guestsText = uiState.guestCount?.toString() ?: "—"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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

                    @Composable
                    fun CategoryTile(category: Category?, iconResId: Int, modifier: Modifier) {
                        if (category == null) {
                            Box(modifier = modifier)
                            return
                        }

                        Surface(
                            color = Color.White,
                            shape = shape,
                            modifier =
                                modifier
                                    .shadow(elevation = elevation, shape = shape)
                                    .clickable {
                                        val erreserbaId = uiState.erreserbaId ?: return@clickable
                                        if (erreserbaId <= 0) return@clickable
                                        onCategorySelected(tableId, erreserbaId, category.name)
                                    }
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(iconResId),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(orange),
                                    modifier = Modifier.size(92.dp)
                                )
                            }
                        }
                    }

                    CategoryTile(c0, R.drawable.primero, Modifier.fillMaxWidth().weight(1f))
                    CategoryTile(c1, R.drawable.segundo, Modifier.fillMaxWidth().weight(1f))
                    CategoryTile(c2, R.drawable.postre, Modifier.fillMaxWidth().weight(1f))

                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CategoryTile(c3, R.drawable.bebidas, Modifier.weight(1f).fillMaxHeight())

                        Surface(
                            color = orange,
                            shape = shape,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .shadow(elevation = elevation, shape = shape)
                                    .clickable {
                                        val erreserbaId = uiState.erreserbaId ?: return@clickable
                                        if (erreserbaId <= 0) return@clickable
                                        onTicketClick(tableId, erreserbaId)
                                    }
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(R.drawable.recibo),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(Color.White),
                                    modifier = Modifier.size(92.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

        }
    }
}
