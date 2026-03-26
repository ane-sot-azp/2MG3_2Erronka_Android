package com.example.osiskitchen.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class KitchenPlatosStockUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val platos: List<KitchenPlatoStock> = emptyList(),
    val updatingIds: Set<Int> = emptySet()
)

data class KitchenPlatoStock(
    val id: Int,
    val izena: String,
    val kategoriaId: Int?,
    val kategoriaIzena: String?,
    val prezioa: Double,
    val stock: Int
)

class KitchenPlatosStockViewModel : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.10.55:5101/api"

    private val _uiState = MutableStateFlow(KitchenPlatosStockUiState())
    val uiState: StateFlow<KitchenPlatosStockUiState> = _uiState

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val platos = withContext(Dispatchers.IO) { fetchPlatos() }
                _uiState.value = _uiState.value.copy(isLoading = false, platos = platos)
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: e.javaClass.simpleName
                    )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun adjustStock(platoId: Int, delta: Int) {
        if (delta == 0) return
        val current = _uiState.value
        if (current.updatingIds.contains(platoId)) return

        val existing = current.platos.firstOrNull { it.id == platoId } ?: return
        val newStock = existing.stock + delta
        if (newStock < 0) return

        val optimistic =
            current.platos.map { if (it.id == platoId) it.copy(stock = newStock) else it }
        _uiState.value =
            current.copy(
                platos = optimistic,
                updatingIds = current.updatingIds + platoId,
                error = null
            )

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { putProduktuaStock(existing, newStock) }
                val after = _uiState.value
                _uiState.value = after.copy(updatingIds = after.updatingIds - platoId)
            } catch (e: Exception) {
                val after = _uiState.value
                val reverted =
                    after.platos.map { if (it.id == platoId) it.copy(stock = existing.stock) else it }
                _uiState.value =
                    after.copy(
                        platos = reverted,
                        updatingIds = after.updatingIds - platoId,
                        error = e.message ?: e.javaClass.simpleName
                    )
            }
        }
    }

    private fun fetchPlatos(): List<KitchenPlatoStock> {
        val kategoriaIzenaById = fetchKategoriaIzenaById()
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Produktuak",
                    "$baseUrl/produktuak"
                )
            }.distinct()

        var lastError: String? = null
        var body: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, text) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${text.take(200)}"
                    continue
                }
                body = text
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val finalBody = body ?: throw IllegalStateException("Ezin izan dira platerak kargatu ($lastError)")
        val root = JSONTokener(finalBody).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject ->
                    root.optJSONArray("produktuak")
                        ?: root.optJSONArray("Produktuak")
                        ?: root.optJSONArray("data")
                        ?: root.optJSONArray("result")
                        ?: root.optJSONArray("\$values")
                        ?: JSONArray()
                else -> JSONArray()
            }

        val result = ArrayList<KitchenPlatoStock>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val izena = obj.optString("izena", obj.optString("Izena", "")).trim().ifBlank { "Platera $id" }
            val stock = obj.optInt("stock", obj.optInt("Stock", 0))
            val prezioa = obj.optDouble("prezioa", obj.optDouble("Prezioa", 0.0))
            val kategoriaId = obj.optInt("motaId", obj.optInt("MotaId", -1)).takeIf { it > 0 }
            val kategoriaIzena = kategoriaId?.let { kategoriaIzenaById[it] }
            result.add(
                KitchenPlatoStock(
                    id = id,
                    izena = izena,
                    kategoriaId = kategoriaId,
                    kategoriaIzena = kategoriaIzena,
                    prezioa = prezioa,
                    stock = stock
                )
            )
        }
        return result.sortedWith(compareBy<KitchenPlatoStock> { it.kategoriaId ?: Int.MAX_VALUE }.thenBy { it.izena })
    }

    private fun putProduktuaStock(existing: KitchenPlatoStock, newStock: Int) {
        val platoId = existing.id
        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Produktuak/$platoId"
            try {
                val payload =
                    JSONObject()
                        .put("Id", existing.id)
                        .put("Izena", existing.izena)
                        .put("Prezioa", existing.prezioa)
                        .put("MotaId", existing.kategoriaId ?: 0)
                        .put("Stock", newStock)
                val (code, body) = httpPutJson(url, payload)
                if (code in 200..299) return
                lastError = "url=$url code=$code body=${body.take(200)}"
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        throw IllegalStateException("Ezin izan da stock-a eguneratu ($lastError)")
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

    private fun httpPutJson(url: String, jsonBody: JSONObject): Pair<Int, String> {
        val conn =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
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

    private fun fetchKategoriaIzenaById(): Map<Int, String> {
        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Kategoriak"
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
                        is JSONObject ->
                            root.optJSONArray("data")
                                ?: root.optJSONArray("result")
                                ?: root.optJSONArray("\$values")
                                ?: JSONArray()
                        else -> JSONArray()
                    }

                val map = HashMap<Int, String>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
                    val name = obj.optString("izena", obj.optString("Izena", "")).trim()
                    if (name.isNotBlank()) map[id] = name
                }
                return map
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        return emptyMap()
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
            "http://192.168.10.55:5101/api",
            "http://192.168.10.55:5101/api"
        ).distinct()
    }
}

data class KitchenIngredientesStockUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val ingredientes: List<KitchenIngredienteStock> = emptyList(),
    val updatingIds: Set<Int> = emptySet()
)

data class KitchenIngredienteStock(
    val id: Int,
    val izena: String,
    val stock: Int,
    val gutxienekoStock: Int,
    val eskatu: Boolean
)

