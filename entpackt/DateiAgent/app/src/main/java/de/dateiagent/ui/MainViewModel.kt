package de.dateiagent.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.dateiagent.DateiAgentApp
import de.dateiagent.ai.ChatMessage
import de.dateiagent.data.*
import de.dateiagent.extract.FileTypes
import de.dateiagent.fs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as DateiAgentApp).container
    val settings = c.settings

    val tasks = c.db.tasks().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activity = c.db.activity().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val memory = c.db.memory().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var folderStatus by mutableStateOf<FolderStatus>(c.folder.status()); private set
    fun refreshFolder() { folderStatus = c.folder.status() }

    // ---- Einstellungen (Compose-State als Spiegel der gespeicherten Werte) ----
    var baseUrl by mutableStateOf(settings.baseUrl)
    var model by mutableStateOf(settings.model)
    var providerId by mutableStateOf(settings.providerId)
    var apiKeyInput by mutableStateOf("")
    var hasKey by mutableStateOf(!settings.apiKey.isNullOrBlank())
    var cloudConsent by mutableStateOf(settings.cloudConsent)
    var imageConsent by mutableStateOf(settings.imageConsent)
    var maxIterations by mutableStateOf(settings.maxIterations.toString())
    var workingStyle by mutableStateOf(settings.workingStyle)
    var testResult by mutableStateOf<String?>(null)

    fun saveSettings() {
        settings.providerId = providerId; settings.baseUrl = baseUrl; settings.model = model
        settings.maxIterations = maxIterations.toIntOrNull() ?: 40; settings.workingStyle = workingStyle
        settings.cloudConsent = cloudConsent; settings.imageConsent = imageConsent
        if (apiKeyInput.isNotBlank()) { settings.apiKey = apiKeyInput; apiKeyInput = "" }
        hasKey = !settings.apiKey.isNullOrBlank()
    }
    fun deleteKey() { settings.apiKey = null; hasKey = false }

    fun testConnection() = viewModelScope.launch {
        saveSettings(); testResult = "Teste …"
        testResult = try {
            val r = withContext(Dispatchers.IO) { c.provider().chat(listOf(ChatMessage("user", "Antworte nur mit: OK")), emptyList()) }
            "Verbindung erfolgreich (${c.config().model}): ${r.content?.take(40)}"
        } catch (e: Exception) { "Fehler: ${e.message}" }
    }

    val setupProblem: String?
        get() = when {
            folderStatus !is FolderStatus.Valid -> "Es ist kein (gültiger) Ordner freigegeben."
            !cloudConsent -> "Einwilligung zur Übertragung an die Cloud-KI fehlt (KI-Einstellungen)."
            !hasKey && baseUrl.contains("api.openai.com") -> "API-Schlüssel fehlt (KI-Einstellungen)."
            else -> null
        }

    // ---- Ordner ----
    fun onFolderPicked(uri: Uri?) { if (uri != null) { c.folder.persist(uri); refreshFolder(); browse("") } }
    fun releaseFolder() { c.folder.release(); refreshFolder() }

    // ---- Aufgaben ----
    fun send(prompt: String) { if (prompt.isNotBlank()) viewModelScope.launch { c.tasks.create(prompt) } }
    fun pause(id: Long) = viewModelScope.launch { c.tasks.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { c.tasks.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { c.tasks.cancel(id) }
    fun delete(id: Long) = viewModelScope.launch { c.tasks.delete(id) }
    fun approve(id: Long, ok: Boolean) = viewModelScope.launch { c.tasks.answerApproval(id, ok) }
    fun clearMemory() = viewModelScope.launch { c.db.memory().clear() }
    fun deleteMemory(id: Long) = viewModelScope.launch { c.db.memory().delete(id) }

    // ---- Dateimanager ----
    var path by mutableStateOf(""); private set
    var entries by mutableStateOf<List<FsEntry>>(emptyList()); private set
    var fmError by mutableStateOf<String?>(null)
    var preview by mutableStateOf<Pair<String, String>?>(null)

    fun browse(p: String) = viewModelScope.launch {
        try {
            val list = withContext(Dispatchers.IO) { c.folder.openFs().list(p) }
            path = PathValidator.normalize(p); entries = list; fmError = null
        } catch (e: Exception) { fmError = e.message; entries = emptyList() }
    }
    fun up() { browse(PathValidator.parent(path)) }

    fun openPreview(e: FsEntry) = viewModelScope.launch {
        preview = try {
            withContext(Dispatchers.IO) {
                if (FileTypes.ext(e.name) in FileTypes.images) e.name to "(Bilddatei, ${e.size} Bytes)"
                else e.name to c.extractorForUi().extract(e.name, c.folder.openFs().readBytes(e.path, 10L * 1024 * 1024)).text.take(6000).ifBlank { "(kein Text)" }
            }
        } catch (ex: Exception) { e.name to "Fehler: ${ex.message}" }
    }

    fun manual(kind: String, p: String, block: (VirtualFs) -> Unit) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { block(c.folder.openFs()) }
            c.db.activity().insert(ActivityEntity(taskId = 0, kind = "MANUAL", tool = kind, path = p, detail = "Manuelle Aktion durch Nutzer"))
            browse(path)
        } catch (e: Exception) { fmError = e.message }
    }
    fun deleteEntry(e: FsEntry) = manual("delete", e.path) { it.delete(e.path) }
    fun makeDir(name: String) = manual("create_directory", name) { it.mkdir(PathValidator.join(path, PathValidator.validateName(name))) }
    fun renameEntry(e: FsEntry, n: String) = manual("rename", e.path) { it.rename(e.path, n) }
}
