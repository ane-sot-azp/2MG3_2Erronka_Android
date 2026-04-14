package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.osislogin.util.SessionManager
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class EskariaProductUiModel(
    val produktuaId: Int,
    val name: String,
    val qty: Int,
    val unitPrice: Double
)

data class EskariaUiModel(
    val id: Int,
    val egoera: String,
    val total: Double,
    val products: List<EskariaProductUiModel>
)

data class OrdersHistoryUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val tableLabel: String? = null,
    val guestCount: Int? = null,
    val erreserbaId: Int = 0,
    val orders: List<EskariaUiModel> = emptyList(),
    val totalWithVat: Double? = null
)

class OrdersHistoryViewModel(private val sessionManager: SessionManager) : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.10.5:5000/api"

    private val _uiState = MutableStateFlow(OrdersHistoryUiState())
    val uiState: StateFlow<OrdersHistoryUiState> = _uiState

    fun load(tableId: Int, erreserbaId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, erreserbaId = erreserbaId)
            try {
                val tableInfo = withContext(Dispatchers.IO) { fetchTableInfo(tableId) }
                val orders = withContext(Dispatchers.IO) { fetchEskariakByErreserba(erreserbaId) }
                val subtotal = orders.sumOf { it.products.sumOf { p -> p.unitPrice * p.qty } }
                val totalWithVat = subtotal * 1.1
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        tableLabel = tableInfo.first,
                        guestCount = tableInfo.second,
                        orders = orders.sortedByDescending { it.id },
                        totalWithVat = totalWithVat
                    )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelEskaria(eskariaId: Int) {
        val erreserbaId = _uiState.value.erreserbaId
        if (erreserbaId <= 0 || eskariaId <= 0) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                withContext(Dispatchers.IO) { deleteEskaria(eskariaId) }
                val orders = withContext(Dispatchers.IO) { fetchEskariakByErreserba(erreserbaId) }
                val subtotal = orders.sumOf { it.products.sumOf { p -> p.unitPrice * p.qty } }
                val totalWithVat = subtotal * 1.1
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        orders = orders.sortedByDescending { it.id },
                        totalWithVat = totalWithVat
                    )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun closeErreserbaAndPay() {
        val erreserbaId = _uiState.value.erreserbaId
        if (erreserbaId <= 0) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val orders = withContext(Dispatchers.IO) { fetchEskariakByErreserba(erreserbaId) }
                val subtotal = orders.sumOf { it.products.sumOf { p -> p.unitPrice * p.qty } }
                val totalWithVat = subtotal * 1.1
                val langileaId = sessionManager.userId.first() ?: 0
                withContext(Dispatchers.IO) { postOrdaindu(erreserbaId, totalWithVat, langileaId) }
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun isLocked(egoera: String?): Boolean {
        val e = egoera?.trim().orEmpty().lowercase()
        return e == "prest" || e == "zerbitzatua"
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

    private fun fetchEskariakByErreserba(erreserbaId: Int): List<EskariaUiModel> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "${baseUrl.trimEnd('/')}/Eskariak/erreserba/$erreserbaId",
                    "${baseUrl.trimEnd('/')}/eskariak/erreserba/$erreserbaId"
                )
            }.distinct()

        var lastError: String? = null
        for (url in candidates) {
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
                        is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("result") ?: root.optJSONArray("\$values") ?: JSONArray()
                        else -> JSONArray()
                    }

                val out = ArrayList<EskariaUiModel>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
                    val egoera = obj.optString("egoera", obj.optString("Egoera", "Bidalita")).trim().ifBlank { "Bidalita" }
                    val prezioa = obj.optDouble("prezioa", obj.optDouble("Prezioa", Double.NaN))
                    val prodsArr =
                        obj.optJSONArray("produktuak")
                            ?: obj.optJSONArray("Produktuak")
                            ?: obj.optJSONArray("data")
                            ?: obj.optJSONArray("result")
                            ?: obj.optJSONArray("\$values")
                            ?: JSONArray()

                    val products = ArrayList<EskariaProductUiModel>(prodsArr.length())
                    for (j in 0 until prodsArr.length()) {
                        val pObj = prodsArr.optJSONObject(j) ?: continue
                        val pId = pObj.optInt("produktuaId", pObj.optInt("ProduktuaId", -1)).takeIf { it > 0 } ?: continue
                        val qty = pObj.optInt("kantitatea", pObj.optInt("Kantitatea", 0)).coerceAtLeast(0)
                        val price = pObj.optDouble("prezioa", pObj.optDouble("Prezioa", 0.0))
                        val name = pObj.optString("produktuaIzena", pObj.optString("ProduktuaIzena", pId.toString())).trim()
                        if (qty > 0) {
                            products.add(EskariaProductUiModel(produktuaId = pId, name = name, qty = qty, unitPrice = price))
                        }
                    }

                    val computedTotal = products.sumOf { it.unitPrice * it.qty }
                    val total = if (prezioa.isNaN()) computedTotal else prezioa
                    out.add(EskariaUiModel(id = id, egoera = egoera, total = total, products = products))
                }
                return out
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan dira eskariak kargatu ($lastError)")
    }

    private fun deleteEskaria(eskariaId: Int) {
        var lastError: String? = null
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "${baseUrl.trimEnd('/')}/Eskariak/$eskariaId",
                    "${baseUrl.trimEnd('/')}/eskariak/$eskariaId"
                )
            }.distinct()
        for (url in candidates) {
            try {
                val (code, body) = httpDelete(url)
                if (code in 200..299) return
                lastError = "url=$url code=$code body=${body.take(250)}"
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan da eskaria ezeztatu ($lastError)")
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

    private fun httpDelete(url: String): Pair<Int, String> {
        val conn =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
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