class KitchenIngredientesStockViewModel : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.10.55:5101/api"

    private val _uiState = MutableStateFlow(KitchenIngredientesStockUiState())
    val uiState: StateFlow<KitchenIngredientesStockUiState> = _uiState

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val items = withContext(Dispatchers.IO) { fetchIngredientes() }
                _uiState.value = _uiState.value.copy(isLoading = false, ingredientes = items)
            } catch (e: Exception) {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: e.javaClass.simpleName
                    )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun adjustStock(ingredienteId: Int, delta: Int) {
        if (delta == 0) return
        val current = _uiState.value
        if (current.updatingIds.contains(ingredienteId)) return

        val existing = current.ingredientes.firstOrNull { it.id == ingredienteId } ?: return
        val newStock = existing.stock + delta
        if (newStock < 0) return

        val optimistic =
            current.ingredientes.map { if (it.id == ingredienteId) it.copy(stock = newStock) else it }
        _uiState.value =
            current.copy(
                ingredientes = optimistic,
                updatingIds = current.updatingIds + ingredienteId,
                error = null
            )

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { patchIngredienteStock(ingredienteId, delta) }
                val after = _uiState.value
                _uiState.value = after.copy(updatingIds = after.updatingIds - ingredienteId)
            } catch (e: Exception) {
                val after = _uiState.value
                val reverted =
                    after.ingredientes.map {
                        if (it.id == ingredienteId) it.copy(stock = existing.stock) else it
                    }
                _uiState.value =
                    after.copy(
                        ingredientes = reverted,
                        updatingIds = after.updatingIds - ingredienteId,
                        error = e.message ?: e.javaClass.simpleName
                    )
            }
        }
    }

    fun toggleEskatu(ingredienteId: Int) {
        val current = _uiState.value
        if (current.updatingIds.contains(ingredienteId)) return

        val existing = current.ingredientes.firstOrNull { it.id == ingredienteId } ?: return
        val optimistic =
            current.ingredientes.map {
                if (it.id == ingredienteId) it.copy(eskatu = !it.eskatu) else it
            }
        _uiState.value =
            current.copy(
                ingredientes = optimistic,
                updatingIds = current.updatingIds + ingredienteId,
                error = null
            )

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { patchIngredienteEskatu(ingredienteId) }
                val after = _uiState.value
                _uiState.value = after.copy(updatingIds = after.updatingIds - ingredienteId)
            } catch (e: Exception) {
                val after = _uiState.value
                val reverted =
                    after.ingredientes.map {
                        if (it.id == ingredienteId) it.copy(eskatu = existing.eskatu) else it
                    }
                _uiState.value =
                    after.copy(
                        ingredientes = reverted,
                        updatingIds = after.updatingIds - ingredienteId,
                        error = e.message ?: e.javaClass.simpleName
                    )
            }
        }
    }

    private fun fetchIngredientes(): List<KitchenIngredienteStock> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Osagaiak",
                    "$baseUrl/osagaiak"
                )
            }.distinct()

        var lastError: String? = null
        var body: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, text) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${text.take(200)}"
                    continue
                }
                body = text
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val finalBody = body ?: throw IllegalStateException("Ezin izan dira osagaiak kargatu ($lastError)")
        val root = JSONTokener(finalBody).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject ->
                    root.optJSONArray("osagaiak")
                        ?: root.optJSONArray("Osagaiak")
                        ?: root.optJSONArray("data")
                        ?: root.optJSONArray("result")
                        ?: JSONArray()
                else -> JSONArray()
            }

        val result = ArrayList<KitchenIngredienteStock>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val izena = obj.optString("izena", obj.optString("Izena", "")).trim().ifBlank { "Osagaia $id" }
            val stock = obj.optInt("stock", obj.optInt("Stock", 0))
            val gutxienekoStock = obj.optInt("gutxienekoStock", obj.optInt("GutxienekoStock", 0))
            val eskatu =
                when {
                    obj.has("eskatu") -> obj.optBoolean("eskatu", false)
                    obj.has("Eskatu") -> obj.optBoolean("Eskatu", false)
                    else -> false
                }
            result.add(
                KitchenIngredienteStock(
                    id = id,
                    izena = izena,
                    stock = stock,
                    gutxienekoStock = gutxienekoStock,
                    eskatu = eskatu
                )
            )
        }
        return result.sortedWith(compareBy<KitchenIngredienteStock> { it.eskatu.not() }.thenBy { it.stock > it.gutxienekoStock }.thenBy { it.izena })
    }

    private fun patchIngredienteStock(ingredienteId: Int, delta: Int) {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Osagaiak/$ingredienteId/stock",
                    "$baseUrl/osagaiak/$ingredienteId/stock"
                )
            }.distinct()

        var lastError: String? = null
        for (candidateUrl in candidates) {
            try {
                val url = URL(candidateUrl)
                val conn =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PATCH"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 15000
                    }

                val jsonBody = "{\"kopurua\":$delta}"
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code in 200..299) return
                val body = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        throw IllegalStateException("Ezin izan da stock-a eguneratu ($lastError)")
    }

    private fun patchIngredienteEskatu(ingredienteId: Int) {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Osagaiak/$ingredienteId/eskatu",
                    "$baseUrl/osagaiak/$ingredienteId/eskatu"
                )
            }.distinct()

        var lastError: String? = null
        for (candidateUrl in candidates) {
            try {
                val url = URL(candidateUrl)
                val conn =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PATCH"
                        setRequestProperty("Accept", "application/json")
                        connectTimeout = 15000
                        readTimeout = 15000
                    }
                val code = conn.responseCode
                if (code in 200..299) return
                val body = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        throw IllegalStateException("Ezin izan da 'eskatu' eguneratu ($lastError)")
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
            "http://192.168.10.55:5101/api",
            "http://192.168.10.55:5101/api"
        ).distinct()
    }
}
