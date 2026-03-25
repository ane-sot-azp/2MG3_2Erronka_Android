package com.example.osislogin.ui

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.osislogin.R
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun ReservationsScreen(
    viewModel: ReservationsViewModel,
    onLogout: () -> Unit,
    onChat: () -> Unit,
    onHome: () -> Unit,
    onReservations: () -> Unit,
    chatUnreadCount: Int
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var editingReservation by remember { mutableStateOf<ReservationUiModel?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    AppChrome(
        onLogout = onLogout,
        onLogoClick = onHome,
        showMiddleAction = true,
        middleIconContentDescription = "Reservas",
        onMiddleAction = onReservations,
        rightIconResId = R.drawable.chat,
        rightIconContentDescription = "Chat",
        onRightAction = onChat,
        rightBadgeCount = chatUnreadCount
    ) { contentModifier ->
        Column(modifier = contentModifier.fillMaxSize()) {
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            CalendarHeader(
                year = uiState.year,
                month = uiState.month,
                onPrev = {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.YEAR, uiState.year)
                    cal.set(Calendar.MONTH, uiState.month - 1)
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.add(Calendar.MONTH, -1)
                    viewModel.setMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
                },
                onNext = {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.YEAR, uiState.year)
                    cal.set(Calendar.MONTH, uiState.month - 1)
                    cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.add(Calendar.MONTH, 1)
                    viewModel.setMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
                },
                onRefresh = viewModel::refresh
            )

            ReservationCalendar(
                year = uiState.year,
                month = uiState.month,
                selectedDateMillis = uiState.selectedDateMillis,
                reservations = uiState.reservations,
                onDateSelected = viewModel::selectDate
            )

            val selected = uiState.selectedDateMillis
            val dayReservations =
                remember(uiState.reservations, selected) {
                    val key = ymdKey(selected)
                    uiState.reservations.filter { r -> ymdKey(r.egunaOrduaMillis) == key }.sortedBy { it.egunaOrduaMillis }
                }

            ReservationsList(
                selectedDateMillis = selected,
                reservations = dayReservations,
                onEdit = { reservation -> editingReservation = reservation }
            )
        }
    }

    editingReservation?.let { reservation ->
        EditReservationDialog(
            reservation = reservation,
            onDismiss = { editingReservation = null },
            onSave = { newTableId, newGuests, newDateMillis, newHour, newMinute ->
                viewModel.updateReservation(
                    reservation = reservation,
                    newMahaiakId = newTableId,
                    newGuests = newGuests,
                    newDateMillis = newDateMillis,
                    newHour = newHour,
                    newMinute = newMinute
                )
            }
        )
    }
}

@Composable
private fun CalendarHeader(
    year: Int,
    month: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRefresh: () -> Unit
) {
    val label = remember(year, month) {
        val locale = Locale.getDefault()
        "${monthName(month - 1, locale)} $year"
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) {
                Icon(imageVector = Icons.Filled.ChevronLeft, contentDescription = "Mes anterior")
            }
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onNext) {
                Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = "Mes siguiente")
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Actualizar")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReservationCalendar(
    year: Int,
    month: Int,
    selectedDateMillis: Long,
    reservations: List<ReservationUiModel>,
    onDateSelected: (Long) -> Unit
) {
    val cal = remember(year, month) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
    val firstDow = remember(cal) { cal.get(Calendar.DAY_OF_WEEK) }
    val offset = remember(firstDow) { (firstDow + 5) % 7 }
    val daysInMonth = remember(cal) { cal.getActualMaximum(Calendar.DAY_OF_MONTH) }

    val counts = remember(reservations, year, month) {
        val map = HashMap<String, Int>()
        reservations.forEach { r ->
            val c = Calendar.getInstance()
            c.timeInMillis = r.egunaOrduaMillis
            val y = c.get(Calendar.YEAR)
            val m = c.get(Calendar.MONTH) + 1
            if (y == year && m == month) {
                val key = ymdKey(r.egunaOrduaMillis)
                map[key] = (map[key] ?: 0) + 1
            }
        }
        map
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("L", "M", "X", "J", "V", "S", "D").forEach { day ->
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        val totalCells = 42
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = 7,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (cell in 0 until totalCells) {
                val dayNumber = cell - offset + 1
                val dateMillis =
                    if (dayNumber in 1..daysInMonth) {
                        val c = Calendar.getInstance()
                        c.set(Calendar.YEAR, year)
                        c.set(Calendar.MONTH, month - 1)
                        c.set(Calendar.DAY_OF_MONTH, dayNumber)
                        c.set(Calendar.HOUR_OF_DAY, 0)
                        c.set(Calendar.MINUTE, 0)
                        c.set(Calendar.SECOND, 0)
                        c.set(Calendar.MILLISECOND, 0)
                        c.timeInMillis
                    } else {
                        null
                    }
                CalendarDayCell(
                    dateMillis = dateMillis,
                    dayLabel = dateMillis?.let { dayNumber.toString() }.orEmpty(),
                    isSelected = dateMillis != null && ymdKey(dateMillis) == ymdKey(selectedDateMillis),
                    count = dateMillis?.let { counts[ymdKey(it)] } ?: 0,
                    onClick = { dateMillis?.let(onDateSelected) }
                )
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    dateMillis: Long?,
    dayLabel: String,
    isSelected: Boolean,
    count: Int,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val bg =
        when {
            dateMillis == null -> Color.Transparent
            isSelected -> scheme.primaryContainer
            else -> scheme.surfaceVariant
        }
    val fg =
        when {
            dateMillis == null -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
            isSelected -> scheme.onPrimaryContainer
            else -> scheme.onSurfaceVariant
        }

    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.size(44.dp).clickable(enabled = dateMillis != null, onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = dayLabel,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                modifier = Modifier.align(Alignment.Center)
            )
            if (dateMillis != null && count > 0) {
                Box(
                    modifier =
                        Modifier.align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(99.dp))
                )
            }
        }
    }
}

