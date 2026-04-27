package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.osislogin.util.SessionManager
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class Shift(val label: String) {
    Comida("Bazkaria"),
    Cena("Afaria")
}

enum class TableAvailability {
    Libre,
    Ocupada,
    Reservada
}

data class TableUiModel(
    val id: Int,
    val numberLabel: String,
    val zone: String,
    val maxComensales: Int,
    val ocupadas: Int?,
    val availability: TableAvailability,
    val hasKitchenAlert: Boolean,
    val erreserbaId: Int?
)

data class TableSectionUiModel(
    val name: String,
    val tables: List<TableUiModel>
)

data class HomeUiState(
    val selectedDateMillis: Long,
    val selectedShift: Shift,
    val selectedSlotStartMinutes: Int,
    val sections: List<TableSectionUiModel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val debug: String? = null
)

class HomeViewModel(private val sessionManager: SessionManager) : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.10.5:5000/api"
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val apiDateTimeFormatter =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    private val apiDateTimeParserWithOffset =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    private val apiDateTimeParserNoOffset =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

    val userEmail =
        sessionManager.userEmail.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )

    val userName =
        sessionManager.userName.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )

    private val _uiState = MutableStateFlow(defaultState())
    val uiState: StateFlow<HomeUiState> = _uiState
    private val arrivedReservationIds = HashSet<Int>()

    private fun defaultState(): HomeUiState {
        val now = Date()
        val hour = SimpleDateFormat("H", Locale.US).format(now).toIntOrNull() ?: 0
        val shift = if (hour < 16) Shift.Comida else Shift.Cena
        val slot = defaultSlotMinutesFor(shift, now)
        return HomeUiState(
            selectedDateMillis = now.time,
            selectedShift = shift,
            selectedSlotStartMinutes = slot
        )
    }

    fun setSelectedDate(dateMillis: Long) {
        val current = _uiState.value
        if (current.selectedDateMillis == dateMillis) return
        val slot = defaultSlotMinutesFor(current.selectedShift, Date())
        _uiState.value = current.copy(selectedDateMillis = dateMillis, selectedSlotStartMinutes = slot)
        refresh()
    }

    fun setSelectedShift(shift: Shift) {
        val current = _uiState.value
        if (current.selectedShift == shift) return
        val slot = defaultSlotMinutesFor(shift, Date())
        _uiState.value = current.copy(selectedShift = shift, selectedSlotStartMinutes = slot)
        refresh()
    }

    fun setSelectedSlotStartMinutes(minutes: Int) {
        val current = _uiState.value
        if (current.selectedSlotStartMinutes == minutes) return
        _uiState.value = current.copy(selectedSlotStartMinutes = minutes)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.value = current.copy(isLoading = true, error = null, debug = null)
            try {
                val dateMillis = _uiState.value.selectedDateMillis
                val shift = _uiState.value.selectedShift
                val slot = _uiState.value.selectedSlotStartMinutes

                val tablesFetch = withContext(Dispatchers.IO) { fetchMahaiak() }
                val erreserbak = withContext(Dispatchers.IO) { fetchErreserbak() }
                val tableState =
                    withContext(Dispatchers.IO) {
                        buildTableState(
                            tables = tablesFetch.tables,
                            erreserbak = erreserbak,
                            selectedDateMillis = dateMillis,
                            selectedShift = shift,
                            selectedSlotStartMinutes = slot,
                            arrivedReservationIds = arrivedReservationIds
                        )
                    }
                val sections = groupByZone(tableState.tables)

                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        sections = sections,
                        debug = tablesFetch.debug
                    )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun markReservedAsArrived(tableId: Int) {
        val current = _uiState.value
        val table =
            current.sections
                .asSequence()
                .flatMap { it.tables.asSequence() }
                .firstOrNull { it.id == tableId }
                ?: return

        if (table.availability != TableAvailability.Reservada) return
        val reservationId = table.erreserbaId ?: return
        if (!arrivedReservationIds.add(reservationId)) return

        val newSections =
            current.sections.map { section ->
                section.copy(
                    tables =
                        section.tables.map { t ->
                            if (t.id != tableId) t
                            else t.copy(availability = TableAvailability.Ocupada)
                        }
                )
            }
        _uiState.value = current.copy(sections = newSections)
    }

    fun createReservationNow(
        tableId: Int,
        guestCount: Int,
        slotStartMinutes: Int,
        onSuccess: (Int) -> Unit
    ) {
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.value = current.copy(isLoading = true, error = null, debug = null)
            try {
                val dateMillis = current.selectedDateMillis
                val shift = current.selectedShift
                val reservationId =
                    withContext(Dispatchers.IO) {
                        val existing = fetchErreserbak()
                        if (isSlotBlockedForTable(existing, dateMillis, tableId, slotStartMinutes)) {
                            throw IllegalStateException("Ordu horretan mahaia ez dago erabilgarri")
                        }
                        postReservationNow(
                            tableId = tableId,
                            guestCount = guestCount,
                            selectedDateMillis = dateMillis,
                            selectedShift = shift,
                            selectedSlotStartMinutes = slotStartMinutes
                        )
                    }
                _uiState.value = _uiState.value.copy(isLoading = false)
                refresh()
                onSuccess(reservationId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun isSlotBlockedForTable(
        reservations: List<ApiErreserba>,
        selectedDateMillis: Long,
        tableId: Int,
        slotStartMinutes: Int
    ): Boolean {
        val selectedYmd = dateFormatter.format(Date(selectedDateMillis))
        for (r in reservations) {
            if (r.ordainduta != 0) continue
            if (r.mahaiakId != tableId) continue
            val ymd = normalizeDateYmd(r.egunaOrdua) ?: continue
            if (ymd != selectedYmd) continue
            val startMillis = parseMillis(r.egunaOrdua) ?: continue
            val start = slotStartMinutesFromMillis(startMillis) ?: continue
            if (slotStartMinutes in start until (start + 90)) return true
        }
        return false
    }

    fun logout() {
        viewModelScope.launch { sessionManager.clearSession() }
    }

    private data class TablesFetchResult(val tables: List<ApiMahaia>, val debug: String)
    private data class ApiMahaia(
        val id: Int,
        val zenbakia: Int,
        val pertsonaMax: Int,
        val kokapena: String
    )

    private data class ApiErreserba(
        val id: Int,
        val egunaOrdua: String?,
        val mahaiakId: Int,
        val pertsonaKopurua: Int,
        val ordainduta: Int
    )

    private data class TableStateResult(
        val tables: List<TableUiModel>,
        val kitchenAlertTableIds: Set<Int>
    )

    private fun fetchMahaiak(): TablesFetchResult {
        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Mahaiak"
            try {
                val (code, body) = httpGet(url)
                if (code !in 200..299) {
                    lastError = "url=$url code=$code body=${body.take(250)}"
                    continue
                }

                val tables = parseMahaiak(body)
                val sample = tables.take(8).joinToString(separator = ", ") { "${it.zenbakia}:${it.pertsonaMax}" }
                val debug = "url=$url code=$code sample=[$sample] body=${body.take(250)}"
                return TablesFetchResult(tables = tables, debug = debug)
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan dira mahaiak kargatu ($lastError)")
    }

    private fun parseMahaiak(body: String): List<ApiMahaia> {
        val root = JSONTokener(body).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject ->
                    root.optJSONArray("data")
                        ?: root.optJSONArray("Data")
                        ?: root.optJSONArray("\$values")
                        ?: JSONArray()
                else -> JSONArray()
            }

        val result = ArrayList<ApiMahaia>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val zenbakia = obj.optInt("zenbakia", obj.optInt("Zenbakia", id))
            val pertsona = obj.optInt("pertsonaKopurua", obj.optInt("PertsonaKopurua", 0))
            val kokapena = obj.optString("kokapena", obj.optString("Kokapena", "")).trim()
            result.add(
                ApiMahaia(
                    id = id,
                    zenbakia = zenbakia,
                    pertsonaMax = pertsona,
                    kokapena = kokapena
                )
            )
        }
        return result.sortedBy { it.zenbakia }
    }

    private fun fetchErreserbak(): List<ApiErreserba> {
        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Erreserbak"
            try {
                val (code, body) = httpGet(url)
                if (code !in 200..299) {
                    lastError = "url=$url code=$code body=${body.take(250)}"
                    continue
                }
                return parseErreserbak(body)
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan dira erreserbak kargatu ($lastError)")
    }

    private fun parseErreserbak(body: String): List<ApiErreserba> {
        val root = JSONTokener(body).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject ->
                    root.optJSONArray("data")
                        ?: root.optJSONArray("Data")
                        ?: root.optJSONArray("\$values")
                        ?: JSONArray()
                else -> JSONArray()
            }

        val result = ArrayList<ApiErreserba>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val mahaiId = obj.optInt("mahaiakId", obj.optInt("MahaiakId", -1)).takeIf { it > 0 } ?: continue
            val erreserbaId = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val pertsonaKopurua = obj.optInt("pertsonaKopurua", obj.optInt("PertsonaKopurua", 0))
            val egunaOrdua = obj.optString("egunaOrdua", obj.optString("EgunaOrdua", "")).trim().ifBlank { null }
            val ordainduta = obj.optInt("ordainduta", obj.optInt("Ordainduta", 0))

            result.add(
                ApiErreserba(
                    id = erreserbaId,
                    egunaOrdua = egunaOrdua,
                    mahaiakId = mahaiId,
                    pertsonaKopurua = pertsonaKopurua,
                    ordainduta = ordainduta
                )
            )
        }
        return result
    }

    private fun buildTableState(
        tables: List<ApiMahaia>,
        erreserbak: List<ApiErreserba>,
        selectedDateMillis: Long,
        selectedShift: Shift,
        selectedSlotStartMinutes: Int,
        arrivedReservationIds: Set<Int>
    ): TableStateResult {
        val selectedYmd = dateFormatter.format(Date(selectedDateMillis))
        val now = Date()
        val todayYmd = dateFormatter.format(now)
        val nowMillis = now.time

        val relevant =
            erreserbak
                .filter { it.ordainduta == 0 }
                .mapNotNull { e ->
                    val ymd = normalizeDateYmd(e.egunaOrdua) ?: return@mapNotNull null
                    if (ymd != selectedYmd) return@mapNotNull null
                    val startMillis = parseMillis(e.egunaOrdua) ?: 0L
                    val slot = slotStartMinutesFromMillis(startMillis) ?: return@mapNotNull null
                    val shift = shiftFromSlotMinutes(slot) ?: return@mapNotNull null
                    if (shift != selectedShift) return@mapNotNull null
                    if (selectedSlotStartMinutes !in slot until (slot + 90)) return@mapNotNull null
                    Triple(e, ymd, startMillis)
                }

        val byTableId = HashMap<Int, Pair<ApiErreserba, Long>>(relevant.size)
        for ((e, _, startMillis) in relevant) {
            val existing = byTableId[e.mahaiakId]
            if (existing == null || startMillis > existing.second) {
                byTableId[e.mahaiakId] = e to startMillis
            }
        }

        val kitchenAlertTableIds = HashSet<Int>()
        val out = ArrayList<TableUiModel>(tables.size)
        for (t in tables) {
            val zone = t.kokapena.ifBlank { "Zonarik gabe" }
            val occ = byTableId[t.id]
            if (occ == null) {
                out.add(
                    TableUiModel(
                        id = t.id,
                        numberLabel = t.zenbakia.toString(),
                        zone = zone,
                        maxComensales = t.pertsonaMax,
                        ocupadas = null,
                        availability = TableAvailability.Libre,
                        hasKitchenAlert = false,
                        erreserbaId = null
                    )
                )
                continue
            }

            val (e, startMillis) = occ
            val isFutureSelected = selectedYmd > todayYmd
            val isToday = selectedYmd == todayYmd
            val reserved = isFutureSelected || (isToday && startMillis > nowMillis) || (!isFutureSelected && !isToday)
            val availability =
                if (reserved && !arrivedReservationIds.contains(e.id)) {
                    TableAvailability.Reservada
                } else {
                    TableAvailability.Ocupada
                }

            val hasAlert =
                if (availability == TableAvailability.Ocupada && e.id > 0) {
                    runCatching { hasKitchenReadyForErreserba(e.id) }.getOrDefault(false)
                } else {
                    false
                }
            if (hasAlert) kitchenAlertTableIds.add(t.id)

            out.add(
                TableUiModel(
                    id = t.id,
                    numberLabel = t.zenbakia.toString(),
                    zone = zone,
                    maxComensales = t.pertsonaMax,
                    ocupadas = e.pertsonaKopurua,
                    availability = availability,
                    hasKitchenAlert = hasAlert,
                    erreserbaId = e.id
                )
            )
        }

        return TableStateResult(
            tables = out.sortedWith(compareBy<TableUiModel> { it.zone.lowercase() }.thenBy { it.numberLabel.toIntOrNull() ?: Int.MAX_VALUE }),
            kitchenAlertTableIds = kitchenAlertTableIds
        )
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

    private fun hasKitchenReadyForErreserba(erreserbaId: Int): Boolean {
        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Eskariak/erreserba/$erreserbaId"
            try {
                val (code, body) = httpGet(url)
                if (code !in 200..299) {
                    lastError = "url=$url code=$code body=${body.take(200)}"
                    continue
                }
                val root = JSONTokener(body).nextValue()
                val array =
                    when (root) {
                        is JSONArray -> root
                        is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("result") ?: root.optJSONArray("\$values") ?: JSONArray()
                        else -> JSONArray()
                    }
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val egoera = obj.optString("egoera", obj.optString("Egoera", "")).trim().lowercase()
                    if (egoera == "prest" || egoera.contains("prest")) return true
                    if (egoera == "egina" || egoera.contains("egina")) return true
                }
                return false
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan da eskariak kargatu ($lastError)")
    }

    private suspend fun postReservationNow(
        tableId: Int,
        guestCount: Int,
        selectedDateMillis: Long,
        selectedShift: Shift,
        selectedSlotStartMinutes: Int
    ): Int {
        val now = Date()
        val langileaId = sessionManager.userId.first() ?: 0
        val name = "Local"
        val targetIso =
            apiDateTimeFormatter.format(
                reservationDateTimeFromSlot(selectedDateMillis, selectedShift, selectedSlotStartMinutes, now)
            )

        val dto =
            JSONObject()
                .put("BezeroIzena", name)
                .put("Telefonoa", "")
                .put("PertsonaKopurua", guestCount)
                .put("EgunaOrdua", targetIso)
                .put("PrezioTotala", 0.0)
                .put("FakturaRuta", "")
                .put("LangileaId", langileaId)
                .put("MahaiakId", tableId)

        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Erreserbak"
            try {
                val (code, body) = httpPostJson(url, dto)
                if (code !in 200..299) {
                    lastError = "url=$url code=$code body=${body.take(250)}"
                    continue
                }
                val obj = (runCatching { JSONTokener(body).nextValue() }.getOrNull() as? JSONObject) ?: JSONObject()
                val id = obj.optInt("erreserbaId", obj.optInt("ErreserbaId", obj.optInt("id", obj.optInt("Id", -1))))
                if (id > 0) return id
                lastError = "url=$url code=$code body=${body.take(250)}"
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan da erreserba sortu ($lastError)")
    }

    private fun reservationDateTimeFromSlot(
        selectedDateMillis: Long,
        selectedShift: Shift,
        selectedSlotStartMinutes: Int,
        now: Date
    ): Date {
        val slotShift = shiftFromSlotMinutes(selectedSlotStartMinutes)
        val safeSlot =
            if (slotShift == selectedShift) {
                selectedSlotStartMinutes
            } else {
                defaultSlotMinutesFor(selectedShift, now)
            }

        val cal =
            Calendar.getInstance(TimeZone.getDefault()).apply {
                timeInMillis = selectedDateMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, safeSlot)
            }
        return cal.time
    }

    private fun defaultSlotMinutesFor(shift: Shift, now: Date): Int {
        val nowCal = Calendar.getInstance(TimeZone.getDefault()).apply { time = now }
        val nowMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)

        val (start, end) =
            when (shift) {
                Shift.Comida -> 13 * 60 to 16 * 60
                Shift.Cena -> 19 * 60 to 23 * 60
            }

        val clamped = nowMinutes.coerceIn(start, end - 1)
        return ((clamped - start) / 30) * 30 + start
    }

    private fun slotStartMinutesFromMillis(millis: Long): Int? {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = millis }
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val slot = (minutes / 30) * 30
        return slot.takeIf { shiftFromSlotMinutes(it) != null }
    }

    private fun shiftFromSlotMinutes(slotStartMinutes: Int): Shift? {
        return when (slotStartMinutes) {
            in (13 * 60) until (16 * 60) -> Shift.Comida
            in (19 * 60) until (23 * 60) -> Shift.Cena
            else -> null
        }
    }

    private fun normalizeDateYmd(iso: String?): String? {
        val raw = iso?.trim().orEmpty()
        if (raw.length >= 10 && raw[4] == '-' && raw[7] == '-') return raw.substring(0, 10)
        return null
    }

    private fun extractHour(iso: String?): Int? {
        val raw = iso?.trim().orEmpty()
        if (raw.length >= 13) {
            val h = raw.substring(11, 13).toIntOrNull()
            if (h != null) return h
        }
        return null
    }

    private fun parseMillis(iso: String?): Long? {
        val raw = iso?.trim().orEmpty()
        if (raw.isBlank()) return null
        val normalized =
            raw.replace("Z", "+00:00").let { text ->
                val dotIndex = text.indexOf('.')
                if (dotIndex < 0) return@let text
                val plus = text.indexOf('+', dotIndex).takeIf { it > 0 }
                val minus = text.indexOf('-', dotIndex).takeIf { it > 0 }
                val offsetIndex = plus ?: minus
                if (offsetIndex != null) {
                    text.substring(0, dotIndex) + text.substring(offsetIndex)
                } else {
                    text.substring(0, dotIndex)
                }
            }

        return runCatching { apiDateTimeParserWithOffset.parse(normalized)?.time }.getOrNull()
            ?: runCatching { apiDateTimeParserNoOffset.parse(normalized)?.time }.getOrNull()
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

    private fun httpPostJson(url: String, bodyJson: JSONObject): Pair<Int, String> {
        val conn =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }

        conn.outputStream.use { os ->
            os.write(bodyJson.toString().toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to body
    }

    private fun apiBaseUrlCandidates(): List<String> {
        val base = apiBaseUrlLanPrimary.trimEnd('/')
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
}
