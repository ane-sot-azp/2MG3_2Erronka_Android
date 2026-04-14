package com.example.osislogin.ui

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

data class Produktua(
    val id: Int,
    val name: String,
    val price: Double,
    val stock: Int,
    val mota: String
)

data class PlaterakUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val tableLabel: String? = null,
    val guestCount: Int? = null,
    val erreserbaId: Int = 0,
    val kategoriKey: String = "",
    val produktuak: List<Produktua> = emptyList(),
    val pendingQtyByProduktuaId: Map<Int, Int> = emptyMap(),
    val orderedQtyByProduktuaId: Map<Int, Int> = emptyMap(),
    val editableOrderedQtyByProduktuaId: Map<Int, Int> = emptyMap()
)

class PlaterakViewModel : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.10.5:5000/api"

    private val _uiState = MutableStateFlow(PlaterakUiState())
    val uiState: StateFlow<PlaterakUiState> = _uiState

    private data class ApiEskariaProduktuaLite(val produktuaId: Int, val kantitatea: Int, val prezioa: Double)
    private data class ApiEskariaLite(val id: Int, val erreserbaId: Int, val egoera: String?, val produktuak: List<ApiEskariaProduktuaLite>)

    fun load(tableId: Int, erreserbaId: Int, kategoriKey: String) {
        viewModelScope.launch {
            _uiState.value =
                _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    erreserbaId = erreserbaId,
                    kategoriKey = kategoriKey
                )
            try {
                val tableInfo = withContext(Dispatchers.IO) { fetchTableInfo(tableId) }
                val produktuak = withContext(Dispatchers.IO) { fetchProduktuakByMota(kategoriKey) }
                val (ordered, editable) =
                    withContext(Dispatchers.IO) { fetchEskariakQuantities(erreserbaId) }
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        tableLabel = tableInfo.first,
                        guestCount = tableInfo.second,
                        produktuak = produktuak,
                        pendingQtyByProduktuaId = emptyMap(),
                        orderedQtyByProduktuaId = ordered,
                        editableOrderedQtyByProduktuaId = editable
                    )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun changeQuantity(produktuaId: Int, delta: Int) {
        if (delta > 0) {
            val current = _uiState.value.pendingQtyByProduktuaId[produktuaId] ?: 0
            val produktua = _uiState.value.produktuak.firstOrNull { it.id == produktuaId } ?: return
            val next = (current + delta).coerceIn(0, produktua.stock)
            val map = _uiState.value.pendingQtyByProduktuaId.toMutableMap()
            if (next == 0) map.remove(produktuaId) else map[produktuaId] = next
            _uiState.value = _uiState.value.copy(pendingQtyByProduktuaId = map, error = null)
            return
        }

        val pendingCurrent = _uiState.value.pendingQtyByProduktuaId[produktuaId] ?: 0
        if (pendingCurrent > 0) {
            val next = (pendingCurrent - 1).coerceAtLeast(0)
            val map = _uiState.value.pendingQtyByProduktuaId.toMutableMap()
            if (next == 0) map.remove(produktuaId) else map[produktuaId] = next
            _uiState.value = _uiState.value.copy(pendingQtyByProduktuaId = map, error = null)
            return
        }

        val editableOrdered = _uiState.value.editableOrderedQtyByProduktuaId[produktuaId] ?: 0
        if (editableOrdered <= 0) return

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                withContext(Dispatchers.IO) {
                    decrementFromExistingEskaria(erreserbaId = _uiState.value.erreserbaId, produktuaId = produktuaId)
                }
                val (ordered, editable) =
                    withContext(Dispatchers.IO) { fetchEskariakQuantities(_uiState.value.erreserbaId) }
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        orderedQtyByProduktuaId = ordered,
                        editableOrderedQtyByProduktuaId = editable
                    )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun submitEskaria(onDone: () -> Unit) {
        val erreserbaId = _uiState.value.erreserbaId
        if (erreserbaId <= 0) {
            onDone()
            return
        }

        viewModelScope.launch {
            val pending = _uiState.value.pendingQtyByProduktuaId.filterValues { it > 0 }
            if (pending.isEmpty()) {
                onDone()
                return@launch
            }

            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                withContext(Dispatchers.IO) {
                    postEskaria(erreserbaId, pending)
                }
                val refreshed = withContext(Dispatchers.IO) { fetchProduktuakByMota(_uiState.value.kategoriKey) }
                val (ordered, editable) =
                    withContext(Dispatchers.IO) { fetchEskariakQuantities(erreserbaId) }
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = null,
                        produktuak = refreshed,
                        pendingQtyByProduktuaId = emptyMap(),
                        orderedQtyByProduktuaId = ordered,
                        editableOrderedQtyByProduktuaId = editable
                    )
                onDone()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun isEskariaLocked(egoera: String?): Boolean {
        val e = egoera?.trim().orEmpty().lowercase()
        return e == "prest" || e == "zerbitzatua"
    }

    private fun fetchEskariakByErreserba(erreserbaId: Int): List<ApiEskariaLite> {
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
                val out = ArrayList<ApiEskariaLite>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
                    val eId = obj.optInt("erreserbaId", obj.optInt("ErreserbaId", -1)).takeIf { it > 0 } ?: erreserbaId
                    val egoera = obj.optString("egoera", obj.optString("Egoera", "")).trim().ifBlank { null }
                    val prodsArr =
                        obj.optJSONArray("produktuak")
                            ?: obj.optJSONArray("Produktuak")
                            ?: obj.optJSONArray("data")
                            ?: obj.optJSONArray("result")
                            ?: obj.optJSONArray("\$values")
                            ?: JSONArray()

                    val produktuak = ArrayList<ApiEskariaProduktuaLite>(prodsArr.length())
                    for (j in 0 until prodsArr.length()) {
                        val pObj = prodsArr.optJSONObject(j) ?: continue
                        val pId = pObj.optInt("produktuaId", pObj.optInt("ProduktuaId", -1)).takeIf { it > 0 } ?: continue
                        val qty = pObj.optInt("kantitatea", pObj.optInt("Kantitatea", 0)).coerceAtLeast(0)
                        val price = pObj.optDouble("prezioa", pObj.optDouble("Prezioa", 0.0))
                        if (qty > 0) produktuak.add(ApiEskariaProduktuaLite(produktuaId = pId, kantitatea = qty, prezioa = price))
                    }

                    out.add(ApiEskariaLite(id = id, erreserbaId = eId, egoera = egoera, produktuak = produktuak))
                }
                return out
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan dira eskariak kargatu ($lastError)")
    }

    private fun fetchEskariakQuantities(erreserbaId: Int): Pair<Map<Int, Int>, Map<Int, Int>> {
        if (erreserbaId <= 0) return emptyMap<Int, Int>() to emptyMap()
        val eskariak = fetchEskariakByErreserba(erreserbaId)
        val total = HashMap<Int, Int>()
        val editable = HashMap<Int, Int>()
        for (e in eskariak) {
            val locked = isEskariaLocked(e.egoera)
            for (p in e.produktuak) {
                total[p.produktuaId] = (total[p.produktuaId] ?: 0) + p.kantitatea
                if (!locked) {
                    editable[p.produktuaId] = (editable[p.produktuaId] ?: 0) + p.kantitatea
                }
            }
        }
        return total to editable
    }

    private fun decrementFromExistingEskaria(erreserbaId: Int, produktuaId: Int) {
        if (erreserbaId <= 0) return
        val eskariak = fetchEskariakByErreserba(erreserbaId)
        val target =
            eskariak.firstOrNull { !isEskariaLocked(it.egoera) && it.produktuak.any { p -> p.produktuaId == produktuaId && p.kantitatea > 0 } }
                ?: return

        val updated = target.produktuak.mapNotNull { p ->
            if (p.produktuaId != produktuaId) return@mapNotNull p
            val nextQty = p.kantitatea - 1
            p.copy(kantitatea = nextQty.coerceAtLeast(0))
        }

        val newTotalPrice = updated.sumOf { it.prezioa * it.kantitatea.coerceAtLeast(0) }
        val produktuakArray = JSONArray()
        for (p in updated) {
            produktuakArray.put(
                JSONObject()
                    .put("ProduktuaId", p.produktuaId)
                    .put("Kantitatea", p.kantitatea)
                    .put("Prezioa", p.prezioa)
            )
        }

        val payload =
            JSONObject()
                .put("ErreserbaId", target.erreserbaId)
                .put("Prezioa", newTotalPrice)
                .put("Egoera", target.egoera ?: "Bidalita")
                .put("Produktuak", produktuakArray)

        var lastError: String? = null
        val putCandidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "${baseUrl.trimEnd('/')}/Eskariak/${target.id}",
                    "${baseUrl.trimEnd('/')}/eskariak/${target.id}"
                )
            }.distinct()

        for (url in putCandidates) {
            try {
                val (code, body) = httpPutJson(url, payload)
                if (code in 200..299) return
                lastError = "url=$url code=$code body=${body.take(250)}"
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        if (updated.all { it.kantitatea <= 0 }) {
            val deleteCandidates =
                apiBaseUrlCandidates().flatMap { baseUrl ->
                    listOf(
                        "${baseUrl.trimEnd('/')}/Eskariak/${target.id}",
                        "${baseUrl.trimEnd('/')}/eskariak/${target.id}"
                    )
                }.distinct()
            for (url in deleteCandidates) {
                try {
                    val (code, body) = httpDelete(url)
                    if (code in 200..299) return
                    lastError = "url=$url code=$code body=${body.take(250)}"
                } catch (e: Exception) {
                    lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        throw IllegalStateException("Ezin izan da eskaria eguneratu ($lastError)")
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

    private fun fetchProduktuakByMota(kategoriKey: String): List<Produktua> {
        val (motaIdByLowerName, motaNameById) = fetchMotakMaps()

        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Produktuak"
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

                val wanted = kategoriKey.trim()
                val wantedId = wanted.lowercase().takeIf { it.isNotBlank() }?.let { motaIdByLowerName[it] }
                val filtered =
                    (0 until array.length()).mapNotNull { i ->
                        val obj = array.optJSONObject(i) ?: return@mapNotNull null
                        val id = obj.optInt("Id", obj.optInt("id", -1)).takeIf { it > 0 } ?: return@mapNotNull null
                        val name = obj.optString("Izena", obj.optString("izena", id.toString())).trim()
                        val price = obj.optDouble("Prezioa", obj.optDouble("prezioa", 0.0))
                        val stock = obj.optInt("Stock", obj.optInt("stock", 0))
                        val motaId = obj.optInt("MotaId", obj.optInt("motaId", -1)).takeIf { it > 0 }
                        val motaName = motaId?.let { motaNameById[it] }.orEmpty()
                        val accepts =
                            when {
                                wanted.isBlank() -> true
                                wantedId != null && motaId != null -> motaId == wantedId
                                wantedId == null && motaName.isNotBlank() -> motaName.equals(wanted, ignoreCase = true)
                                else -> false
                            }
                        if (!accepts) return@mapNotNull null

                        Produktua(
                            id = id,
                            name = name,
                            price = price,
                            stock = stock,
                            mota = if (motaName.isNotBlank()) motaName else wanted
                        )
                    }

                return filtered.sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan dira produktuak kargatu ($lastError)")
    }

    private fun fetchMotakMaps(): Pair<Map<String, Int>, Map<Int, String>> {
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

                val idToName = HashMap<Int, String>(array.length())
                val lowerNameToId = HashMap<String, Int>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
                    val name = obj.optString("izena", obj.optString("Izena", "")).trim()
                    if (name.isBlank()) continue
                    idToName[id] = name
                    lowerNameToId[name.lowercase()] = id
                }
                return lowerNameToId to idToName
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        return emptyMap<String, Int>() to emptyMap()
    }

    private fun postEskaria(erreserbaId: Int, qtyByProductId: Map<Int, Int>) {
        val produktuakArray = JSONArray()
        var subtotal = 0.0
        for ((productId, qty) in qtyByProductId) {
            val produktua = _uiState.value.produktuak.firstOrNull { it.id == productId } ?: continue
            val unitPrice = produktua.price
            subtotal += unitPrice * qty
            produktuakArray.put(
                JSONObject()
                    .put("ProduktuaId", productId)
                    .put("Kantitatea", qty)
                    .put("Prezioa", unitPrice)
            )
        }

        val payload =
            JSONObject()
                .put("ErreserbaId", erreserbaId)
                .put("Prezioa", subtotal)
                .put("Egoera", "Bidalita")
                .put("Produktuak", produktuakArray)

        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Eskariak"
            try {
                val (code, body) = httpPostJson(url, payload)
                if (code in 200..299) return
                lastError = "url=$url code=$code body=${body.take(250)}"
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan da eskaria sortu ($lastError)")
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
