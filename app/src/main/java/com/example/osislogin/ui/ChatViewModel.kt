package com.example.osislogin.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    val messages: List<String> = emptyList(),
    val unreadCount: Int = 0
)

private data class IncomingFileTransfer(
    val sender: String,
    val fileId: String,
    val fileName: String,
    val expectedSize: Long,
    val mimeType: String,
    val tempFile: File,
    private val outputStream: FileOutputStream
) {
    private var bytesWritten: Long = 0

    fun write(chunk: ByteArray) {
        outputStream.write(chunk)
        bytesWritten += chunk.size
    }

    fun size(): Long = bytesWritten

    fun finish() {
        outputStream.flush()
        outputStream.close()
    }

    fun dispose() {
        try {
            outputStream.close()
        } catch (_: Exception) {
        }
        tempFile.delete()
    }
}

private data class SelectedFileMetadata(
    val fileName: String,
    val size: Long,
    val mimeType: String
)

class ChatViewModel(userName: String, context: Context) : ViewModel() {
    private val hostCandidates = listOf("127.0.0.0")
    private val port = 5555
    private val sharedChatKey = "OSIS_TXAT_GAKO_2026"
    private val encryptionPrefix = "ENC|"
    private val fileStartPrefix = "FILE_START|"
    private val fileChunkPrefix = "FILE_CHUNK|"
    private val fileEndPrefix = "FILE_END|"
    private val fileCancelPrefix = "FILE_CANCEL|"
    private val fileMessagePrefix = "FILE_READY|"
    private val maxFileSize = 5L * 1024L * 1024L
    private val fileChunkSize = 8 * 1024
    private val allowedExtensions = setOf(".pdf", ".jpg", ".jpeg", ".png", ".txt")
    private val appContext = context.applicationContext
    private val fileProviderAuthority = "${appContext.packageName}.fileprovider"

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    private var currentUserName: String = userName
    val userName: String
        get() = currentUserName

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var listenJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts: Int = 0
    private val pending = ArrayDeque<String>()
    private val incomingFileTransfers =
        Collections.synchronizedMap(HashMap<String, IncomingFileTransfer>())
    private val outgoingFileTransfers = Collections.synchronizedSet(HashSet<String>())
    private val chatWriteLock = Any()
    @Volatile private var isChatOpen: Boolean = false

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    companion object {
        fun factory(initialUserName: String, appContext: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ChatViewModel(
                            userName = initialUserName,
                            context = appContext
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
    }

    fun updateUserName(userName: String) {
        val cleaned = userName.trim().ifEmpty { "Anonimo" }
        if (cleaned == currentUserName) return
        currentUserName = cleaned
        if (_uiState.value.isConnected) {
            disconnect()
            connect()
        }
    }

    fun reset() {
        disconnect()
        pending.clear()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                unreadCount = 0,
                error = null,
                status = "Deskonektatuta"
            )
        }
    }

    fun setChatOpen(isOpen: Boolean) {
        isChatOpen = isOpen
        if (isOpen) {
            _uiState.update { it.copy(unreadCount = 0) }
        }
    }

    fun connect() {
        if (_uiState.value.isConnecting || _uiState.value.isConnected) return

        reconnectJob?.cancel()
        reconnectJob = null

        _uiState.update {
            it.copy(
                isConnecting = true,
                isConnected = false,
                error = null,
                status = "Konektatzen..."
            )
        }

        listenJob?.cancel()
        listenJob =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    var s: Socket? = null
                    var lastConnectError: Exception? = null
                    for (host in hostCandidates) {
                        try {
                            withContext(Dispatchers.Main) {
                                _uiState.update { it.copy(status = "Konektatzen $host:$port") }
                            }
                            val candidate = Socket()
                            candidate.connect(InetSocketAddress(host, port), 3000)
                            s = candidate
                            break
                        } catch (e: Exception) {
                            lastConnectError = e
                            try {
                                s?.close()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    val connectedSocket = s ?: throw (lastConnectError ?: IllegalStateException("Ezin izan da konektatu"))
                    if (!connectedSocket.isConnected) {
                        throw (lastConnectError ?: IllegalStateException("Ezin izan da konektatu"))
                    }
                    val r = BufferedReader(InputStreamReader(connectedSocket.getInputStream()))
                    val w = BufferedWriter(OutputStreamWriter(connectedSocket.getOutputStream()))

                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(status = "Erabiltzailea bidaltzen") }
                    }
                    w.write(currentUserName)
                    w.newLine()
                    w.flush()

                    socket = connectedSocket
                    reader = r
                    writer = w

                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(isConnecting = false, isConnected = true, error = null, status = "Konektatuta")
                        }
                    }

