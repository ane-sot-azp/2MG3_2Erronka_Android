package com.example.osislogin.ui

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.osislogin.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onLogout: () -> Unit,
    chatEnabled: Boolean,
    onChat: () -> Unit,
    onReservations: () -> Unit,
    chatUnreadCount: Int,
    onTableClick: (tableId: Int, erreserbaId: Int?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var reserveDialogTable by remember { mutableStateOf<TableUiModel?>(null) }
    var reserveDialogGuestCount by remember { mutableStateOf("") }
    var reserveDialogSlotMinutes by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    AppChrome(
        onLogout = {
            viewModel.logout()
            onLogout()
        },
        onLogoClick = { viewModel.refresh() },
        showMiddleAction = true,
        middleIconContentDescription = "Erreserbak",
        onMiddleAction = onReservations,
        showRightAction = chatEnabled,
        rightIconResId = R.drawable.chat,
        rightIconContentDescription = "Txata",
        onRightAction = onChat,
        rightBadgeCount = chatUnreadCount
    ) { contentModifier ->
        Column(modifier = contentModifier.fillMaxSize()) {
            DateShiftHeader(
                selectedDateMillis = uiState.selectedDateMillis,
                selectedShift = uiState.selectedShift,
                selectedSlotStartMinutes = uiState.selectedSlotStartMinutes,
                onDateSelected = viewModel::setSelectedDate,
                onShiftSelected = viewModel::setSelectedShift,
                onSlotSelected = viewModel::setSelectedSlotStartMinutes,
                onRefresh = viewModel::refresh
            )

            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(
                    count = uiState.sections.size,
                    key = { index -> uiState.sections[index].name }
                ) { index ->
                    val section = uiState.sections[index]
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = section.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        SectionFlowRow(
                            tables = section.tables,
                            onMesaClick = { table ->
                                when (table.availability) {
                                    TableAvailability.Libre -> {
                                        reserveDialogTable = table
                                        reserveDialogGuestCount = ""
                                        reserveDialogSlotMinutes = uiState.selectedSlotStartMinutes
                                    }
                                    TableAvailability.Reservada -> {
                                        viewModel.markReservedAsArrived(table.id)
                                        onTableClick(table.id, table.erreserbaId)
                                    }
                                    TableAvailability.Ocupada -> onTableClick(table.id, table.erreserbaId)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    reserveDialogTable?.let { table ->
        AlertDialog(
            onDismissRequest = { reserveDialogTable = null },
            title = { Text(text = "Erreserba sortu") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "${table.numberLabel}. Mahaia")
                    OutlinedTextField(
                        value = reserveDialogGuestCount,
                        onValueChange = { reserveDialogGuestCount = it.filter { ch -> ch.isDigit() }.take(3) },
                        label = { Text(text = "Pertsonak") },
                        singleLine = true
                    )

                    val slots = remember(uiState.selectedShift) { generateSlotMinutes(uiState.selectedShift) }
                    val selectedSlot = reserveDialogSlotMinutes ?: uiState.selectedSlotStartMinutes
                    TimeSlotPicker(
                        slots = slots,
                        selectedSlotStartMinutes = selectedSlot,
                        onSlotSelected = { reserveDialogSlotMinutes = it }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val guests = reserveDialogGuestCount.toIntOrNull() ?: 0
                        if (guests <= 0) {
                            Toast.makeText(context, "Sartu pertsona kopuru egokia", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val tableId = table.id
                        val slot = reserveDialogSlotMinutes ?: uiState.selectedSlotStartMinutes
                        viewModel.createReservationNow(
                            tableId = tableId,
                            guestCount = guests,
                            slotStartMinutes = slot,
                            onSuccess = { reservationId ->
                                reserveDialogTable = null
                                reserveDialogGuestCount = ""
                                reserveDialogSlotMinutes = null
                                onTableClick(tableId, reservationId)
                            }
                        )
                    }
                ) { Text(text = "Sortu") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        reserveDialogTable = null
                        reserveDialogGuestCount = ""
                        reserveDialogSlotMinutes = null
                    }
                ) { Text(text = "Utzi") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectionFlowRow(
    tables: List<TableUiModel>,
    onMesaClick: (TableUiModel) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tables.forEach { table ->
            MesaCard(
                table = table,
                onClick = { onMesaClick(table) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateShiftHeader(
    selectedDateMillis: Long,
    selectedShift: Shift,
    selectedSlotStartMinutes: Int,
    onDateSelected: (Long) -> Unit,
    onShiftSelected: (Shift) -> Unit,
    onSlotSelected: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val dateLabel = remember(selectedDateMillis) {
        val df = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        df.format(Date(selectedDateMillis))
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDateMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let(onDateSelected)
                        showPicker = false
                    }
                ) { Text(text = "Ados") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(text = "Utzi") }
            }
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(
                onClick = { showPicker = true },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(imageVector = Icons.Filled.DateRange, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = dateLabel)
            }

            IconButton(onClick = onRefresh) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Eguneratu")
            }
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf(Shift.Comida, Shift.Cena)
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selectedShift == option,
                    onClick = { onShiftSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                ) {
                    Text(text = option.label)
                }
            }
        }

        val slots = remember(selectedShift) { generateSlotMinutes(selectedShift) }
        TimeSlotPicker(
            slots = slots,
            selectedSlotStartMinutes = selectedSlotStartMinutes,
            onSlotSelected = onSlotSelected
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeSlotPicker(
    slots: List<Int>,
    selectedSlotStartMinutes: Int,
    onSlotSelected: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Ordua")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            slots.forEach { slotMinutes ->
                val isSelected = slotMinutes == selectedSlotStartMinutes
                if (isSelected) {
                    Button(onClick = { onSlotSelected(slotMinutes) }) {
                        Text(text = formatSlotMinutes(slotMinutes))
                    }
                } else {
                    OutlinedButton(onClick = { onSlotSelected(slotMinutes) }) {
                        Text(text = formatSlotMinutes(slotMinutes))
                    }
                }
            }
        }
    }
}

private fun generateSlotMinutes(shift: Shift): List<Int> {
    val (start, end) =
        when (shift) {
            Shift.Comida -> 13 * 60 to 16 * 60
            Shift.Cena -> 19 * 60 to 23 * 60
        }
    val result = ArrayList<Int>()
    var current = start
    while (current < end) {
        result.add(current)
        current += 30
    }
    return result
}

private fun formatSlotMinutes(minutesFromMidnight: Int): String {
    val h = minutesFromMidnight / 60
    val m = minutesFromMidnight % 60
    return "%02d:%02d".format(h, m)
}

@Composable
fun MesaCard(
    table: TableUiModel,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val occupiedColor = remember { Color(0xFFF3863A) }
    val reservedColor = remember { Color(0xFFFFD6BF) }
    val container =
        when {
            table.hasKitchenAlert -> scheme.errorContainer
            table.availability == TableAvailability.Libre -> scheme.secondaryContainer
            table.availability == TableAvailability.Reservada -> reservedColor
            else -> occupiedColor
        }
    val content =
        when {
            table.hasKitchenAlert -> scheme.onErrorContainer
            else -> scheme.onSurface
        }
    val border =
        if (table.hasKitchenAlert) {
            BorderStroke(2.dp, scheme.error)
        } else {
            null
        }

    val size = remember(table.maxComensales) { mesaSize(table.maxComensales) }
    val seatSize = 12.dp
    val seatGap = 6.dp
    val outerWidth = size.width + (seatSize + seatGap) * 2
    val outerHeight = size.height + (seatSize + seatGap) * 2

    val transition = rememberInfiniteTransition(label = "kitchenAlert")
    val alertAlpha =
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alertAlpha"
        )

    val seatColor =
        if (table.hasKitchenAlert) {
            scheme.error.copy(alpha = 0.55f)
        } else {
            scheme.onSurfaceVariant.copy(alpha = 0.35f)
        }
    val seats = remember(table.maxComensales) { mesaSeats(table.maxComensales) }

    Box(
        modifier =
            Modifier
                .size(outerWidth, outerHeight)
                .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.spacedBy(seatGap)
        ) {
            repeat(seats.top) {
                MesaSeat(color = seatColor, modifier = Modifier.size(seatSize, seatSize))
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(seatGap)
        ) {
            repeat(seats.bottom) {
                MesaSeat(color = seatColor, modifier = Modifier.size(seatSize, seatSize))
            }
        }

        Column(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(seatGap)
        ) {
            repeat(seats.left) {
                MesaSeat(color = seatColor, modifier = Modifier.size(seatSize, seatSize))
            }
        }

        Column(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalArrangement = Arrangement.spacedBy(seatGap)
        ) {
            repeat(seats.right) {
                MesaSeat(color = seatColor, modifier = Modifier.size(seatSize, seatSize))
            }
        }

        val tableShape =
            if (table.maxComensales <= 4) {
                RoundedCornerShape(percent = 50)
            } else {
                RoundedCornerShape(18.dp)
            }
        Surface(
            color = container,
            contentColor = content,
            shape = tableShape,
            border = border,
            modifier = Modifier.align(Alignment.Center).size(size.width, size.height)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                if (table.hasKitchenAlert) {
                    Icon(
                        imageVector = Icons.Filled.NotificationsActive,
                        contentDescription = "Komanda prest",
                        tint = scheme.error.copy(alpha = alertAlpha.value),
                        modifier = Modifier.align(Alignment.TopEnd).size(22.dp)
                    )
                }

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${table.numberLabel}. Mahaia",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(imageVector = Icons.Filled.People, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = "${table.ocupadas ?: 0}/${table.maxComensales}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

private data class MesaSize(val width: Dp, val height: Dp)

private fun mesaSize(maxComensales: Int): MesaSize {
    val effectiveMax = maxComensales.coerceAtLeast(4)
    val steps = (effectiveMax - 4 + 1) / 2
    val widthDp = 104 + (22 * steps)
    return MesaSize(width = widthDp.dp, height = 84.dp)
}

private data class MesaSeats(val top: Int, val bottom: Int, val left: Int, val right: Int)

private fun mesaSeats(maxComensales: Int): MesaSeats {
    val seats = maxComensales.coerceAtLeast(0)
    val top = (seats + 1) / 2
    val bottom = seats / 2
    return MesaSeats(top = top, bottom = bottom, left = 0, right = 0)
}

@Composable
private fun MesaSeat(
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = color,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {}
}
