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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ReservationUiModel(
    val id: Int,
    val bezeroIzena: String,
    val telefonoa: String,
    val pertsonaKopurua: Int,
    val egunaOrduaRaw: String,
    val egunaOrduaMillis: Long,
    val mahaiakId: Int,
    val langileaId: Int,
    val ordainduta: Int
)

data class ReservationsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val year: Int,
    val month: Int,
    val selectedDateMillis: Long,
    val reservations: List<ReservationUiModel> = emptyList()
)

class ReservationsViewModel(private val sessionManager: SessionManager) : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://10.0.2.2:5101/api"
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

    private val _uiState = MutableStateFlow(defaultState())
    val uiState: StateFlow<ReservationsUiState> = _uiState

    private fun defaultState(): ReservationsUiState {
        val today = startOfDayMillis(Date().time)
        val cal = Calendar.getInstance()
        cal.timeInMillis = today
        return ReservationsUiState(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            selectedDateMillis = today
        )
    }

    fun refresh() {
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.value = current.copy(isLoading = true, error = null)
            try {
                val reservations = withContext(Dispatchers.IO) { fetchReservations() }
                _uiState.value = _uiState.value.copy(isLoading = false, reservations = reservations)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun setMonth(year: Int, month: Int) {
        val current = _uiState.value
        if (current.year == year && current.month == month) return
        val currentCal = Calendar.getInstance()
        currentCal.timeInMillis = current.selectedDateMillis

        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val max = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val clampedDay = minOf(currentCal.get(Calendar.DAY_OF_MONTH), max)
        cal.set(Calendar.DAY_OF_MONTH, clampedDay)
        _uiState.value = current.copy(year = year, month = month, selectedDateMillis = startOfDayMillis(cal.timeInMillis))
    }

    fun selectDate(dateMillis: Long) {
        val current = _uiState.value
        val normalized = startOfDayMillis(dateMillis)
        if (current.selectedDateMillis == normalized) return
        val cal = Calendar.getInstance()
        cal.timeInMillis = normalized
        _uiState.value =
            current.copy(
                year = cal.get(Calendar.YEAR),
                month = cal.get(Calendar.MONTH) + 1,
                selectedDateMillis = normalized
            )
    }

    fun updateReservation(
        reservation: ReservationUiModel,
        newMahaiakId: Int,
        newGuests: Int,
        newDateMillis: Long,
        newHour: Int,
        newMinute: Int
    ) {
        viewModelScope.launch {
            val current = _uiState.value
            _uiState.value = current.copy(isLoading = true, error = null)
            try {
                withContext(Dispatchers.IO) {
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = startOfDayMillis(newDateMillis)
                    cal.set(Calendar.HOUR_OF_DAY, newHour)
                    cal.set(Calendar.MINUTE, newMinute)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    putReservation(
                        id = reservation.id,
                        bezeroIzena = reservation.bezeroIzena,
                        telefonoa = reservation.telefonoa,
                        pertsonaKopurua = newGuests,
                        egunaOrduaMillis = cal.timeInMillis,
                        mahaiakId = newMahaiakId,
                        langileaId = reservation.langileaId
                    )
                }
                val reservations = withContext(Dispatchers.IO) { fetchReservations() }
                _uiState.value = _uiState.value.copy(isLoading = false, reservations = reservations)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun fetchReservations(): List<ReservationUiModel> {
        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Erreserbak"
            try {
                val (code, body) = httpGet(url)
                if (code !in 200..299) {
                    lastError = "url=$url code=$code body=${body.take(250)}"
                    continue
                }
                return parseReservations(body)
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("No se pudieron cargar las reservas ($lastError)")
    }

    private fun parseReservations(body: String): List<ReservationUiModel> {
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

        val result = ArrayList<ReservationUiModel>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val egunaOrduaRaw = obj.optString("egunaOrdua", obj.optString("EgunaOrdua", "")).trim()
            val millis = parseMillis(egunaOrduaRaw) ?: continue
            val langileaId = obj.optInt("langileaId", obj.optInt("LangileaId", 0))
            val mahaiakId = obj.optInt("mahaiakId", obj.optInt("MahaiakId", 0))
            val ordainduta = obj.optInt("ordainduta", obj.optInt("Ordainduta", 0))
            val bezeroIzena = obj.optString("bezeroIzena", obj.optString("BezeroIzena", "")).trim()
            val telefonoa = obj.optString("telefonoa", obj.optString("Telefonoa", "")).trim()
            val pertsonaKopurua = obj.optInt("pertsonaKopurua", obj.optInt("PertsonaKopurua", 0))
            result.add(
                ReservationUiModel(
                    id = id,
                    bezeroIzena = bezeroIzena,
                    telefonoa = telefonoa,
                    pertsonaKopurua = pertsonaKopurua,
                    egunaOrduaRaw = egunaOrduaRaw,
                    egunaOrduaMillis = millis,
                    mahaiakId = mahaiakId,
                    langileaId = langileaId,
                    ordainduta = ordainduta
                )
            )
        }
        return result.sortedByDescending { it.egunaOrduaMillis }
    }

    private fun parseMillis(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val normalized =
            text.replace("Z", "+00:00").let { value ->
                val dotIndex = value.indexOf('.')
                if (dotIndex < 0) return@let value
                val plus = value.indexOf('+', dotIndex).takeIf { it > 0 }
                val minus = value.indexOf('-', dotIndex).takeIf { it > 0 }
                val offsetIndex = plus ?: minus
                if (offsetIndex != null) {
                    value.substring(0, dotIndex) + value.substring(offsetIndex)
                } else {
                    value.substring(0, dotIndex)
                }
            }
        return runCatching { apiDateTimeParserWithOffset.parse(normalized)?.time }.getOrNull()
            ?: runCatching { apiDateTimeParserNoOffset.parse(normalized)?.time }.getOrNull()
    }

    private fun putReservation(
        id: Int,
        bezeroIzena: String,
        telefonoa: String,
        pertsonaKopurua: Int,
        egunaOrduaMillis: Long,
        mahaiakId: Int,
        langileaId: Int
    ) {
        val dto =
            JSONObject()
                .put("BezeroIzena", bezeroIzena)
                .put("Telefonoa", telefonoa)
                .put("PertsonaKopurua", pertsonaKopurua)
                .put("EgunaOrdua", apiDateTimeFormatter.format(Date(egunaOrduaMillis)))
                .put("PrezioTotala", 0.0)
                .put("FakturaRuta", "")
                .put("LangileaId", langileaId)
                .put("MahaiakId", mahaiakId)

        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Erreserbak/$id"
            try {
                val (code, body) = httpPutJson(url, dto)
                if (code in 200..299) return
                lastError = "url=$url code=$code body=${body.take(250)}"
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("No se pudo editar la reserva ($lastError)")
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

    private fun httpPutJson(url: String, bodyJson: JSONObject): Pair<Int, String> {
        val conn =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
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
            "http://10.0.2.2:5101/api",
            "http://172.16.238.14:5101/api"
        ).distinct()
    }
}