                    reconnectAttempts = 0
                    flushPending()

                    while (isActive) {
                        val line = r.readLine() ?: break
                        if (handleIncomingFileMessage(line)) {
                            continue
                        }
                        val visibleLine = prepareIncomingMessage(line)
                        publishIncomingMessage(visibleLine)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isConnecting = false,
                                isConnected = false,
                                error = "${e.javaClass.simpleName}: ${e.message ?: ""}".trimEnd(':', ' '),
                                status = "Deskonektatuta"
                            )
                        }
                    }
                    scheduleReconnect()
                } finally {
                    closeSocket()
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isConnecting = false,
                                isConnected = false,
                                status = "Deskonektatuta"
                            )
                        }
                    }
                }
            }
    }

    fun disconnect() {
        listenJob?.cancel()
        listenJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        clearIncomingTransfers()
        outgoingFileTransfers.clear()
        closeSocket()
        _uiState.update { it.copy(isConnecting = false, isConnected = false, status = "Deskonektatuta") }
    }

    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty()) return

        if (!_uiState.value.isConnected) {
            pending.addLast(msg)
            connect()
            _uiState.update { it.copy(status = "Konektatzen...") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val w = writer ?: throw IllegalStateException("Writer no disponible")
                w.write(encryptMessage(msg))
                w.newLine()
                w.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(error = "${e.javaClass.simpleName}: ${e.message ?: ""}".trimEnd(':', ' '))
                    }
                }
            }
        }
    }

    fun sendFile(uri: Uri) {
        if (!_uiState.value.isConnected) {
            _uiState.update {
                it.copy(
                    error = "Ezin da fitxategia bidali: ez dago konexiorik.",
                    status = "Deskonektatuta"
                )
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val metadata =
                try {
                    readSelectedFileMetadata(uri)
                } catch (e: Exception) {
                    null
                }

            if (metadata == null) {
                showError("Ezin izan da hautatutako fitxategia irakurri.")
                return@launch
            }

            val sanitizedName = sanitizeFileName(metadata.fileName)
            val validationError = validateFile(sanitizedName, metadata.size)
            if (validationError != null) {
                showError("[Fitxategia baztertua] $sanitizedName ($validationError)")
                return@launch
            }

            val fileId = UUID.randomUUID().toString().replace("-", "")
            outgoingFileTransfers.add(fileId)

            try {
                val localCopy = copyUriToChatStorage(uri, fileId, sanitizedName)

                appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                    sendFileStream(fileId, sanitizedName, metadata.size, metadata.mimeType, inputStream)
                } ?: throw IllegalStateException("InputStream ez dago erabilgarri")

                publishLocalMessage(
                    buildFileChatMessage(
                        currentUserName,
                        sanitizedName,
                        formatSize(metadata.size),
                        localCopy.absolutePath
                    )
                )
            } catch (e: Exception) {
                trySendFileCancel(fileId)
                showError("[Errorea] Ezin izan da fitxategia bidali: $sanitizedName")
            } finally {
                outgoingFileTransfers.remove(fileId)
            }
        }
    }

    fun openSharedFile(path: String) {
        val source = File(path)
        if (!source.exists()) {
            _uiState.update { it.copy(error = "Fitxategia ez dago eskuragarri.") }
            return
        }

        val mimeType = guessMimeType(source.name)
        val uri = FileProvider.getUriForFile(appContext, fileProviderAuthority, source)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            appContext.startActivity(Intent.createChooser(intent, "Ireki fitxategia"))
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Ezin izan da fitxategia ireki.") }
        }
    }

    fun downloadFileToUri(sourcePath: String, targetUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val source = File(sourcePath)
            if (!source.exists()) {
                showError("Fitxategia ez dago eskuragarri.")
                return@launch
            }

            try {
                FileInputStream(source).use { input ->
                    appContext.contentResolver.openOutputStream(targetUri)?.use { output ->
                        input.copyTo(output)
                    } ?: throw IllegalStateException("OutputStream ez dago erabilgarri")
                }
            } catch (e: Exception) {
                showError("Ezin izan da fitxategia gorde.")
            }
        }
    }

    private suspend fun flushPending() {
        if (pending.isEmpty()) return
        val w = writer ?: return
        while (pending.isNotEmpty()) {
            val msg = pending.removeFirst()
            w.write(encryptMessage(msg))
            w.newLine()
        }
        w.flush()
    }

    private fun scheduleReconnect() {
        if (reconnectJob != null) return

        val cappedAttempts = reconnectAttempts.coerceAtMost(4)
        val delayMs = (1000L shl cappedAttempts).coerceAtMost(10000L)
        reconnectAttempts += 1

        reconnectJob =
            viewModelScope.launch {
                delay(delayMs)
                reconnectJob = null
                connect()
            }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        reader = null
        writer = null
    }

    private fun publishIncomingMessage(message: String) {
        _uiState.update { state ->
            val isSystem =
                message.contains("sartu da", ignoreCase = true) ||
                    message.contains("atera egin da", ignoreCase = true)
            val newUnread =
                when {
                    isChatOpen -> 0
                    isSystem -> state.unreadCount
                    else -> state.unreadCount + 1
                }
            state.copy(messages = state.messages + message, unreadCount = newUnread)
        }
    }

    private fun publishLocalMessage(message: String) {
        _uiState.update { state -> state.copy(messages = state.messages + message) }
    }

    private suspend fun showError(message: String) {
        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(error = message) }
        }
    }

    private fun handleIncomingFileMessage(message: String): Boolean {
        if (message.isBlank()) return false

        return when {
            message.startsWith(fileStartPrefix) -> handleIncomingFileStart(message)
            message.startsWith(fileChunkPrefix) -> handleIncomingFileChunk(message)
            message.startsWith(fileEndPrefix) -> handleIncomingFileEnd(message)
            message.startsWith(fileCancelPrefix) -> handleIncomingFileCancel(message)
            else -> false
        }
    }

    private fun handleIncomingFileStart(message: String): Boolean {
        val parts = message.split("|", limit = 6)
        if (parts.size != 6) return true

        val sender = parts[1]
        val fileId = parts[2]
        val fileName = sanitizeFileName(parts[3])

        if (outgoingFileTransfers.contains(fileId)) {
            return true
        }

        val size =
            parts[4].toLongOrNull() ?: run {
                publishIncomingMessage("$sender: [Fitxategia baztertua] Tamaina baliogabea.")
                return true
            }

        val validationError = validateFile(fileName, size)
        if (validationError != null) {
            publishIncomingMessage("$sender: [Fitxategia baztertua] $fileName ($validationError)")
            return true
        }

        synchronized(incomingFileTransfers) {
            incomingFileTransfers.remove(fileId)?.dispose()
            incomingFileTransfers[fileId] =
                createIncomingTransfer(
                    sender = sender,
                    fileId = fileId,
                    fileName = fileName,
                    size = size,
                    mimeType = parts[5]
                )
        }

        return true
    }

    private fun handleIncomingFileChunk(message: String): Boolean {
        val parts = message.split("|", limit = 5)
        if (parts.size != 5) return true

        val sender = parts[1]
        val fileId = parts[2]
        if (outgoingFileTransfers.contains(fileId)) {
            return true
        }

        val transfer =
            synchronized(incomingFileTransfers) {
                incomingFileTransfers[fileId]
            } ?: return true

        return try {
            val chunkBase64 = decryptMessage(parts[4])
            val chunkBytes = Base64.getDecoder().decode(chunkBase64)
            transfer.write(chunkBytes)

            if (transfer.size() > maxFileSize || transfer.size() > transfer.expectedSize) {
                throw IllegalStateException("Fitxategiaren tamaina mugaz kanpo dago")
            }
            true
        } catch (_: Exception) {
            removeIncomingTransfer(fileId)?.dispose()
            publishIncomingMessage("$sender: [Errorea] Ezin izan da fitxategia jaso.")
            true
        }
    }

    private fun handleIncomingFileEnd(message: String): Boolean {
        val parts = message.split("|", limit = 3)
        if (parts.size != 3) return true

        val sender = parts[1]
        val fileId = parts[2]
        if (outgoingFileTransfers.remove(fileId)) {
            return true
        }

        val transfer = removeIncomingTransfer(fileId) ?: return true
        return try {
            if (transfer.size() != transfer.expectedSize) {
                throw IllegalStateException("Tamaina ez dator bat")
            }

            val savedFile = saveIncomingFile(transfer)
            publishIncomingMessage(
                buildFileChatMessage(
                    sender = sender,
                    fileName = transfer.fileName,
                    size = formatSize(transfer.expectedSize),
                    path = savedFile.absolutePath
                )
            )
            true
        } catch (_: Exception) {
            publishIncomingMessage("$sender: [Errorea] Ezin izan da fitxategia gorde.")
            true
        } finally {
            transfer.dispose()
        }
    }

    private fun handleIncomingFileCancel(message: String): Boolean {
        val parts = message.split("|", limit = 3)
        if (parts.size != 3) return true

        if (outgoingFileTransfers.remove(parts[2])) {
            return true
        }

        removeIncomingTransfer(parts[2])?.dispose()
        publishIncomingMessage("${parts[1]}: [Fitxategia ezeztatuta]")
        return true
    }

    private fun removeIncomingTransfer(fileId: String): IncomingFileTransfer? {
        return synchronized(incomingFileTransfers) {
            incomingFileTransfers.remove(fileId)
        }
    }

    private fun clearIncomingTransfers() {
        synchronized(incomingFileTransfers) {
            incomingFileTransfers.values.forEach { it.dispose() }
            incomingFileTransfers.clear()
        }
    }

    private fun createIncomingTransfer(
        sender: String,
        fileId: String,
        fileName: String,
        size: Long,
        mimeType: String
    ): IncomingFileTransfer {
        val tempDir = File(appContext.cacheDir, "chat_incoming")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        val tempFile = File(tempDir, "${fileId}_${fileName}")
        return IncomingFileTransfer(
            sender = sender,
            fileId = fileId,
            fileName = fileName,
            expectedSize = size,
            mimeType = mimeType,
            tempFile = tempFile,
            outputStream = FileOutputStream(tempFile)
        )
    }

    private fun saveIncomingFile(transfer: IncomingFileTransfer): File {
        transfer.finish()
        val directory =
            File(
                appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
                "OSIS/TxatFitxategiak"
            )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Ezin izan da karpeta sortu")
        }

        val target = createUniqueFile(directory, transfer.fileName)
        transfer.tempFile.copyTo(target, overwrite = false)
        return target
    }

    private fun readSelectedFileMetadata(uri: Uri): SelectedFileMetadata? {
        val resolver = appContext.contentResolver
        var displayName: String? = null
        var size: Long? = null

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }

        val finalName =
            displayName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "fitxategia"
        val finalSize = size ?: -1L
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { guessMimeType(finalName) }

        return if (finalSize > 0) {
            SelectedFileMetadata(
                fileName = finalName,
                size = finalSize,
                mimeType = mimeType
            )
        } else {
            null
        }
    }

    private fun copyUriToChatStorage(uri: Uri, fileId: String, fileName: String): File {
        val directory = getChatStorageDirectory()
        val localCopy = File(directory, "${fileId}_$fileName")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(localCopy).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("InputStream ez dago erabilgarri")
        return localCopy
    }

    private fun getChatStorageDirectory(): File {
        val directory =
            File(
                appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
                "OSIS/TxatFitxategiak"
            )
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    private fun sendFileStream(
        fileId: String,
        fileName: String,
        size: Long,
        mimeType: String,
        inputStream: InputStream
    ) {
        synchronized(chatWriteLock) {
            val currentWriter = writer ?: throw IllegalStateException("Writer no disponible")
            currentWriter.write("$fileStartPrefix$fileId|$fileName|$size|$mimeType")
            currentWriter.newLine()

            val buffer = ByteArray(fileChunkSize)
            var bytesRead: Int
            var chunkIndex = 0
            while (inputStream.read(buffer).also { bytesRead = it } > 0) {
                val chunkBytes =
                    if (bytesRead == buffer.size) {
                        buffer.clone()
                    } else {
                        buffer.copyOf(bytesRead)
                    }
                val chunkBase64 = Base64.getEncoder().encodeToString(chunkBytes)
                val encryptedChunk = encryptMessage(chunkBase64)
                currentWriter.write("$fileChunkPrefix$fileId|$chunkIndex|$encryptedChunk")
                currentWriter.newLine()
                chunkIndex++
            }

            currentWriter.write("$fileEndPrefix$fileId")
            currentWriter.newLine()
            currentWriter.flush()
        }
    }

    private fun trySendFileCancel(fileId: String) {
        try {
            synchronized(chatWriteLock) {
                writer?.run {
                    write("$fileCancelPrefix$fileId")
                    newLine()
                    flush()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun prepareIncomingMessage(message: String): String {
        val separatorIndex = message.indexOf(": ")
        if (separatorIndex < 0) return message

        val sender = message.substring(0, separatorIndex)
        val payload = message.substring(separatorIndex + 2)
        if (!payload.startsWith(encryptionPrefix)) return message

        return try {
            "$sender: ${decryptMessage(payload)}"
        } catch (_: Exception) {
            "$sender: [Ezin izan da mezua deszifratu]"
        }
    }

    private fun encryptMessage(message: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, getSharedKey(), IvParameterSpec(iv))

        val encrypted = cipher.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return encryptionPrefix +
            Base64.getEncoder().encodeToString(iv) +
            "|" +
            Base64.getEncoder().encodeToString(encrypted)
    }

    private fun decryptMessage(payload: String): String {
        val parts = payload.split("|", limit = 3)
        require(parts.size == 3 && parts[0] == "ENC") { "Mezu zifratua ez da baliozkoa" }

        val iv = Base64.getDecoder().decode(parts[1])
        val encrypted = Base64.getDecoder().decode(parts[2])
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, getSharedKey(), IvParameterSpec(iv))

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, StandardCharsets.UTF_8)
    }

    private fun getSharedKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val key = digest.digest(sharedChatKey.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(key, "AES")
    }

    private fun validateFile(fileName: String, size: Long): String? {
        val extension = getExtension(fileName)
        if (!allowedExtensions.contains(extension)) {
            return "Fitxategi mota hau ez dago baimenduta."
        }
        if (size <= 0) {
            return "Fitxategia hutsik dago."
        }
        if (size > maxFileSize) {
            return "Fitxategiak ${formatSize(maxFileSize)} baino gehiago dauka."
        }
        return null
    }

    private fun sanitizeFileName(fileName: String?): String {
        val cleaned = fileName?.replace(Regex("""[\\/:*?"<>|]"""), "_")?.trim().orEmpty()
        return cleaned.ifBlank { "fitxategia" }
    }

    private fun buildFileChatMessage(sender: String, fileName: String, size: String, path: String): String {
        return "$sender: $fileMessagePrefix${buildDisplayFileName(fileName)}|$size|$path"
    }

    private fun buildDisplayFileName(fileName: String): String {
        val sanitized = sanitizeFileName(fileName)
        val extension = sanitized.substringAfterLast('.', "")
        var baseName = sanitized.substringBeforeLast('.', sanitized)

        val hashPrefix = Regex("^[a-f0-9]{24,}[_-](.+)$", RegexOption.IGNORE_CASE)
        val match = hashPrefix.matchEntire(baseName)
        if (match != null) {
            baseName = match.groupValues[1]
        }

        if (baseName.equals("full", ignoreCase = true)) {
            return if (extension.isBlank()) "irudia" else "irudia.${extension.lowercase(Locale.US)}"
        }

        val finalBase = baseName.ifBlank { "fitxategia" }
        return if (extension.isBlank()) {
            finalBase
        } else {
            "$finalBase.${extension.lowercase(Locale.US)}"
        }
    }

    private fun getExtension(fileName: String): String {
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex >= 0) {
            fileName.substring(dotIndex).lowercase(Locale.US)
        } else {
            ""
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extension = getExtension(fileName).removePrefix(".")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension.lowercase(Locale.US)) {
                "pdf" -> "application/pdf"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
    }

    private fun formatSize(size: Long): String {
        return when {
            size >= 1024L * 1024L ->
                String.format(Locale.US, "%.2f MB", size / 1024d / 1024d)
            size >= 1024L ->
                String.format(Locale.US, "%.2f KB", size / 1024d)
            else -> "$size B"
        }
    }

    private fun createUniqueFile(directory: File, fileName: String): File {
        val target = File(directory, fileName)
        if (!target.exists()) {
            return target
        }

        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex >= 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex >= 0) fileName.substring(dotIndex) else ""

        for (i in 1..999) {
            val candidate = File(directory, "${baseName}_$i$extension")
            if (!candidate.exists()) {
                return candidate
            }
        }

        return File(directory, "${baseName}_${UUID.randomUUID()}$extension")
    }
}
