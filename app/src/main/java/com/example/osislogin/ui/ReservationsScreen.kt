package com.example.osislogin.ui

import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
    chatEnabled: Boolean,
    onChat: () -> Unit,
    onHome: () -> Unit,
    onReservations: () -> Unit,
    chatUnreadCount: Int
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var editingReservation by remember { mutableStateOf<ReservationUiModel?>(null) }
    var creatingReservation by remember { mutableStateOf(false) }
    var selectedShift by remember { mutableStateOf(Shift.Comida) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    AppChrome(
        onLogout = onLogout,
        onLogoClick = onHome,
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
                onCreate = { creatingReservation = true },
                onRefresh = viewModel::refresh
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                val options = listOf(Shift.Comida, Shift.Cena)
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = selectedShift == option,
                        onClick = { selectedShift = option },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(text = option.label)
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            val selected = uiState.selectedDateMillis
            val dayReservations =
                remember(uiState.reservations, selected, selectedShift) {
                    val key = ymdKey(selected)
                    uiState.reservations
                        .filter { r -> ymdKey(r.egunaOrduaMillis) == key }
                        .filter { r -> shiftFromMillis(r.egunaOrduaMillis) == selectedShift }
                        .sortedBy { it.egunaOrduaMillis }
                }

            if (isLandscape) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.fillMaxHeight().weight(0.9f),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        ReservationCalendar(
                            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).padding(start = 16.dp, end = 8.dp),
                            year = uiState.year,
                            month = uiState.month,
                            selectedDateMillis = uiState.selectedDateMillis,
                            selectedShift = selectedShift,
                            reservations = uiState.reservations,
                            onDateSelected = viewModel::selectDate
                        )
                    }

                    ReservationsList(
                        modifier = Modifier.fillMaxHeight().weight(1.1f),
                        selectedDateMillis = selected,
                        selectedShift = selectedShift,
                        reservations = dayReservations,
                        onEdit = { reservation -> editingReservation = reservation },
                        onViewTicket = { reservation ->
                            val url = viewModel.ticketUrlCandidates(reservation.id).firstOrNull() ?: return@ReservationsList
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ezin izan da tiketa ireki", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.widthIn(max = 400.dp), contentAlignment = Alignment.Center) {
                        ReservationCalendar(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).scale(1f),
                            year = uiState.year,
                            month = uiState.month,
                            selectedDateMillis = uiState.selectedDateMillis,
                            selectedShift = selectedShift,
                            reservations = uiState.reservations,
                            onDateSelected = viewModel::selectDate
                        )
                    }
                }

                ReservationsList(
                    modifier = Modifier.fillMaxSize(),
                    selectedDateMillis = selected,
                    selectedShift = selectedShift,
                    reservations = dayReservations,
                    onEdit = { reservation -> editingReservation = reservation },
                    onViewTicket = { reservation ->
                        val url = viewModel.ticketUrlCandidates(reservation.id).firstOrNull() ?: return@ReservationsList
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Ezin izan da tiketa ireki", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }
    }

    editingReservation?.let { reservation ->
        EditReservationDialog(
            reservation = reservation,
            tables = uiState.tables,
            allReservations = uiState.reservations,
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

    if (creatingReservation) {
        CreateReservationDialog(
            initialDateMillis = uiState.selectedDateMillis,
            tables = uiState.tables,
            allReservations = uiState.reservations,
            onDismiss = { creatingReservation = false },
            onCreate = { name, phone, tableId, guests, dateMillis, hour, minute ->
                viewModel.createReservation(
                    bezeroIzena = name,
                    telefonoa = phone,
                    mahaiakId = tableId,
                    guests = guests,
                    dateMillis = dateMillis,
                    hour = hour,
                    minute = minute,
                    onSuccess = { creatingReservation = false }
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
    onCreate: () -> Unit,
    onRefresh: () -> Unit
) {
    val label = remember(year, month) {
        val locale = Locale("eu", "ES")
        "${monthName(month - 1, locale)} $year"
    }
    val orange = remember { Color(0xFFF3863A) }

    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPrev,
                modifier = Modifier.size(40.dp).background(orange, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = "Aurreko hilabetea",
                    tint = Color.White
                )
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNext,
                modifier = Modifier.size(40.dp).background(orange, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "Hurrengo hilabetea",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(onClick = onCreate) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Erreserba berria")
            }
            IconButton(onClick = onRefresh) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Eguneratu")
            }
        }
    }
}

@Composable
private fun ReservationCalendar(
    modifier: Modifier = Modifier,
    year: Int,
    month: Int,
    selectedDateMillis: Long,
    selectedShift: Shift,
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
        val comida = HashMap<String, Int>()
        val cena = HashMap<String, Int>()
        reservations.forEach { r ->
            val c = Calendar.getInstance()
            c.timeInMillis = r.egunaOrduaMillis
            val y = c.get(Calendar.YEAR)
            val m = c.get(Calendar.MONTH) + 1
            if (y == year && m == month) {
                val key = ymdKey(r.egunaOrduaMillis)
                when (shiftFromMillis(r.egunaOrduaMillis)) {
                    Shift.Comida -> comida[key] = (comida[key] ?: 0) + 1
                    Shift.Cena -> cena[key] = (cena[key] ?: 0) + 1
                }
            }
        }
        comida to cena
    }

    Column(modifier = modifier) {
        val scheme = MaterialTheme.colorScheme
        val weekendColor = remember { Color(0xFFF3863A) }
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Al", "As", "Az", "Og", "Or", "Lr", "Ig").forEachIndexed { index, day ->
                val isWeekend = index >= 5
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isWeekend) weekendColor else scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        val totalCells = 42
        val spacing = 4.dp
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            for (week in 0 until 6) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    for (dow in 0 until 7) {
                        val cell = week * 7 + dow
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
                        val isWeekendColumn = dow >= 5
                        CalendarDayCell(
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            dateMillis = dateMillis,
                            dayLabel = dateMillis?.let { dayNumber.toString() }.orEmpty(),
                            isSelected = dateMillis != null && ymdKey(dateMillis) == ymdKey(selectedDateMillis),
                            isWeekend = dateMillis != null && isWeekendColumn,
                            comidaCount = dateMillis?.let { counts.first[ymdKey(it)] } ?: 0,
                            cenaCount = dateMillis?.let { counts.second[ymdKey(it)] } ?: 0,
                            selectedShift = selectedShift,
                            onClick = { dateMillis?.let(onDateSelected) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    modifier: Modifier = Modifier,
    dateMillis: Long?,
    dayLabel: String,
    isSelected: Boolean,
    isWeekend: Boolean,
    comidaCount: Int,
    cenaCount: Int,
    selectedShift: Shift,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val weekendColor = remember { Color(0xFFF3863A) }
    val bg =
        when {
            dateMillis == null -> Color.Transparent
            isSelected -> scheme.primaryContainer
            isWeekend -> weekendColor.copy(alpha = 0.28f)
            else -> scheme.surfaceVariant
        }
    val fg =
        when {
            dateMillis == null -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
            isSelected -> scheme.onPrimaryContainer
            isWeekend -> scheme.onSurface
            else -> scheme.onSurfaceVariant
        }

    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.clickable(enabled = dateMillis != null, onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = dayLabel,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                modifier = Modifier.align(Alignment.Center)
            )
            if (dateMillis != null) {
                val comidaColor = MaterialTheme.colorScheme.primary
                val cenaColor = MaterialTheme.colorScheme.secondary
                val comidaSize = if (selectedShift == Shift.Comida) 8.dp else 6.dp
                val cenaSize = if (selectedShift == Shift.Cena) 8.dp else 6.dp

                if (comidaCount > 0 && cenaCount > 0) {
                    Box(
                        modifier =
                            Modifier.align(Alignment.BottomStart)
                                .padding(4.dp)
                                .size(comidaSize)
                                .background(comidaColor, RoundedCornerShape(99.dp))
                    )
                    Box(
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(cenaSize)
                                .background(cenaColor, RoundedCornerShape(99.dp))
                    )
                } else if (comidaCount > 0) {
                    Box(
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(comidaSize)
                                .background(comidaColor, RoundedCornerShape(99.dp))
                    )
                } else if (cenaCount > 0) {
                    Box(
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(cenaSize)
                                .background(cenaColor, RoundedCornerShape(99.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun ReservationsList(
    modifier: Modifier = Modifier,
    selectedDateMillis: Long,
    selectedShift: Shift,
    reservations: List<ReservationUiModel>,
    onEdit: (ReservationUiModel) -> Unit,
    onViewTicket: (ReservationUiModel) -> Unit
) {
    val locale = Locale.getDefault()
    val monthLocale = Locale("eu", "ES")
    val dateLabel = remember(selectedDateMillis) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = selectedDateMillis
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val monthName = monthName(cal.get(Calendar.MONTH), monthLocale)
        "$day $monthName"
    }
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text = "Erreserbak · $dateLabel · ${selectedShift.label}", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(10.dp))

        if (reservations.isEmpty()) {
            Text(
                text = "Egun honetarako ez dago erreserbarik",
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
                val isClosed = r.ordainduta != 0
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
                                text = "$timeLabel · ${r.mahaiakId}. Mahaia",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${r.pertsonaKopurua} pertsona · ${r.bezeroIzena.ifBlank { "—" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isClosed) {
                            IconButton(onClick = { onViewTicket(r) }) {
                                Icon(imageVector = Icons.Filled.ReceiptLong, contentDescription = "Tiketa")
                            }
                        } else {
                            IconButton(onClick = { onEdit(r) }) {
                                Icon(imageVector = Icons.Filled.Edit, contentDescription = "Editatu")
                            }
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
    tables: List<TableUiModel>,
    allReservations: List<ReservationUiModel>,
    onDismiss: () -> Unit,
    onSave: (newTableId: Int, newGuests: Int, newDateMillis: Long, newHour: Int, newMinute: Int) -> Unit
) {
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dialogMinWidth = if (isLandscape) 760.dp else 340.dp
    val dialogMaxWidth = if (isLandscape) 980.dp else 560.dp
    val initialDateMillis = remember(reservation.egunaOrduaMillis) { startOfDayMillis(reservation.egunaOrduaMillis) }
    val cal = remember(reservation.egunaOrduaMillis) { Calendar.getInstance().apply { timeInMillis = reservation.egunaOrduaMillis } }

    var showDatePicker by remember { mutableStateOf(false) }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialDateMillis)

    var dateMillis by remember { mutableStateOf(initialDateMillis) }
    var guestsText by remember { mutableStateOf(reservation.pertsonaKopurua.toString()) }
    val initialMinutes = remember { cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) }
    val initialShift =
        remember {
            when (initialMinutes) {
                in (13 * 60) until (16 * 60) -> Shift.Comida
                in (19 * 60) until (23 * 60) -> Shift.Cena
                else -> if (initialMinutes < 19 * 60) Shift.Comida else Shift.Cena
            }
        }
    var selectedShift by remember { mutableStateOf(initialShift) }
    val initialSlot =
        remember {
            val slots = generateSlotMinutes(initialShift)
            val rounded = (initialMinutes / 30) * 30
            slots.minByOrNull { kotlin.math.abs(it - rounded) } ?: slots.first()
        }
    var selectedSlotStartMinutes by remember { mutableStateOf(initialSlot) }
    var selectedTableId by remember { mutableStateOf(reservation.mahaiakId) }

    val computedTables =
        remember(tables, allReservations, dateMillis, selectedSlotStartMinutes) {
            tablesWithAvailability(
                baseTables = tables,
                reservations = allReservations,
                selectedDateMillis = dateMillis,
                slotStartMinutes = selectedSlotStartMinutes,
                excludeReservationId = reservation.id
            )
        }
    val sections = remember(computedTables) { groupByZone(computedTables) }
    val selectedTable =
        remember(computedTables, selectedTableId) {
            computedTables.firstOrNull { it.id == selectedTableId }
        }

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
                ) { Text(text = "Ados") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(text = "Utzi") }
            }
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = dialogMinWidth, max = dialogMaxWidth),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(text = "Erreserba editatu") },
        text = {
            val slots = remember(selectedShift) { generateSlotMinutes(selectedShift) }
            val disabledSlots =
                remember(allReservations, dateMillis, selectedTableId, slots) {
                    slots
                        .asSequence()
                        .filter {
                            isSlotBlockedForTable(
                                reservations = allReservations,
                                selectedDateMillis = dateMillis,
                                tableId = selectedTableId,
                                slotStartMinutes = it,
                                excludeReservationId = reservation.id
                            )
                        }
                        .toSet()
                }
            val tablesContent: @Composable (Modifier) -> Unit = { modifier ->
                LazyColumn(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sections, key = { it.name }) { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = section.name, style = MaterialTheme.typography.titleSmall)
                            SectionFlowRow(
                                tables = section.tables,
                                onMesaClick = { table ->
                                    if (table.availability != TableAvailability.Libre && table.id != selectedTableId) {
                                        Toast.makeText(context, "Mahaia ez dago erabilgarri", Toast.LENGTH_SHORT).show()
                                        return@SectionFlowRow
                                    }
                                    selectedTableId = table.id
                                    val max = table.maxComensales
                                    val value = guestsText.toIntOrNull()
                                    if (value != null && value > max) {
                                        guestsText = max.toString()
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(text = "Erreserba #${reservation.id}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = selectedTable?.numberLabel?.let { "$it. Mahaia" } ?: "—",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = guestsText,
                            onValueChange = { input ->
                                val digits = input.filter { ch -> ch.isDigit() }.take(3)
                                val max = selectedTable?.maxComensales
                                val clamped =
                                    if (max != null) {
                                        val value = digits.toIntOrNull()
                                        if (value != null && value > max) max.toString() else digits
                                    } else {
                                        digits
                                    }
                                guestsText = clamped
                            },
                            label = { Text(text = "Pertsonak (gehienez ${selectedTable?.maxComensales ?: "—"})") },
                            singleLine = true
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
                                Text(text = ymdKey(dateMillis), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val options = listOf(Shift.Comida, Shift.Cena)
                            options.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = selectedShift == option,
                                    onClick = {
                                        selectedShift = option
                                        val newSlots = generateSlotMinutes(option)
                                        selectedSlotStartMinutes = newSlots.first()
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                                ) {
                                    Text(text = option.label)
                                }
                            }
                        }

                        ReservationTimeSlotPicker(
                            slots = slots,
                            selectedSlotStartMinutes = selectedSlotStartMinutes,
                            disabledSlots = disabledSlots,
                            onSlotSelected = { selectedSlotStartMinutes = it }
                        )
                    }

                    tablesContent(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = "Erreserba #${reservation.id}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = selectedTable?.numberLabel?.let { "$it. Mahaia" } ?: "—",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = guestsText,
                        onValueChange = { input ->
                            val digits = input.filter { ch -> ch.isDigit() }.take(3)
                            val max = selectedTable?.maxComensales
                            val clamped =
                                if (max != null) {
                                    val value = digits.toIntOrNull()
                                    if (value != null && value > max) max.toString() else digits
                                } else {
                                    digits
                                }
                            guestsText = clamped
                        },
                        label = { Text(text = "Pertsonak (gehienez ${selectedTable?.maxComensales ?: "—"})") },
                        singleLine = true
                    )
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
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf(Shift.Comida, Shift.Cena)
                        options.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = selectedShift == option,
                                onClick = {
                                    selectedShift = option
                                    val newSlots = generateSlotMinutes(option)
                                    selectedSlotStartMinutes = newSlots.first()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                            ) {
                                Text(text = option.label)
                            }
                        }
                    }

                    ReservationTimeSlotPicker(
                        slots = slots,
                        selectedSlotStartMinutes = selectedSlotStartMinutes,
                        disabledSlots = disabledSlots,
                        onSlotSelected = { selectedSlotStartMinutes = it }
                    )

                    tablesContent(Modifier.fillMaxWidth().height(260.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val guests = guestsText.toIntOrNull() ?: 0
                    val tableId = selectedTableId
                    if (tableId <= 0 || guests <= 0) {
                        Toast.makeText(context, "Datu baliogabeak", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val max = selectedTable?.maxComensales ?: 0
                    if (max > 0 && guests > max) {
                        Toast.makeText(context, "Mahaia honetarako gehienez $max pertsona", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    if (
                        isSlotBlockedForTable(
                            reservations = allReservations,
                            selectedDateMillis = dateMillis,
                            tableId = tableId,
                            slotStartMinutes = selectedSlotStartMinutes,
                            excludeReservationId = reservation.id
                        )
                    ) {
                        Toast.makeText(context, "Ordu horretan mahaia ez dago erabilgarri", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    onSave(
                        tableId,
                        guests,
                        dateMillis,
                        selectedSlotStartMinutes / 60,
                        selectedSlotStartMinutes % 60
                    )
                    onDismiss()
                }
            ) { Text(text = "Gorde") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "Utzi") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReservationTimeSlotPicker(
    slots: List<Int>,
    selectedSlotStartMinutes: Int,
    disabledSlots: Set<Int>,
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
                val enabled = isSelected || !disabledSlots.contains(slotMinutes)
                if (isSelected) {
                    Button(onClick = { onSlotSelected(slotMinutes) }) {
                        Text(text = formatSlotMinutes(slotMinutes))
                    }
                } else {
                    OutlinedButton(enabled = enabled, onClick = { onSlotSelected(slotMinutes) }) {
                        Text(text = formatSlotMinutes(slotMinutes))
                    }
                }
            }
        }
    }
}

private const val ReservationBlockMinutes = 90

private fun isSlotBlockedForTable(
    reservations: List<ReservationUiModel>,
    selectedDateMillis: Long,
    tableId: Int,
    slotStartMinutes: Int,
    excludeReservationId: Int?
): Boolean {
    val selectedKey = ymdKey(selectedDateMillis)
    for (r in reservations) {
        if (r.ordainduta != 0) continue
        if (excludeReservationId != null && r.id == excludeReservationId) continue
        if (r.mahaiakId != tableId) continue
        if (ymdKey(r.egunaOrduaMillis) != selectedKey) continue
        val start = slotStartMinutesFromMillisOrNull(r.egunaOrduaMillis) ?: continue
        if (slotStartMinutes in start until (start + ReservationBlockMinutes)) return true
    }
    return false
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

private fun groupByZone(tables: List<TableUiModel>): List<TableSectionUiModel> {
    val grouped = tables.groupBy { it.zone.ifBlank { "Zonarik gabe" } }
    return grouped.entries
        .sortedBy { it.key.lowercase() }
        .map { (zone, list) ->
            TableSectionUiModel(
                name = zone,
                tables = list.sortedBy { it.numberLabel.toIntOrNull() ?: Int.MAX_VALUE }
            )
        }
}

private fun tablesWithAvailability(
    baseTables: List<TableUiModel>,
    reservations: List<ReservationUiModel>,
    selectedDateMillis: Long,
    slotStartMinutes: Int,
    excludeReservationId: Int?
): List<TableUiModel> {
    val selectedKey = ymdKey(selectedDateMillis)
    val todayKey = ymdKey(System.currentTimeMillis())
    val nowMillis = System.currentTimeMillis()
    val relevant =
        reservations.asSequence()
            .filter { it.ordainduta == 0 }
            .filter { excludeReservationId == null || it.id != excludeReservationId }
            .filter { ymdKey(it.egunaOrduaMillis) == selectedKey }
            .filter {
                val start = slotStartMinutesFromMillisOrNull(it.egunaOrduaMillis) ?: return@filter false
                slotStartMinutes in start until (start + ReservationBlockMinutes)
            }
            .toList()

    val byTableId = HashMap<Int, ReservationUiModel>(relevant.size)
    for (r in relevant) {
        val existing = byTableId[r.mahaiakId]
        if (existing == null || r.egunaOrduaMillis > existing.egunaOrduaMillis) {
            byTableId[r.mahaiakId] = r
        }
    }

    val out = ArrayList<TableUiModel>(baseTables.size)
    for (t in baseTables) {
        val r = byTableId[t.id]
        if (r == null) {
            out.add(
                t.copy(
                    availability = TableAvailability.Libre,
                    ocupadas = null,
                    erreserbaId = null,
                    hasKitchenAlert = false
                )
            )
        } else {
            val isFutureSelected = selectedKey > todayKey
            val isToday = selectedKey == todayKey
            val reserved = isFutureSelected || (isToday && r.egunaOrduaMillis > nowMillis) || (!isFutureSelected && !isToday)
            val availability = if (reserved) TableAvailability.Reservada else TableAvailability.Ocupada
            out.add(
                t.copy(
                    availability = availability,
                    ocupadas = r.pertsonaKopurua,
                    erreserbaId = r.id,
                    hasKitchenAlert = false
                )
            )
        }
    }

    return out.sortedWith(compareBy<TableUiModel> { it.zone.lowercase() }.thenBy { it.numberLabel.toIntOrNull() ?: Int.MAX_VALUE })
}

private fun slotStartMinutesFromMillisOrNull(millis: Long): Int? {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val slot = (minutes / 30) * 30
    return when (slot) {
        in (13 * 60) until (16 * 60) -> slot
        in (19 * 60) until (23 * 60) -> slot
        else -> null
    }
}

private fun shiftFromMillis(millis: Long): Shift {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    return when (minutes) {
        in (13 * 60) until (16 * 60) -> Shift.Comida
        in (19 * 60) until (23 * 60) -> Shift.Cena
        else -> if (minutes < 19 * 60) Shift.Comida else Shift.Cena
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateReservationDialog(
    initialDateMillis: Long,
    tables: List<TableUiModel>,
    allReservations: List<ReservationUiModel>,
    onDismiss: () -> Unit,
    onCreate: (name: String, phone: String, tableId: Int, guests: Int, dateMillis: Long, hour: Int, minute: Int) -> Unit
) {
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dialogMinWidth = if (isLandscape) 760.dp else 340.dp
    val dialogMaxWidth = if (isLandscape) 980.dp else 560.dp
    var nameText by remember { mutableStateOf("") }
    var phoneText by remember { mutableStateOf("") }
    var guestsText by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    val initialLocalDateMillis = remember(initialDateMillis) { startOfDayMillis(initialDateMillis) }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialLocalDateMillis)
    var dateMillis by remember { mutableStateOf(initialLocalDateMillis) }

    var selectedShift by remember { mutableStateOf(Shift.Comida) }
    val initialSlot = remember { generateSlotMinutes(Shift.Comida).first() }
    var selectedSlotStartMinutes by remember { mutableStateOf(initialSlot) }
    var selectedTableId by remember { mutableStateOf<Int?>(null) }

    val computedTables =
        remember(tables, allReservations, dateMillis, selectedSlotStartMinutes) {
            tablesWithAvailability(
                baseTables = tables,
                reservations = allReservations,
                selectedDateMillis = dateMillis,
                slotStartMinutes = selectedSlotStartMinutes,
                excludeReservationId = null
            )
        }
    val sections = remember(computedTables) { groupByZone(computedTables) }
    val selectedTable =
        remember(computedTables, selectedTableId) {
            selectedTableId?.let { id -> computedTables.firstOrNull { it.id == id } }
        }

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
                ) { Text(text = "Ados") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(text = "Utzi") }
            }
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = dialogMinWidth, max = dialogMaxWidth),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(text = "Erreserba berria") },
        text = {
            val slots = remember(selectedShift) { generateSlotMinutes(selectedShift) }
            val disabledSlots =
                remember(allReservations, dateMillis, selectedTableId, slots) {
                    val tableId = selectedTableId ?: return@remember emptySet()
                    slots
                        .asSequence()
                        .filter {
                            isSlotBlockedForTable(
                                reservations = allReservations,
                                selectedDateMillis = dateMillis,
                                tableId = tableId,
                                slotStartMinutes = it,
                                excludeReservationId = null
                            )
                        }
                        .toSet()
                }
            val tablesContent: @Composable (Modifier) -> Unit = { modifier ->
                LazyColumn(
                    modifier = modifier,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sections, key = { it.name }) { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = section.name, style = MaterialTheme.typography.titleSmall)
                            SectionFlowRow(
                                tables = section.tables,
                                onMesaClick = { table ->
                                    if (table.availability != TableAvailability.Libre) {
                                        Toast.makeText(context, "Mahaia ez dago erabilgarri", Toast.LENGTH_SHORT).show()
                                        return@SectionFlowRow
                                    }
                                    selectedTableId = table.id
                                    val max = table.maxComensales
                                    val value = guestsText.toIntOrNull()
                                    if (value != null && value > max) {
                                        guestsText = max.toString()
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = nameText,
                            onValueChange = { nameText = it.take(60) },
                            label = { Text(text = "Izena") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = phoneText,
                            onValueChange = { phoneText = it.take(30) },
                            label = { Text(text = "Telefonoa") },
                            singleLine = true
                        )
                        Text(
                            text = selectedTable?.numberLabel?.let { "$it. Mahaia" } ?: "—",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = guestsText,
                            onValueChange = { input ->
                                val digits = input.filter { ch -> ch.isDigit() }.take(3)
                                val max = selectedTable?.maxComensales
                                val clamped =
                                    if (max != null) {
                                        val value = digits.toIntOrNull()
                                        if (value != null && value > max) max.toString() else digits
                                    } else {
                                        digits
                                    }
                                guestsText = clamped
                            },
                            label = { Text(text = "Pertsonak (gehienez ${selectedTable?.maxComensales ?: "—"})") },
                            singleLine = true
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
                                Text(text = ymdKey(dateMillis), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val options = listOf(Shift.Comida, Shift.Cena)
                            options.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = selectedShift == option,
                                    onClick = {
                                        selectedShift = option
                                        selectedSlotStartMinutes = generateSlotMinutes(option).first()
                                        selectedTableId = null
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                                ) {
                                    Text(text = option.label)
                                }
                            }
                        }

                        ReservationTimeSlotPicker(
                            slots = slots,
                            selectedSlotStartMinutes = selectedSlotStartMinutes,
                            disabledSlots = disabledSlots,
                            onSlotSelected = { selectedSlotStartMinutes = it }
                        )
                    }

                    tablesContent(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it.take(60) },
                        label = { Text(text = "Izena") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = phoneText,
                        onValueChange = { phoneText = it.take(30) },
                        label = { Text(text = "Telefonoa") },
                        singleLine = true
                    )
                    Text(
                        text = selectedTable?.numberLabel?.let { "$it. Mahaia" } ?: "—",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = guestsText,
                        onValueChange = { input ->
                            val digits = input.filter { ch -> ch.isDigit() }.take(3)
                            val max = selectedTable?.maxComensales
                            val clamped =
                                if (max != null) {
                                    val value = digits.toIntOrNull()
                                    if (value != null && value > max) max.toString() else digits
                                } else {
                                    digits
                                }
                            guestsText = clamped
                        },
                        label = { Text(text = "Pertsonak (gehienez ${selectedTable?.maxComensales ?: "—"})") },
                        singleLine = true
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
                            Text(text = ymdKey(dateMillis), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf(Shift.Comida, Shift.Cena)
                        options.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = selectedShift == option,
                                onClick = {
                                    selectedShift = option
                                    selectedSlotStartMinutes = generateSlotMinutes(option).first()
                                    selectedTableId = null
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                            ) {
                                Text(text = option.label)
                            }
                        }
                    }

                    ReservationTimeSlotPicker(
                        slots = slots,
                        selectedSlotStartMinutes = selectedSlotStartMinutes,
                        disabledSlots = disabledSlots,
                        onSlotSelected = { selectedSlotStartMinutes = it }
                    )

                    tablesContent(Modifier.fillMaxWidth().height(260.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val guests = guestsText.toIntOrNull() ?: 0
                    val tableId = selectedTableId ?: 0
                    if (tableId <= 0 || guests <= 0) {
                        Toast.makeText(context, "Datu baliogabeak", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val max = selectedTable?.maxComensales ?: 0
                    if (max > 0 && guests > max) {
                        Toast.makeText(context, "Mahaia honetarako gehienez $max pertsona", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    if (
                        isSlotBlockedForTable(
                            reservations = allReservations,
                            selectedDateMillis = dateMillis,
                            tableId = tableId,
                            slotStartMinutes = selectedSlotStartMinutes,
                            excludeReservationId = null
                        )
                    ) {
                        Toast.makeText(context, "Ordu horretan mahaia ez dago erabilgarri", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    onCreate(
                        nameText,
                        phoneText,
                        tableId,
                        guests,
                        dateMillis,
                        selectedSlotStartMinutes / 60,
                        selectedSlotStartMinutes % 60
                    )
                }
            ) { Text(text = "Sortu") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "Utzi") }
        }
    )
}
