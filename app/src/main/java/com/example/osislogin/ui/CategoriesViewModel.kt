package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.osislogin.util.SessionManager
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class Category(
    val id: Int,
    val name: String
)

data class ConsumptionLine(val name: String, val qty: Int)

data class CategoriesUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val tableLabel: String? = null,
    val guestCount: Int? = null,
    val erreserbaId: Int? = null,
    val categories: List<Category> = emptyList(),
    val isClosePreviewLoading: Boolean = false,
    val closePreviewLines: List<ConsumptionLine> = emptyList(),
    val closePreviewTotal: Double? = null
)

class CategoriesViewModel(private val sessionManager: SessionManager) : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://10.0.2.2:5101/api"

    private val _uiState = MutableStateFlow(CategoriesUiState())
    val uiState: StateFlow<CategoriesUiState> = _uiState

    fun load(tableId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val tableInfo = withContext(Dispatchers.IO) { fetchTableInfo(tableId) }
                val erreserbaId = withContext(Dispatchers.IO) { ensureErreserba(tableId, tableInfo.second) }
                val categories = withContext(Dispatchers.IO) { fetchCategories() }
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        tableLabel = tableInfo.first,
                        guestCount = tableInfo.second,
                        erreserbaId = erreserbaId,
                        categories = categories
                    )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun loadClosePreview(erreserbaId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isClosePreviewLoading = true, closePreviewLines = emptyList(), closePreviewTotal = null, error = null)
            try {
                val (lines, total) = withContext(Dispatchers.IO) { fetchClosePreview(erreserbaId) }
                _uiState.value = _uiState.value.copy(isClosePreviewLoading = false, closePreviewLines = lines, closePreviewTotal = total)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isClosePreviewLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun closeErreserba(erreserbaId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val (_, total) = withContext(Dispatchers.IO) { fetchClosePreview(erreserbaId) }
                val langileaId = sessionManager.userId.first() ?: 0
                withContext(Dispatchers.IO) { postOrdaindu(erreserbaId, total, langileaId) }
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun fetchTableInfo(tableId: Int): Pair<String?, Int?> {
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Mahaiak"
            val (code, body) = httpGet(url)
            if (code !in 200..299) continue
            val root = JSONTokener(body).nextValue()
            val array =
                when (root) {
                    is JSONArray -> root
                    is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("\$values") ?: JSONArray()
                    else -> JSONArray()
                }
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optInt("id", obj.optInt("Id", -1))
                if (id != tableId) continue
                val label = obj.optInt("zenbakia", obj.optInt("Zenbakia", tableId)).toString()
                val guests = obj.optInt("pertsonaKopurua", obj.optInt("PertsonaKopurua", 0))
                return label to guests
            }
        }
        return tableId.toString() to null
    }

    private suspend fun ensureErreserba(tableId: Int, fallbackGuests: Int?): Int {
        val existing = findOpenErreserba(tableId)
        if (existing != null) return existing

        val langileaId = sessionManager.userId.first() ?: 0
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val dto =
            JSONObject()
                .put("BezeroIzena", "")
                .put("Telefonoa", "")
                .put("PertsonaKopurua", fallbackGuests ?: 0)
                .put("EgunaOrdua", now)
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
                val id =
                    obj.optInt("erreserbaId", obj.optInt("ErreserbaId", obj.optInt("id", obj.optInt("Id", -1))))
                if (id > 0) return id
                lastError = "url=$url code=$code body=${body.take(250)}"
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan da erreserba sortu ($lastError)")
    }

    private fun findOpenErreserba(tableId: Int): Int? {
        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Erreserbak"
            try {
                val (code, body) = httpGet(url)
                if (code !in 200..299) {
                    lastError = "url=$url code=$code body=${body.take(250)}"
                    continue
                }
                val root = JSONTokener(body).nextValue()
                val array =
                    when (root) {
                        is JSONArray -> root
                        is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("\$values") ?: JSONArray()
                        else -> JSONArray()
                    }
                var bestId: Int? = null
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val ordainduta = obj.optInt("ordainduta", obj.optInt("Ordainduta", 0))
                    if (ordainduta != 0) continue
                    val mahaiakId = obj.optInt("mahaiakId", obj.optInt("MahaiakId", -1))
                    if (mahaiakId != tableId) continue
                    val id = obj.optInt("id", obj.optInt("Id", -1))
                    if (id > 0 && (bestId == null || id > bestId)) bestId = id
                }
                if (bestId != null) return bestId
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        return null
    }

    private data class EskariaProduktuaLite(val produktuaId: Int, val izena: String, val kantitatea: Int, val prezioa: Double)

    private fun fetchClosePreview(erreserbaId: Int): Pair<List<ConsumptionLine>, Double> {
        val items = fetchEskariakItems(erreserbaId)
        val totals = items.groupBy { it.produktuaId }.mapValues { (_, list) -> list.sumOf { it.kantitatea } }
        val names = items.associateBy({ it.produktuaId }, { it.izena })
        val prices = items.associateBy({ it.produktuaId }, { it.prezioa })
        val lines =
            totals.entries
                .map { (id, qty) -> ConsumptionLine(name = names[id].orEmpty().ifBlank { id.toString() }, qty = qty) }
                .sortedBy { it.name.lowercase() }

        val subtotal = totals.entries.sumOf { (id, qty) -> (prices[id] ?: 0.0) * qty }
        val total = subtotal * 1.1
        return lines to total
    }

    private fun fetchEskariakItems(erreserbaId: Int): List<EskariaProduktuaLite> {
        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Eskariak/erreserba/$erreserbaId"
            try {
                val (code, body) = httpGet(url)
                if (code !in 200..299) {
                    lastError = "url=$url code=$code body=${body.take(250)}"
                    continue
                }
                val root = JSONTokener(body).nextValue()
                val array =
                    when (root) {
                        is JSONArray -> root
                        is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("\$values") ?: JSONArray()
                        else -> JSONArray()
                    }
                val result = ArrayList<EskariaProduktuaLite>()
                for (i in 0 until array.length()) {
                    val eskaria = array.optJSONObject(i) ?: continue
                    val produktuak = eskaria.optJSONArray("Produktuak") ?: eskaria.optJSONArray("produktuak") ?: eskaria.optJSONArray("\$values") ?: JSONArray()
                    for (j in 0 until produktuak.length()) {
                        val p = produktuak.optJSONObject(j) ?: continue
                        val pid = p.optInt("produktuaId", p.optInt("ProduktuaId", -1)).takeIf { it > 0 } ?: continue
                        val izena = p.optString("produktuaIzena", p.optString("ProduktuaIzena", pid.toString())).trim()
                        val qty = p.optInt("kantitatea", p.optInt("Kantitatea", 0))
                        val price = p.optDouble("prezioa", p.optDouble("Prezioa", 0.0))
                        if (qty > 0) result.add(EskariaProduktuaLite(produktuaId = pid, izena = izena, kantitatea = qty, prezioa = price))
                    }
                }
                return result
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        return emptyList()
    }

    private fun fetchCategories(): List<Category> {
        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Kategoriak"
            try {
                val (code, body) = httpGet(url)
                if (code !in 200..299) {
                    lastError = "url=$url code=$code body=${body.take(250)}"
                    continue
                }
                val root = JSONTokener(body).nextValue()
                val array =
                    when (root) {
                        is JSONArray -> root
                        is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("\$values") ?: JSONArray()
                        else -> JSONArray()
                    }

                val cats =
                    (0 until array.length())
                        .mapNotNull { i ->
                            val obj = array.optJSONObject(i) ?: return@mapNotNull null
                            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: return@mapNotNull null
                            val name = obj.optString("izena", obj.optString("Izena", "")).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            Category(id = id, name = name)
                        }

                return cats.sortedWith(compareBy<Category> { motaOrderKey(it.name) }.thenBy { it.name.lowercase() })
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan dira kategoriak kargatu ($lastError)")
    }

    private fun motaOrderKey(mota: String): Int {
        val lower = mota.lowercase()
        return when {
            lower.contains("prim") || lower.contains("lehen") -> 0
            lower.contains("segu") || lower.contains("big") -> 1
            lower.contains("post") -> 2
            lower.contains("bebi") || lower.contains("edari") -> 3
            else -> 99
        }
    }

    private fun postOrdaindu(erreserbaId: Int, guztira: Double, langileaId: Int) {
        val dto =
            JSONObject()
                .put("ErreserbaId", erreserbaId)
                .put("Guztira", guztira)
                .put("Jasotakoa", guztira)
                .put("Itzulia", 0.0)
                .put("LangileaId", langileaId)
                .put("OrdainketaModua", "Txartela")

        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Erreserbak/ordaindu"
            try {
                val (code, body) = httpPostJson(url, dto)
                if (code in 200..299) return
                lastError = "url=$url code=$code body=${body.take(250)}"
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan da erreserba ordaindu ($lastError)")
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
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }
        conn.outputStream.use { os -> os.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }
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
