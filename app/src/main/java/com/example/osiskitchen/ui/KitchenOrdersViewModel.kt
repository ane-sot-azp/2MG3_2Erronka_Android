package com.example.osiskitchen.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class KitchenOrdersUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val debugInfo: String? = null,
    val groups: List<KitchenOrderGroup> = emptyList(),
    val updatingKomandaIds: Set<Int> = emptySet()
)

data class KitchenOrderGroup(
    val erreserbaId: Int,
    val txanda: String?,
    val customerName: String?,
    val personCount: Int?,
    val tablesLabel: String?,
    val fakturaId: Int?,
    val komandak: List<KitchenKomanda>
)

data class KitchenKomanda(
    val id: Int,
    val platerakId: Int?,
    val plateraIzena: String,
    val kategoriaId: Int?,
    val kategoriaIzena: String?,
    val kopurua: Int,
    val oharrak: String?,
    val egoera: Boolean
)

class KitchenOrdersViewModel : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.10.5:5000/api"
    private val apiTraceTag = "OSIS_KITCHEN_API"

    private val _uiState = MutableStateFlow(KitchenOrdersUiState())
    val uiState: StateFlow<KitchenOrdersUiState> = _uiState

    private data class PlateraInfo(
        val izena: String,
        val kategoriaId: Int?,
        val kategoriaIzena: String?
    )

    private val plateraInfoCache = LinkedHashMap<Int, PlateraInfo>()
    private val komandaEgoeraCache = LinkedHashMap<Int, Boolean>()
    private val komandaToEskariaId = HashMap<Int, Int>()

    private data class ApiMotaLite(
        val id: Int,
        val izena: String
    )

    private data class ApiProduktuaLite(
        val id: Int,
        val izena: String,
        val motaId: Int,
        val prezioa: Double
    )

    private data class ApiMahaiaLite(
        val id: Int,
        val zenbakia: Int
    )

    private data class ApiErreserbaLite(
        val id: Int,
        val egunaOrdua: String?,
        val bezeroIzena: String?,
        val pertsonaKopurua: Int?,
        val mahaiakId: Int,
        val ordainduta: Int
    )

    private data class ApiEskariaProduktuaLite(
        val produktuaId: Int,
        val kantitatea: Int,
        val prezioa: Double,
        val produktuaIzena: String?
    )

    private data class ApiEskariaLite(
        val id: Int,
        val erreserbaId: Int,
        val prezioa: Double,
        val egoera: String?,
        val produktuak: List<ApiEskariaProduktuaLite>
    )

    private data class LoadGroupsResult(
        val groups: List<KitchenOrderGroup>,
        val debugInfo: String
    )

    private class ApiTrace {
        private val sb = StringBuilder()
        fun add(text: String) {
            sb.append(text).append('\n')
        }

        fun text(): String = sb.toString()
    }

    private fun logLong(tag: String, message: String) {
        val max = 3500
        var i = 0
        while (i < message.length) {
            val end = (i + max).coerceAtMost(message.length)
            Log.d(tag, message.substring(i, end))
            i = end
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, debugInfo = null)
            val trace = ApiTrace()
            try {
                val result = withContext(Dispatchers.IO) { loadGroups(trace) }
                val combinedDebug = result.debugInfo + "\n\n" + trace.text()
                logLong(apiTraceTag, combinedDebug)
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        groups = result.groups,
                        debugInfo = combinedDebug
                    )
            } catch (e: Exception) {
                val combinedDebug = "api=$apiBaseUrlLanPrimary error=${e.message ?: e.javaClass.simpleName}\n\n" + trace.text()
                logLong(apiTraceTag, combinedDebug)
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: e.javaClass.simpleName,
                        debugInfo = combinedDebug
                    )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun setKomandaEgoera(komandaId: Int, egoera: Boolean) {
        val current = _uiState.value
        if (current.updatingKomandaIds.contains(komandaId)) return

        val eskariaId = komandaToEskariaId[komandaId]
        val komandaIdsToUpdate =
            if (eskariaId != null) {
                current.groups
                    .flatMap { it.komandak }
                    .filter { komandaToEskariaId[it.id] == eskariaId }
                    .map { it.id }
            } else {
                listOf(komandaId)
            }

        for (id in komandaIdsToUpdate) {
            komandaEgoeraCache[id] = egoera
        }
        while (komandaEgoeraCache.size > 800) {
            val firstKey = komandaEgoeraCache.keys.firstOrNull() ?: break
            komandaEgoeraCache.remove(firstKey)
        }

        val updatedGroups =
            current.groups.map { group ->
                group.copy(
                    komandak =
                        group.komandak.map { k ->
                            if (komandaIdsToUpdate.contains(k.id)) k.copy(egoera = egoera) else k
                        }
                )
            }

        _uiState.value =
            current.copy(
                groups = updatedGroups,
                updatingKomandaIds = current.updatingKomandaIds + komandaId,
                error = null
            )

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { patchKomandaEgoera(komandaId, egoera) }
                val after = _uiState.value
                _uiState.value =
                    after.copy(updatingKomandaIds = after.updatingKomandaIds - komandaId)
            } catch (e: Exception) {
                val after = _uiState.value
                for (id in komandaIdsToUpdate) {
                    komandaEgoeraCache[id] = !egoera
                }
                val revertedGroups =
                    after.groups.map { group ->
                        group.copy(
                            komandak =
                                group.komandak.map { k ->
                                    if (komandaIdsToUpdate.contains(k.id)) k.copy(egoera = !egoera) else k
                                }
                        )
                    }
                _uiState.value =
                    after.copy(
                        groups = revertedGroups,
                        updatingKomandaIds = after.updatingKomandaIds - komandaId,
                        error = e.message ?: e.javaClass.simpleName
                    )
            }
        }
    }

    private fun loadGroups(trace: ApiTrace): LoadGroupsResult {
        val todayYmd = todayYmd()
        val currentTxanda = currentTxanda()

        komandaToEskariaId.clear()

        val motak = fetchMotak(trace)
        val motaNameById = motak.associate { it.id to it.izena }

        val produktuak = fetchProduktuak(trace)
        val produktuaById = produktuak.associateBy { it.id }

        val mahaiak = fetchMahaiak(trace)
        val mahaiaZenbakiaById = mahaiak.associate { it.id to it.zenbakia }

        val erreserbak = fetchErreserbak(trace)
        val erreserbakOpenToday =
            erreserbak.filter { e ->
                e.ordainduta == 0 && (normalizeDateYmd(e.egunaOrdua) == todayYmd)
            }

        val groups = ArrayList<KitchenOrderGroup>(erreserbakOpenToday.size)
        var noEskariakCount = 0
        for (e in erreserbakOpenToday) {
            val eskariak = fetchEskariakByErreserba(e.id, trace)
            if (eskariak.isEmpty()) {
                noEskariakCount += 1
                continue
            }

            val komandak = ArrayList<KitchenKomanda>()
            for (eskaria in eskariak) {
                val isDone = isEskariaDone(eskaria.egoera)
                for (p in eskaria.produktuak) {
                    val produktua = produktuaById[p.produktuaId]
                    val motaId = produktua?.motaId
                    val motaIzena = motaId?.let { motaNameById[it] }
                    val komandaId = (eskaria.id * 100000) + p.produktuaId
                    komandaToEskariaId[komandaId] = eskaria.id
                    val cachedEgoera = komandaEgoeraCache[komandaId]
                    komandak.add(
                        KitchenKomanda(
                            id = komandaId,
                            platerakId = p.produktuaId,
                            plateraIzena = p.produktuaIzena ?: produktua?.izena ?: "Produktua ${p.produktuaId}",
                            kategoriaId = motaId,
                            kategoriaIzena = motaIzena,
                            kopurua = p.kantitatea,
                            oharrak = null,
                            egoera = cachedEgoera ?: isDone
                        )
                    )
                }
            }

            if (komandak.isEmpty()) {
                noEskariakCount += 1
                continue
            }

            val mahaiaZenbakia = mahaiaZenbakiaById[e.mahaiakId]
            val tablesLabel = mahaiaZenbakia?.let { "Mahai $it" } ?: "Mahai ${e.mahaiakId}"
            groups.add(
                KitchenOrderGroup(
                    erreserbaId = e.id,
                    txanda = currentTxanda,
                    customerName = e.bezeroIzena,
                    personCount = e.pertsonaKopurua,
                    tablesLabel = tablesLabel,
                    fakturaId = null,
                    komandak = komandak.sortedWith(compareBy<KitchenKomanda> { it.egoera }.thenBy { it.id })
                )
            )
        }

        val debugInfo =
            "api=$apiBaseUrlLanPrimary hoy=$todayYmd txanda=$currentTxanda reservasOpenToday=${erreserbakOpenToday.size} grupos=${groups.size} sinEskariak=$noEskariakCount"
        return LoadGroupsResult(
            groups = groups.sortedWith(compareBy<KitchenOrderGroup> { it.tablesLabel ?: "" }.thenBy { it.erreserbaId }),
            debugInfo = debugInfo
        )
    }

    private fun fetchMotak(trace: ApiTrace): List<ApiMotaLite> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Kategoriak",
                    "$baseUrl/kategoriak"
                )
            }.distinct()

        var lastError: String? = null
        var okBody: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, body) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                    continue
                }
                okBody = body
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val body = okBody ?: run {
            trace.add("MOTAK_FAILED $lastError")
            return emptyList()
        }

        val root = JSONTokener(body).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("result") ?: root.optJSONArray("\$values") ?: JSONArray()
                else -> JSONArray()
            }

        val out = ArrayList<ApiMotaLite>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val name = obj.optString("izena", obj.optString("Izena", "")).trim()
            if (name.isBlank()) continue
            out.add(ApiMotaLite(id = id, izena = name))
        }
        return out
    }

    private fun fetchProduktuak(trace: ApiTrace): List<ApiProduktuaLite> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Produktuak",
                    "$baseUrl/produktuak"
                )
            }.distinct()

        var lastError: String? = null
        var okBody: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, body) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                    continue
                }
                okBody = body
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val body = okBody ?: run {
            trace.add("PRODUKTUAK_FAILED $lastError")
            return emptyList()
        }

        val root = JSONTokener(body).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("result") ?: root.optJSONArray("\$values") ?: JSONArray()
                else -> JSONArray()
            }

        val out = ArrayList<ApiProduktuaLite>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val izena = obj.optString("izena", obj.optString("Izena", "")).trim().ifBlank { "Produktua $id" }
            val motaId = obj.optInt("motaId", obj.optInt("MotaId", -1)).takeIf { it > 0 } ?: continue
            val prezioa = obj.optDouble("prezioa", obj.optDouble("Prezioa", 0.0))
            out.add(ApiProduktuaLite(id = id, izena = izena, motaId = motaId, prezioa = prezioa))
        }
        return out
    }

    private fun fetchMahaiak(trace: ApiTrace): List<ApiMahaiaLite> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Mahaiak",
                    "$baseUrl/mahaiak"
                )
            }.distinct()

        var lastError: String? = null
        var okBody: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, body) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                    continue
                }
                okBody = body
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val body = okBody ?: run {
            trace.add("MAHAIAK_FAILED $lastError")
            return emptyList()
        }

        val root = JSONTokener(body).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("result") ?: root.optJSONArray("\$values") ?: JSONArray()
                else -> JSONArray()
            }

        val out = ArrayList<ApiMahaiaLite>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val zenbakia = obj.optInt("zenbakia", obj.optInt("Zenbakia", -1)).takeIf { it > 0 } ?: continue
            out.add(ApiMahaiaLite(id = id, zenbakia = zenbakia))
        }
        return out
    }

    private fun fetchErreserbak(trace: ApiTrace): List<ApiErreserbaLite> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Erreserbak",
                    "$baseUrl/erreserbak"
                )
            }.distinct()

        var lastError: String? = null
        var okBody: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, body) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                    continue
                }
                okBody = body
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val body = okBody ?: run {
            trace.add("ERRESERBAK_FAILED $lastError")
            return emptyList()
        }

        val root = JSONTokener(body).nextValue()
        val array =
            when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("result") ?: root.optJSONArray("\$values") ?: JSONArray()
                else -> JSONArray()
            }

        val out = ArrayList<ApiErreserbaLite>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val egunaOrdua = obj.optString("egunaOrdua", obj.optString("EgunaOrdua", "")).trim().ifBlank { null }
            val bezeroIzena = obj.optString("bezeroIzena", obj.optString("BezeroIzena", "")).trim().ifBlank { null }
            val pertsonaKopurua = obj.optInt("pertsonaKopurua", obj.optInt("PertsonaKopurua", -1)).takeIf { it > 0 }
            val mahaiakId = obj.optInt("mahaiakId", obj.optInt("MahaiakId", -1)).takeIf { it > 0 } ?: continue
            val ordainduta = obj.optInt("ordainduta", obj.optInt("Ordainduta", 0))
            out.add(
                ApiErreserbaLite(
                    id = id,
                    egunaOrdua = egunaOrdua,
                    bezeroIzena = bezeroIzena,
                    pertsonaKopurua = pertsonaKopurua,
                    mahaiakId = mahaiakId,
                    ordainduta = ordainduta
                )
            )
        }
        return out
    }

    private fun fetchEskariakByErreserba(erreserbaId: Int, trace: ApiTrace): List<ApiEskariaLite> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Eskariak/erreserba/$erreserbaId",
                    "$baseUrl/eskariak/erreserba/$erreserbaId"
                )
            }.distinct()

        var lastError: String? = null
        var okBody: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, body) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                    continue
                }
                okBody = body
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val body = okBody ?: run {
            trace.add("ESKARIAK_FAILED erreserbaId=$erreserbaId $lastError")
            return emptyList()
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
            val prezioa = obj.optDouble("prezioa", obj.optDouble("Prezioa", 0.0))
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
                val kantitatea = pObj.optInt("kantitatea", pObj.optInt("Kantitatea", 0)).coerceAtLeast(0)
                val pPrezioa = pObj.optDouble("prezioa", pObj.optDouble("Prezioa", 0.0))
                val pName = pObj.optString("produktuaIzena", pObj.optString("ProduktuaIzena", "")).trim().ifBlank { null }
                produktuak.add(
                    ApiEskariaProduktuaLite(
                        produktuaId = pId,
                        kantitatea = kantitatea,
                        prezioa = pPrezioa,
                        produktuaIzena = pName
                    )
                )
            }
            out.add(ApiEskariaLite(id = id, erreserbaId = eId, prezioa = prezioa, egoera = egoera, produktuak = produktuak))
        }
        return out
    }

    private fun isEskariaDone(egoera: String?): Boolean {
        val e = egoera?.trim().orEmpty().lowercase()
        if (e.isBlank()) return false
        return e == "egina" || e == "zerbitzatuta" || e.contains("egina")
    }

    private data class MahaiFallbackSeed(
        val erreserbaId: Int,
        val tablesLabel: String?,
        val personCount: Int?
    )

    private fun loadGroupsFromMahaiakFallback(todayYmd: String, currentTxanda: String, trace: ApiTrace): List<KitchenOrderGroup> {
        val seeds = fetchMahaiakOccupiedSeeds(trace)
        if (seeds.isEmpty()) return emptyList()

        val groups = ArrayList<KitchenOrderGroup>(seeds.size)
        for (s in seeds) {
            val fakturaId = runCatching { fetchFakturaIdByErreserbaId(s.erreserbaId, trace) }.getOrNull()
            val komandak =
                if (fakturaId != null) {
                    runCatching { fetchKomandakByFaktura(fakturaId, trace) }.getOrNull().orEmpty()
                } else {
                    emptyList()
                }

            if (komandak.isEmpty()) continue

            groups.add(
                KitchenOrderGroup(
                    erreserbaId = s.erreserbaId,
                    txanda = currentTxanda,
                    customerName = null,
                    personCount = s.personCount,
                    tablesLabel = s.tablesLabel,
                    fakturaId = fakturaId,
                    komandak = komandak.sortedWith(compareBy<KitchenKomanda> { it.egoera }.thenBy { it.id })
                )
            )
        }

        return groups.sortedWith(compareBy<KitchenOrderGroup> { it.tablesLabel ?: "" }.thenBy { it.erreserbaId })
    }

    private fun fetchMahaiakOccupiedSeeds(trace: ApiTrace): List<MahaiFallbackSeed> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                val noApi =
                    if (baseUrl.endsWith("/api")) {
                        baseUrl.removeSuffix("/api").trimEnd('/')
                    } else {
                        baseUrl
                    }
                listOf(
                    "$baseUrl/Mahaiak",
                    "$baseUrl/Mahai",
                    "$noApi/mahaiak"
                )
            }.distinct()

        var lastError: String? = null
        var okBody: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, text, _) = httpGetWithHeaders(candidateUrl, trace)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${text.take(200)}"
                    continue
                }
                okBody = text
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val body = okBody ?: run {
            trace.add("MAHAIAK_FAILED $lastError")
            return emptyList()
        }

        val root = parseJsonPossiblyDoubleEncoded(body) ?: return emptyList()
        val array =
            extractArrayFromJson(
                root,
                "mahaiak",
                "Mahaiak",
                "data",
                "result",
                "value",
                "Value",
                "items",
                "Items"
            )

        val byErreserba = LinkedHashMap<Int, Pair<MutableList<Int>, MutableList<Int>>>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val erreserbaId = obj.optInt("ErreserbaId", obj.optInt("erreserbaId", -1)).takeIf { it > 0 }
            val occupied =
                parseBooleanAny(obj, "Occupied")
                    ?: parseBooleanAny(obj, "occupied")
                    ?: parseBooleanAny(obj, "Okupatuta")
                    ?: parseBooleanAny(obj, "okupatuta")
                    ?: parseBooleanAny(obj, "isOccupied")
                    ?: parseBooleanAny(obj, "IsOccupied")
                    ?: false

            if (!occupied && erreserbaId == null) continue
            if (erreserbaId == null) continue
            val zenbakia = obj.optInt("Zenbakia", obj.optInt("zenbakia", -1)).takeIf { it > 0 }
            val personCount = obj.optInt("PertsonaKopurua", obj.optInt("pertsonaKopurua", -1)).takeIf { it > 0 }

            val (tables, people) = byErreserba.getOrPut(erreserbaId) { mutableListOf<Int>() to mutableListOf<Int>() }
            if (zenbakia != null) tables.add(zenbakia)
            if (personCount != null) people.add(personCount)
        }

        val seeds = ArrayList<MahaiFallbackSeed>(byErreserba.size)
        for ((erreserbaId, pair) in byErreserba) {
            val tables = pair.first.distinct().sorted()
            val tablesLabel =
                if (tables.isEmpty()) {
                    null
                } else if (tables.size == 1) {
                    " ${tables.first()}. mahaia"
                } else {
                    "${tables.joinToString(", ")}. mahaiak"
                }
            val personCount = pair.second.distinct().maxOrNull()
            seeds.add(MahaiFallbackSeed(erreserbaId = erreserbaId, tablesLabel = tablesLabel, personCount = personCount))
        }

        return seeds
    }

    private fun parseBooleanAny(obj: JSONObject, key: String): Boolean? {
        if (!obj.has(key)) return null
        val v = obj.opt(key)
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> {
                val cleaned = v.trim()
                when {
                    cleaned.equals("true", ignoreCase = true) -> true
                    cleaned.equals("false", ignoreCase = true) -> false
                    cleaned == "1" -> true
                    cleaned == "0" -> false
                    cleaned.toIntOrNull() != null -> cleaned.toInt() != 0
                    else -> null
                }
            }
            is JSONObject -> {
                val inner =
                    v.opt("value")
                        ?: v.opt("Value")
                        ?: v.opt("\$value")
                        ?: v.opt("\$Value")
                when (inner) {
                    is Boolean -> inner
                    is Number -> inner.toInt() != 0
                    is String -> {
                        val cleaned = inner.trim()
                        when {
                            cleaned.equals("true", ignoreCase = true) -> true
                            cleaned.equals("false", ignoreCase = true) -> false
                            cleaned == "1" -> true
                            cleaned == "0" -> false
                            cleaned.toIntOrNull() != null -> cleaned.toInt() != 0
                            else -> null
                        }
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun parseKomandaEgoeraNullable(obj: JSONObject): Boolean? {
        val directKeys =
            listOf(
                "egoera",
                "Egoera",
                "egoeraId",
                "EgoeraId",
                "egina",
                "Egina",
                "isDone",
                "IsDone",
                "done",
                "Done",
                "completed",
                "Completed",
                "estado",
                "Estado"
            )

        for (k in directKeys) {
            parseBooleanAny(obj, k)?.let { return it }
        }

        for (k in directKeys) {
            if (!obj.has(k)) continue
            val v = obj.opt(k)
            when (v) {
                is Number -> return v.toInt() != 0
                is String -> {
                    val cleaned = v.trim()
                    cleaned.toIntOrNull()?.let { return it != 0 }
                }
                is JSONObject -> {
                    val inner = v.opt("id") ?: v.opt("Id") ?: v.opt("value") ?: v.opt("Value")
                    when (inner) {
                        is Number -> return inner.toInt() != 0
                        is String -> inner.trim().toIntOrNull()?.let { return it != 0 }
                    }
                }
            }
        }

        val it = obj.keys()
        while (it.hasNext()) {
            val key = it.next()
            if (!key.contains("egoera", ignoreCase = true) && !key.contains("egina", ignoreCase = true)) continue
            parseBooleanAny(obj, key)?.let { return it }
            val v = obj.opt(key)
            when (v) {
                is Number -> return v.toInt() != 0
                is String -> v.trim().toIntOrNull()?.let { return it != 0 }
            }
        }

        return null
    }

    private fun parseKomandaEgoera(obj: JSONObject): Boolean = parseKomandaEgoeraNullable(obj) ?: false

    private fun fetchKomandaEgoeraById(komandaId: Int, trace: ApiTrace): Boolean? {
        komandaEgoeraCache[komandaId]?.let { return it }

        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Komandak/$komandaId/egoera",
                    "$baseUrl/komandak/$komandaId/egoera",
                    "$baseUrl/Komandak/$komandaId",
                    "$baseUrl/komandak/$komandaId"
                )
            }.distinct()

        var lastError: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, text, _) = httpGetWithHeaders(candidateUrl, trace)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${text.take(200)}"
                    continue
                }
                val root = parseJsonPossiblyDoubleEncoded(text)
                val parsed =
                    when (root) {
                        is Boolean -> root
                        is Number -> root.toInt() != 0
                        is String -> {
                            val cleaned = root.trim()
                            when {
                                cleaned.equals("true", ignoreCase = true) -> true
                                cleaned.equals("false", ignoreCase = true) -> false
                                cleaned.toIntOrNull() != null -> cleaned.toInt() != 0
                                else -> null
                            }
                        }
                        is JSONObject -> {
                            parseKomandaEgoeraNullable(root)
                                ?: (root.optJSONObject("komanda") ?: root.optJSONObject("Komanda"))?.let { parseKomandaEgoeraNullable(it) }
                        }
                        is JSONArray -> {
                            val first = root.optJSONObject(0)
                            if (first != null) {
                                parseKomandaEgoeraNullable(first)
                            } else {
                                null
                            }
                        }
                        else -> null
                    }
                if (parsed != null) {
                    komandaEgoeraCache[komandaId] = parsed
                    if (komandaEgoeraCache.size > 800) {
                        val firstKey = komandaEgoeraCache.keys.firstOrNull()
                        if (firstKey != null) komandaEgoeraCache.remove(firstKey)
                    }
                    return parsed
                }
                lastError = "url=$candidateUrl parsed=null body=${text.take(200)}"
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                if (msg.contains("LazyInitializationException", ignoreCase = true) || msg.contains("Could not initialize proxy", ignoreCase = true)) {
                    trace.add("EGOERA_ENDPOINT_BROKEN url=$candidateUrl error=${msg.take(180)}")
                    continue
                }
                lastError = "url=$candidateUrl error=$msg"
            }
        }
        trace.add("EGOERA_FALLBACK_FAILED komandaId=$komandaId error=${lastError ?: "unknown"}")
        return null
    }

    private data class ErreserbaLite(
        val id: Int,
        val izena: String?,
        val pertsonaKopurua: Int?,
        val txanda: String?,
        val dateYmd: String?,
        val tablesLabel: String?,
        val mahaiIds: List<Int>
    )

    private fun extractArrayFromJson(root: Any?, vararg keys: String): JSONArray {
        fun fromAny(value: Any?): JSONArray? {
            return when (value) {
                is JSONArray -> value
                is JSONObject ->
                    value.optJSONArray("\$values")
                        ?: value.optJSONArray("values")
                        ?: value.optJSONArray("Values")
                        ?: value.optJSONArray("items")
                        ?: value.optJSONArray("Items")
                else -> null
            }
        }

        return when (root) {
            is JSONArray -> root
            is JSONObject -> {
                for (key in keys) {
                    fromAny(root.opt(key))?.let { return it }
                }
                fromAny(root.opt("\$values")) ?: JSONArray()
            }
            else -> JSONArray()
        }
    }

    private fun extractMahaiakArray(erreserbaObj: JSONObject): JSONArray? {
        val raw = erreserbaObj.opt("mahaiak") ?: erreserbaObj.opt("Mahaiak")
        return when (raw) {
            is JSONArray -> raw
            is JSONObject ->
                raw.optJSONArray("\$values")
                    ?: raw.optJSONArray("values")
                    ?: raw.optJSONArray("Values")
            else -> null
        }
    }

    private fun extractMahaiIds(mahaiak: JSONArray?): List<Int> {
        if (mahaiak == null || mahaiak.length() == 0) return emptyList()
        val ids = ArrayList<Int>(mahaiak.length())
        for (i in 0 until mahaiak.length()) {
            val m = mahaiak.optJSONObject(i) ?: continue
            val id = m.optInt("id", m.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            ids.add(id)
        }
        return ids.distinct()
    }

    private fun parseJsonPossiblyDoubleEncoded(body: String): Any? {
        val first = runCatching { JSONTokener(body).nextValue() }.getOrNull()
        if (first is String) {
            val trimmed = first.trim()
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                return runCatching { JSONTokener(trimmed).nextValue() }.getOrNull() ?: first
            }
        }
        return first
    }

    private data class FetchErreserbakResult(
        val erreserbak: List<ErreserbaLite>,
        val okUrl: String?,
        val lastError: String?,
        val body: String?
    )

    private fun todayYmd(): String {
        val cal = Calendar.getInstance()
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(y, m, d)
    }

    private fun normalizeDateYmd(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return null
        Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(s)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return null
            val mo = m.groupValues[2].toIntOrNull() ?: return null
            val d = m.groupValues[3].toIntOrNull() ?: return null
            return "%04d-%02d-%02d".format(y, mo, d)
        }
        Regex("""^(\d{4})/(\d{1,2})/(\d{1,2})""").find(s)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return null
            val mo = m.groupValues[2].toIntOrNull() ?: return null
            val d = m.groupValues[3].toIntOrNull() ?: return null
            return "%04d-%02d-%02d".format(y, mo, d)
        }
        Regex("""^(\d{2})/(\d{2})/(\d{4})""").find(s)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return null
            val mo = m.groupValues[2].toIntOrNull() ?: return null
            val y = m.groupValues[3].toIntOrNull() ?: return null
            return "%04d-%02d-%02d".format(y, mo, d)
        }
        Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})""").find(s)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return null
            val b = m.groupValues[2].toIntOrNull() ?: return null
            val y = m.groupValues[3].toIntOrNull() ?: return null
            val (d, mo) =
                when {
                    a > 12 -> a to b
                    b > 12 -> b to a
                    else -> a to b
                }
            return "%04d-%02d-%02d".format(y, mo, d)
        }
        return null
    }

    private fun fetchErreserbakFromCandidates(candidates: List<String>, trace: ApiTrace): FetchErreserbakResult {
        trace.add("=== ERRESERBAK CANDIDATES (${candidates.size}) ===")
        for (u in candidates) trace.add("candidate=$u")
        var lastError: String? = null
        var body: String? = null
        var okUrl: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, text, _) = httpGetWithHeaders(candidateUrl, trace)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${text.take(200)}"
                    continue
                }
                body = text
                okUrl = candidateUrl
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
                trace.add("EXCEPTION url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}")
            }
        }

        val finalBody = body
        if (finalBody == null) return FetchErreserbakResult(erreserbak = emptyList(), okUrl = okUrl, lastError = lastError, body = null)

        val trimmedBody = finalBody.trim()
        val root = parseJsonPossiblyDoubleEncoded(finalBody) ?: return FetchErreserbakResult(emptyList(), okUrl, lastError, finalBody)
        val array =
            extractArrayFromJson(
                root,
                "erreserbak",
                "Erreserbak",
                "data",
                "result",
                "value",
                "Value",
                "items",
                "Items"
            )

        val parsed = ArrayList<ErreserbaLite>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id =
                obj.optInt("id", obj.optInt("Id", obj.optInt("ID", -1))).takeIf { it > 0 } ?: continue
            val izena = obj.optString("izena", obj.optString("Izena", "")).trim().ifBlank { null }
            val txanda = obj.optString("txanda", obj.optString("Txanda", "")).trim().ifBlank { null }
            val pertsonaKopurua =
                obj.optInt("pertsonaKopurua", obj.optInt("PertsonaKopurua", -1)).takeIf { it > 0 }
            val dateRaw = obj.optString("data", obj.optString("Data", "")).trim().ifBlank { null }
            val dateYmd = normalizeDateYmd(dateRaw)
            val mahaiak = extractMahaiakArray(obj)
            val tablesLabel = buildTablesLabel(mahaiak)
            val mahaiIds = extractMahaiIds(mahaiak)
            parsed.add(
                ErreserbaLite(
                    id = id,
                    izena = izena,
                    pertsonaKopurua = pertsonaKopurua,
                    txanda = txanda,
                    dateYmd = dateYmd,
                    tablesLabel = tablesLabel,
                    mahaiIds = mahaiIds
                )
            )
        }

        if (parsed.isEmpty()) {
            if (lastError != null) {
                throw IllegalStateException("Ezin izan dira erreserbak kargatu ($lastError)")
            }
            val looksEmpty =
                trimmedBody == "[]" ||
                    trimmedBody == "{}" ||
                    trimmedBody.equals("null", ignoreCase = true) ||
                    trimmedBody.isBlank()
            if (!looksEmpty) {
                val prefix = trimmedBody.replace("\n", " ").replace("\r", " ").take(220)
                throw IllegalStateException("Erreserben erantzuna ezin izan da parseatu (url=${okUrl ?: "?"} body=${prefix})")
            }
        }

        return FetchErreserbakResult(parsed, okUrl, lastError, finalBody)
    }

    private fun fetchErreserbakHoy(todayYmd: String, trace: ApiTrace): Pair<List<ErreserbaLite>, String> {
        val gaurCandidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Erreserbak/gaur",
                    "$baseUrl/erreserbak/gaur"
                )
            }.distinct()

        trace.add("=== FETCH ERRESERBAK HOY ===")
        trace.add("todayYmd=$todayYmd")
        val fromGaur = fetchErreserbakFromCandidates(gaurCandidates, trace)
        if (fromGaur.erreserbak.isNotEmpty()) {
            return fromGaur.erreserbak to (fromGaur.okUrl ?: "Erreserbak/gaur")
        }

        val dateCandidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Erreserbak/data/$todayYmd",
                    "$baseUrl/erreserbak/data/$todayYmd"
                )
            }.distinct()
        val fromDate = fetchErreserbakFromCandidates(dateCandidates, trace)
        if (fromDate.erreserbak.isNotEmpty()) {
            return fromDate.erreserbak to (fromDate.okUrl ?: "Erreserbak/data")
        }

        val allCandidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Erreserbak",
                    "$baseUrl/erreserbak"
                )
            }.distinct()

        val fromAll = fetchErreserbakFromCandidates(allCandidates, trace)
        val filtered = fromAll.erreserbak.filter { it.dateYmd == todayYmd }
        return filtered to (fromAll.okUrl ?: "Erreserbak")
    }

    private fun buildTablesLabel(mahaiak: JSONArray?): String? {
        if (mahaiak == null || mahaiak.length() == 0) return null
        val numbers = ArrayList<Int>()
        for (i in 0 until mahaiak.length()) {
            val m = mahaiak.optJSONObject(i) ?: continue
            val n =
                when {
                    m.has("mahaiZenbakia") -> m.optInt("mahaiZenbakia", -1)
                    m.has("MahaiZenbakia") -> m.optInt("MahaiZenbakia", -1)
                    m.has("mahai_zenbakia") -> m.optInt("mahai_zenbakia", -1)
                    m.has("MahaiZenbakia") -> m.optInt("MahaiZenbakia", -1)
                    else -> -1
                }.takeIf { it > 0 }
            if (n != null) numbers.add(n)
        }

        if (numbers.isEmpty()) return null
        val distinct = numbers.distinct().sorted()
        return if (distinct.size == 1) {
            "${distinct.first()}. mahaia"
        } else {
            "${distinct.joinToString(", ")} mahaiak"
        }
    }

    private fun fetchFakturaIdByErreserbaId(erreserbaId: Int, trace: ApiTrace): Int {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Fakturak/erreserba/$erreserbaId/item",
                    "$baseUrl/fakturak/erreserba/$erreserbaId/item",
                    "$baseUrl/Fakturak/erreserba/$erreserbaId",
                    "$baseUrl/fakturak/erreserba/$erreserbaId"
                )
            }.distinct()

        var lastError: String? = null
        var okBody: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, text, _) = httpGetWithHeaders(candidateUrl, trace)
                if (code == 404) continue
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${text.take(200)}"
                    continue
                }
                okBody = text
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val body = okBody ?: throw IllegalStateException("Ezin izan da faktura kargatu ($lastError)")
        val obj = JSONTokener(body).nextValue() as? JSONObject ?: throw IllegalStateException("Fakturaren erantzuna baliogabea da")
        return obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 }
            ?: throw IllegalStateException("Faktura id-rik gabe")
    }

    private fun ensureFakturaIdFromMahaiakSession(mahaiIds: List<Int>, trace: ApiTrace): Int {
        var lastError: String? = null
        for (mahaiId in mahaiIds) {
            val candidates =
                apiBaseUrlCandidates().flatMap { baseUrl ->
                    listOf(
                        "$baseUrl/Mahaiak/$mahaiId/comanda-session",
                        "$baseUrl/mahaiak/$mahaiId/comanda-session",
                        "$baseUrl/Mahai/$mahaiId/comanda-session",
                        "$baseUrl/mahai/$mahaiId/comanda-session"
                    )
                }.distinct()

            for (candidateUrl in candidates) {
                try {
                    trace.add("--- REQUEST ---")
                    trace.add("POST $candidateUrl")
                    val conn =
                        (URL(candidateUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Accept", "application/json")
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            doOutput = true
                            connectTimeout = 15000
                            readTimeout = 15000
                        }

                    val body = """{"action":"new"}"""
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val respBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                    trace.add("--- RESPONSE ---")
                    trace.add("code=$code")
                    trace.add("body=$respBody")
                    trace.add("")

                    if (code !in 200..299) {
                        lastError = "url=$candidateUrl code=$code body=${respBody.take(200)}"
                        continue
                    }

                    val obj = JSONTokener(respBody).nextValue() as? JSONObject ?: continue
                    val fakturaId = obj.optInt("fakturaId", obj.optInt("FakturaId", -1)).takeIf { it > 0 }
                    if (fakturaId != null) return fakturaId
                    lastError = "url=$candidateUrl code=$code missingFakturaId body=${respBody.take(200)}"
                } catch (e: Exception) {
                    lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        throw IllegalStateException("Ezin izan da ($lastError). mahaiaren faktura lortu/sortu")
    }

    private fun fetchKomandakByFaktura(fakturaId: Int, trace: ApiTrace): List<KitchenKomanda> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Komandak/faktura/$fakturaId/items",
                    "$baseUrl/komandak/faktura/$fakturaId/items",
                    "$baseUrl/Komandak/faktura/$fakturaId",
                    "$baseUrl/komandak/faktura/$fakturaId"
                )
            }.distinct()

        var lastError: String? = null
        var okBody: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, text, _) = httpGetWithHeaders(candidateUrl, trace)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${text.take(200)}"
                    continue
                }
                okBody = text
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val body = okBody ?: throw IllegalStateException("Ezin izan dira komandak kargatu ($lastError)")
        val root = parseJsonPossiblyDoubleEncoded(body)
        val array =
            extractArrayFromJson(
                root,
                "komandak",
                "Komandak",
                "data",
                "result",
                "value",
                "Value",
                "items",
                "Items"
            )

        val result = ArrayList<KitchenKomanda>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: continue
            val kopurua = obj.optInt("kopurua", obj.optInt("Kopurua", 0)).takeIf { it > 0 } ?: 0
            val oharrak = obj.optString("oharrak", obj.optString("Oharrak", "")).trim().ifBlank { null }
            val egoera =
                parseKomandaEgoeraNullable(obj)
                    ?: fetchKomandaEgoeraById(id, trace)
                    ?: false

            val platerakId =
                run {
                    val p = obj.optJSONObject("platerak") ?: obj.optJSONObject("Platerak")
                    val fromNested =
                        p?.optInt("id", p.optInt("Id", -1))?.takeIf { it > 0 }
                    fromNested
                        ?: obj.optInt("platerakId", obj.optInt("PlaterakId", -1)).takeIf { it > 0 }
                }

            val plateraInfo = if (platerakId != null) fetchPlateraInfo(platerakId, trace) else PlateraInfo("Platera", null, null)

            result.add(
                KitchenKomanda(
                    id = id,
                    platerakId = platerakId,
                    plateraIzena = plateraInfo.izena,
                    kategoriaId = plateraInfo.kategoriaId,
                    kategoriaIzena = plateraInfo.kategoriaIzena,
                    kopurua = kopurua,
                    oharrak = oharrak,
                    egoera = egoera
                )
            )
        }

        return result
    }

    private fun fetchPlateraInfo(platerakId: Int, trace: ApiTrace): PlateraInfo {
        plateraInfoCache[platerakId]?.let { return it }

        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "$baseUrl/Platerak/$platerakId",
                    "$baseUrl/platerak/$platerakId"
                )
            }.distinct()

        var lastError: String? = null
        var okBody: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, text, _) = httpGetWithHeaders(candidateUrl, trace)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${text.take(200)}"
                    continue
                }
                okBody = text
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val body = okBody
        if (body == null) {
            trace.add("PLATERA_INFO_FALLBACK platerakId=$platerakId reason=${lastError ?: "unknown"}")
            val fallback = PlateraInfo("Platera $platerakId", null, null)
            plateraInfoCache[platerakId] = fallback
            return fallback
        }

        val obj = JSONTokener(body).nextValue() as? JSONObject
        if (obj == null) {
            trace.add("PLATERA_INFO_FALLBACK platerakId=$platerakId reason=invalid_json body=${body.take(120)}")
            val fallback = PlateraInfo("Platera $platerakId", null, null)
            plateraInfoCache[platerakId] = fallback
            return fallback
        }
        val name = obj.optString("izena", obj.optString("Izena", "")).trim().ifBlank { "Platera $platerakId" }
        val kategoriaId =
            obj.optInt("kategoriaId", obj.optInt("KategoriaId", -1)).takeIf { it > 0 }
        val kategoriaIzena = obj.optString("kategoriaIzena", obj.optString("KategoriaIzena", "")).trim().ifBlank { null }

        val info = PlateraInfo(izena = name, kategoriaId = kategoriaId, kategoriaIzena = kategoriaIzena)
        plateraInfoCache[platerakId] = info
        if (plateraInfoCache.size > 300) {
            val firstKey = plateraInfoCache.keys.firstOrNull()
            if (firstKey != null) plateraInfoCache.remove(firstKey)
        }
        return info
    }

    private fun patchKomandaEgoera(komandaId: Int, egoera: Boolean) {
        val eskariaId =
            komandaToEskariaId[komandaId]
                ?: throw IllegalStateException("Ezin izan da eskaria aurkitu (komandaId=$komandaId)")

        val eskaria = fetchEskariaById(eskariaId)
        val newEgoera = if (egoera) "Egina" else "Bidalita"
        val produktuakArray = JSONArray()
        for (p in eskaria.produktuak) {
            produktuakArray.put(
                JSONObject()
                    .put("ProduktuaId", p.produktuaId)
                    .put("Kantitatea", p.kantitatea)
                    .put("Prezioa", p.prezioa)
            )
        }
        val payload =
            JSONObject()
                .put("ErreserbaId", eskaria.erreserbaId)
                .put("Prezioa", eskaria.prezioa)
                .put("Egoera", newEgoera)
                .put("Produktuak", produktuakArray)

        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val url = "${baseUrl.trimEnd('/')}/Eskariak/$eskariaId"
            try {
                val (code, body) = httpPutJson(url, payload)
                if (code in 200..299) return
                lastError = "url=$url code=$code body=${body.take(200)}"
            } catch (e: Exception) {
                lastError = "url=$url error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        throw IllegalStateException("Ezin izan da egoera eguneratu ($lastError)")
    }

    private fun fetchEskariaById(eskariaId: Int): ApiEskariaLite {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                listOf(
                    "${baseUrl.trimEnd('/')}/Eskariak/$eskariaId",
                    "${baseUrl.trimEnd('/')}/eskariak/$eskariaId"
                )
            }.distinct()

        var lastError: String? = null
        var okBody: String? = null
        for (candidateUrl in candidates) {
            try {
                val (code, body) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    lastError = "url=$candidateUrl code=$code body=${body.take(200)}"
                    continue
                }
                okBody = body
                break
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }

        val body = okBody ?: throw IllegalStateException("Ezin izan da eskaria kargatu ($lastError)")
        val obj = JSONTokener(body).nextValue() as? JSONObject ?: throw IllegalStateException("Ezin izan da eskaria kargatu (json)")

        val id = obj.optInt("id", obj.optInt("Id", -1)).takeIf { it > 0 } ?: eskariaId
        val erreserbaId = obj.optInt("erreserbaId", obj.optInt("ErreserbaId", -1)).takeIf { it > 0 } ?: -1
        val prezioa = obj.optDouble("prezioa", obj.optDouble("Prezioa", 0.0))
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
            val kantitatea = pObj.optInt("kantitatea", pObj.optInt("Kantitatea", 0)).coerceAtLeast(0)
            val pPrezioa = pObj.optDouble("prezioa", pObj.optDouble("Prezioa", 0.0))
            val pName = pObj.optString("produktuaIzena", pObj.optString("ProduktuaIzena", "")).trim().ifBlank { null }
            produktuak.add(ApiEskariaProduktuaLite(produktuaId = pId, kantitatea = kantitatea, prezioa = pPrezioa, produktuaIzena = pName))
        }

        return ApiEskariaLite(id = id, erreserbaId = erreserbaId, prezioa = prezioa, egoera = egoera, produktuak = produktuak)
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

    private fun httpGetWithHeaders(url: String, trace: ApiTrace): Triple<Int, String, Map<String, List<String>>> {
        trace.add("--- REQUEST ---")
        trace.add("GET $url")
        val conn =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 15000
            }

        val code = conn.responseCode
        val headers = conn.headerFields.filterKeys { it != null }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        trace.add("--- RESPONSE ---")
        trace.add("code=$code")
        trace.add("headers=$headers")
        trace.add("body=$body")
        trace.add("")
        return Triple(code, body, headers)
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

    private fun currentTxanda(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 19 -> "Bazkaria"
            else -> "Afaria"
        }
    }
}
