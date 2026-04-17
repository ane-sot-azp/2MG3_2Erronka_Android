package com.example.osislogin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.osislogin.data.AppDatabase
import com.example.osislogin.util.SessionManager
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

enum class LoginMode {
    SelectUser,
    Code
}

data class LoginUserOption(
    val id: Int,
    val kodea: Int,
    val label: String
)

data class LoginUiState(
    val mode: LoginMode = LoginMode.SelectUser,
    val selectableUsers: List<LoginUserOption> = emptyList(),
    val selectedUserKodea: Int? = null,
    val selectedUserLabel: String = "",
    val isLoadingUsers: Boolean = false,
    val langileKodea: String = "",
    val pasahitza: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

class LoginViewModel(
    private val database: AppDatabase,
    private val sessionManager: SessionManager
) : ViewModel() {
    private val apiBaseUrlLanPrimary = "http://192.168.10.5:5000/api"

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    fun setMode(mode: LoginMode) {
        val current = _uiState.value
        if (current.mode == mode) return
        _uiState.value = current.copy(mode = mode, error = null)
    }

    fun updateLangileKodea(value: String) {
        _uiState.value = _uiState.value.copy(langileKodea = value)
    }

    fun updatePasahitza(value: String) {
        _uiState.value = _uiState.value.copy(pasahitza = value)
    }

    fun loadSelectableUsers() {
        val current = _uiState.value
        if (current.isLoadingUsers || current.selectableUsers.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingUsers = true, error = null)
            try {
                val users = withContext(Dispatchers.IO) { fetchUsersWithLanpostua2() }
                val first = users.firstOrNull()
                _uiState.value =
                    _uiState.value.copy(
                        isLoadingUsers = false,
                        selectableUsers = users,
                        selectedUserKodea = first?.kodea,
                        selectedUserLabel = first?.label.orEmpty()
                    )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingUsers = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun selectUser(user: LoginUserOption) {
        _uiState.value =
            _uiState.value.copy(
                selectedUserKodea = user.kodea,
                selectedUserLabel = user.label,
                error = null
            )
    }

    fun login() {
        when (_uiState.value.mode) {
            LoginMode.SelectUser -> loginWithSelectedUser()
            LoginMode.Code -> loginWithApiCode()
        }
    }

    private fun loginWithSelectedUser() {
        val current = _uiState.value
        val kodea = current.selectedUserKodea
        if (kodea == null || current.selectedUserLabel.isBlank()) {
            _uiState.value = current.copy(error = "Aukeratu erabiltzaile bat")
            return
        }
        val pasahitza = current.pasahitza
        if (pasahitza.isBlank()) {
            _uiState.value = current.copy(error = "Sartu pasahitza")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val (ok, obj) = withContext(Dispatchers.IO) { postLogin(kodea, pasahitza) }
                if (ok) {
                    val data = obj.optJSONObject("data") ?: obj.optJSONObject("Data") ?: JSONObject()
                    val userId = data.optInt("id", data.optInt("Id", -1)).takeIf { it > 0 } ?: 0
                    val name =
                        data
                            .optString("izena", data.optString("Izena", current.selectedUserLabel))
                            .trim()
                            .ifBlank { current.selectedUserLabel }
                    val chatEnabled =
                        data.optBoolean("txat_sarbidea", data.optBoolean("Txat_sarbidea", false))
                    sessionManager.saveUserSession(userId = userId, email = kodea.toString(), name = name)
                    sessionManager.setChatEnabled(chatEnabled)
                    _uiState.value = _uiState.value.copy(isLoading = false, isSuccess = true)
                } else {
                    val message = obj.optString("message", obj.optString("Message", "Login errorea")).trim()
                    _uiState.value = _uiState.value.copy(isLoading = false, error = message.ifBlank { "Login errorea" })
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun loginWithApiCode() {
        val current = _uiState.value
        val kodeaText = current.langileKodea.trim()
        val kodea = kodeaText.toIntOrNull()
        if (kodea == null) {
            _uiState.value = current.copy(error = "Langile kodea zenbaki bat izan behar da")
            return
        }
        val pasahitza = current.pasahitza
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val (ok, obj) = withContext(Dispatchers.IO) { postLogin(kodea, pasahitza) }
                if (ok) {
                    val data = obj.optJSONObject("data") ?: obj.optJSONObject("Data") ?: JSONObject()
                    val userId = data.optInt("id", data.optInt("Id", -1)).takeIf { it > 0 } ?: 0
                    val name =
                        data
                            .optString("izena", data.optString("Izena", kodeaText))
                            .trim()
                            .ifBlank { kodeaText }
                    val chatEnabled =
                        data.optBoolean("txat_sarbidea", data.optBoolean("Txat_sarbidea", false))
                    sessionManager.saveUserSession(userId = userId, email = kodeaText, name = name)
                    sessionManager.setChatEnabled(chatEnabled)
                    _uiState.value = _uiState.value.copy(isLoading = false, isSuccess = true)
                } else {
                    val message = obj.optString("message", obj.optString("Message", "Login errorea")).trim()
                    _uiState.value = _uiState.value.copy(isLoading = false, error = message.ifBlank { "Login errorea" })
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun resetSuccess() {
        _uiState.value = _uiState.value.copy(isSuccess = false)
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

    private fun fetchUsersWithLanpostua2(): List<LoginUserOption> {
        val candidates =
            apiBaseUrlCandidates().flatMap { baseUrl ->
                val noApi =
                    if (baseUrl.endsWith("/api")) {
                        baseUrl.removeSuffix("/api").trimEnd('/')
                    } else {
                        baseUrl
                    }
                listOf(
                    "${baseUrl.trimEnd('/')}/Langileak/lanpostua/2",
                    "${baseUrl.trimEnd('/')}/Langileak",
                    "${baseUrl.trimEnd('/')}/Langile",
                    "${noApi.trimEnd('/')}/langileak"
                )
            }.distinct()

        val errors = ArrayList<String>(candidates.size)
        for (candidateUrl in candidates) {
            try {
                val (code, body) = httpGet(candidateUrl)
                if (code !in 200..299) {
                    errors.add("url=$candidateUrl code=$code body=${body.take(200)}")
                    continue
                }
                val root = JSONTokener(body).nextValue()
                val array =
                    when (root) {
                        is JSONArray -> root
                        is JSONObject ->
                            root.optJSONArray("data")
                                ?: root.optJSONArray("Data")
                                ?: root.optJSONArray("langileak")
                                ?: root.optJSONArray("Langileak")
                                ?: root.optJSONArray("result")
                                ?: root.optJSONArray("Result")
                                ?: root.optJSONArray("\$values")
                                ?: JSONArray()
                        else -> JSONArray()
                    }

                val out = ArrayList<LoginUserOption>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val lanpostuaId = extractLanpostuaId(obj) ?: continue
                    if (lanpostuaId != 2) continue

                    val kodea =
                        obj.optInt(
                            "langile_kodea",
                            obj.optInt(
                                "Langile_kodea",
                                obj.optInt(
                                    "langileKodea",
                                    obj.optInt("LangileKodea", obj.optInt("kodea", obj.optInt("Kodea", -1)))
                                )
                            )
                        ).takeIf { it > 0 } ?: continue
                    val id = obj.optInt("id", obj.optInt("Id", kodea))

                    val izena = obj.optString("izena", obj.optString("Izena", "")).trim()
                    val abizena = obj.optString("abizena", obj.optString("Abizena", "")).trim()
                    val label =
                        when {
                            izena.isNotBlank() && abizena.isNotBlank() -> "$izena $abizena"
                            izena.isNotBlank() -> izena
                            abizena.isNotBlank() -> abizena
                            else -> "Langile $kodea"
                        }

                    out.add(LoginUserOption(id = id, kodea = kodea, label = label))
                }
                return out.distinctBy { it.kodea }.sortedWith(compareBy<LoginUserOption> { it.label.lowercase() }.thenBy { it.kodea })
            } catch (e: Exception) {
                errors.add("url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}")
            }
        }
        val tail = errors.takeLast(4).joinToString("\n")
        throw IllegalStateException(
            "Ezin izan da langileen zerrenda kargatu.\n" +
                "Egiaztatu APIa martxan dagoela eta gailu honetatik eskuragarri dagoela.\n" +
                "Azken saiakerak:\n$tail"
        )
    }

    private fun extractLanpostuaId(obj: JSONObject): Int? {
        val direct =
            obj.optInt(
                "id_lanpostua",
                obj.optInt(
                    "Id_lanpostua",
                    obj.optInt(
                        "lanpostua_id",
                        obj.optInt(
                            "Lanpostua_id",
                            obj.optInt(
                                "lanpostuak_id",
                                obj.optInt(
                                    "Lanpostuak_id",
                                    obj.optInt("lanpostuaId", obj.optInt("LanpostuaId", obj.optInt("LanpostuaID", -1)))
                                )
                            )
                        )
                    )
                )
            )
        if (direct > 0) return direct

        val lanpostuaAny = obj.opt("Lanpostua") ?: obj.opt("lanpostua")
        val nested =
            when (lanpostuaAny) {
                is JSONObject -> lanpostuaAny.optInt("Id", lanpostuaAny.optInt("id", -1))
                is Number -> lanpostuaAny.toInt()
                is String -> lanpostuaAny.trim().toIntOrNull() ?: -1
                else -> -1
            }
        if (nested > 0) return nested

        val nestedObj =
            obj.optJSONObject("Lanpostua")
                ?: obj.optJSONObject("lanpostua")
                ?: obj.optJSONObject("LanpostuaDto")
                ?: obj.optJSONObject("lanpostuaDto")
        val nestedObjId = nestedObj?.optInt("Id", nestedObj.optInt("id", -1)) ?: -1
        return nestedObjId.takeIf { it > 0 }
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

    private fun postLogin(langileKodea: Int, pasahitza: String): Pair<Boolean, JSONObject> {
        var lastError: String? = null
        for (baseUrl in apiBaseUrlCandidates()) {
            val candidateUrl = "${baseUrl.trimEnd('/')}/Login"
            try {
                val url = URL(candidateUrl)
                val conn =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 15000
                    }

                val jsonBody =
                    JSONObject()
                        .put("Langile_kodea", langileKodea)
                        .put("Pasahitza", pasahitza)

                conn.outputStream.use { os ->
                    os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val obj = (runCatching { JSONTokener(body).nextValue() }.getOrNull() as? JSONObject) ?: JSONObject()
                val ok = obj.optBoolean("ok", obj.optBoolean("Ok", false))
                if (code in 200..299) return ok to obj

                lastError = "url=$candidateUrl code=$code body=${body.take(250)}"
            } catch (e: Exception) {
                lastError = "url=$candidateUrl error=${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("Ezin izan da login egin ($lastError)")
    }
}
