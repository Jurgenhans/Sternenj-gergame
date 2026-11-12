package de.dateiagent

import android.app.Application
import de.dateiagent.agent.AgentConfig
import de.dateiagent.agent.AgentRunner
import de.dateiagent.ai.LlmProvider
import de.dateiagent.ai.OpenAiProvider
import de.dateiagent.data.*
import de.dateiagent.extract.*
import de.dateiagent.fs.FolderAccess
import de.dateiagent.fs.FolderStatus
import de.dateiagent.work.AppNotifier
import de.dateiagent.work.Notifications
import de.dateiagent.work.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URI

/** Einfacher Service-Locator (modulare Abhaengigkeiten ohne DI-Framework). */
class AppContainer(val app: Application) {
    val settings = SettingsStore(app)
    val db = AppDatabase.build(app)
    val memory = MemoryRepository(db.memory())
    val folder = FolderAccess(app, settings)
    val tasks = TaskRepository(app, db)
    private val pdf by lazy { AndroidPdfReader(app) }
    private val extractor by lazy { TextExtractor(pdf) }

    fun provider(): LlmProvider = OpenAiProvider(settings.baseUrl, { settings.apiKey }, settings.model, id = settings.providerId)

    fun config(): AgentConfig = AgentConfig(
        cloudConsent = settings.cloudConsent, imageConsent = settings.imageConsent, maxIterations = settings.maxIterations,
        workingStyle = settings.workingStyle,
        providerHost = runCatching { URI(settings.baseUrl).host }.getOrNull() ?: settings.baseUrl,
        model = settings.model,
        folderName = (folder.status() as? FolderStatus.Valid)?.displayName ?: "Ordner",
    )

    fun newRunner() = AgentRunner(db.tasks(), db.activity(), memory, ::config, ::provider, { folder.openFs() }, extractor, AndroidImageEncoder(), AppNotifier(app))
    fun extractorForUi() = extractor
}

class DateiAgentApp : Application() {
    lateinit var container: AppContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        scope.launch { container.tasks.recoverInterrupted() }   // Wiederherstellung nach App-Neustart
    }
}