@Composable
private fun ReservationsList(
    selectedDateMillis: Long,
    reservations: List<ReservationUiModel>,
    onEdit: (ReservationUiModel) -> Unit
) {
    val locale = Locale.getDefault()
    val dateLabel = remember(selectedDateMillis) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = selectedDateMillis
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val monthName = monthName(cal.get(Calendar.MONTH), locale)
        "$day $monthName"
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text = "Reservas · $dateLabel", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(10.dp))

        if (reservations.isEmpty()) {
            Text(
                text = "No hay reservas para este día",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }

        val timeFormatter = remember { SimpleDateFormat("HH:mm", locale) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(reservations, key = { it.id }) { r ->
                val timeLabel =
                    remember(r.egunaOrduaMillis) {
                        timeFormatter.format(Date(r.egunaOrduaMillis))
                    }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "$timeLabel · Mesa ${r.mahaiakId}",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${r.pertsonaKopurua} comensales · ${r.bezeroIzena.ifBlank { "—" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onEdit(r) }) {
                            Icon(imageVector = Icons.Filled.Edit, contentDescription = "Editar")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditReservationDialog(
    reservation: ReservationUiModel,
    onDismiss: () -> Unit,
    onSave: (newTableId: Int, newGuests: Int, newDateMillis: Long, newHour: Int, newMinute: Int) -> Unit
) {
    val context = LocalContext.current
    val initialDateMillis = remember(reservation.egunaOrduaMillis) { startOfDayMillis(reservation.egunaOrduaMillis) }
    val cal = remember(reservation.egunaOrduaMillis) { Calendar.getInstance().apply { timeInMillis = reservation.egunaOrduaMillis } }

    var showDatePicker by remember { mutableStateOf(false) }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialDateMillis)

    var dateMillis by remember { mutableStateOf(initialDateMillis) }
    var timeText by remember { mutableStateOf("%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))) }
    var guestsText by remember { mutableStateOf(reservation.pertsonaKopurua.toString()) }
    var tableText by remember { mutableStateOf(reservation.mahaiakId.toString()) }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = pickerState.selectedDateMillis
                        if (millis != null) {
                            dateMillis = utcMillisToLocalMidnightMillis(millis)
                        }
                        showDatePicker = false
                    }
                ) { Text(text = "OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(text = "Cancelar") }
            }
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Editar reserva") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "Reserva #${reservation.id}", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tableText,
                        onValueChange = { tableText = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text(text = "Mesa") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = guestsText,
                        onValueChange = { guestsText = it.filter { ch -> ch.isDigit() }.take(3) },
                        label = { Text(text = "Comensales") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f).clickable { showDatePicker = true }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
                            Text(text = ymdKey(dateMillis), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedTextField(
                        value = timeText,
                        onValueChange = { timeText = it.take(5) },
                        label = { Text(text = "Hora (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val tableId = tableText.toIntOrNull() ?: 0
                    val guests = guestsText.toIntOrNull() ?: 0
                    val time = parseTime(timeText)
                    if (tableId <= 0 || guests <= 0 || time == null) {
                        Toast.makeText(context, "Datos inválidos", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    onSave(tableId, guests, dateMillis, time.first, time.second)
                    onDismiss()
                }
            ) { Text(text = "Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "Cancelar") }
        }
    )
}

private fun parseTime(text: String): Pair<Int, Int>? {
    val trimmed = text.trim()
    val parts = trimmed.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h to m
}

private fun startOfDayMillis(millis: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun utcMillisToLocalMidnightMillis(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utc.timeInMillis = utcMillis
    val year = utc.get(Calendar.YEAR)
    val month = utc.get(Calendar.MONTH)
    val day = utc.get(Calendar.DAY_OF_MONTH)

    val local = Calendar.getInstance()
    local.set(Calendar.YEAR, year)
    local.set(Calendar.MONTH, month)
    local.set(Calendar.DAY_OF_MONTH, day)
    local.set(Calendar.HOUR_OF_DAY, 0)
    local.set(Calendar.MINUTE, 0)
    local.set(Calendar.SECOND, 0)
    local.set(Calendar.MILLISECOND, 0)
    return local.timeInMillis
}

private fun ymdKey(millis: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    return "%04d-%02d-%02d".format(y, m, d)
}

private fun monthName(month0: Int, locale: Locale): String {
    val symbols = DateFormatSymbols(locale)
    return symbols.months.getOrNull(month0)?.trim().orEmpty().replaceFirstChar { it.titlecase(locale) }
}
