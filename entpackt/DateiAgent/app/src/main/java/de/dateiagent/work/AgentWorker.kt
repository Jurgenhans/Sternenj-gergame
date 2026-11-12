package de.dateiagent.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.Data
import de.dateiagent.DateiAgentApp
import de.dateiagent.agent.AgentNotifier
import de.dateiagent.agent.Outcome
import de.dateiagent.data.TaskEntity
import de.dateiagent.ui.MainActivity
import kotlinx.coroutines.CancellationException

object Notifications {
    const val CH_PROGRESS = "progress"
    const val CH_EVENTS = "events"

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Laufende Aufgaben", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "Rückfragen & Ergebnisse", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(c: Context) = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun progress(c: Context, text: String): Notification {
        ensureChannels(c)
        return NotificationCompat.Builder(c, CH_PROGRESS).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DateiAgent arbeitet …").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp(c)).build()
    }

    fun event(c: Context, id: Int, title: String, text: String) {
        ensureChannels(c)
        val n = NotificationCompat.Builder(c, CH_EVENTS).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(openApp(c)).build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}

class AppNotifier(private val c: Context) : AgentNotifier {
    override fun approvalNeeded(task: TaskEntity) = Notifications.event(c, task.id.toInt() * 2, "Bestätigung erforderlich", task.approvalText)
    override fun finished(task: TaskEntity) = Notifications.event(c, task.id.toInt() * 2 + 1,
        if (task.status == "COMPLETED") "Aufgabe abgeschlossen" else "Aufgabe fehlgeschlagen",
        (if (task.status == "COMPLETED") task.resultText else task.error).take(300).ifBlank { task.prompt })
}

/**
 * Fuehrt eine Agenten-Aufgabe im Hintergrund aus. Laeuft als Foreground-Worker (sichtbare Benachrichtigung),
 * damit lange Aufgaben nicht vom System beendet werden. Der Zustand wird nach jedem Schritt in Room gesichert.
 */
class AgentWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object { const val KEY_TASK_ID = "taskId" }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Aufgabe wird bearbeitet")

    private fun foreground(text: String): ForegroundInfo {
        val n = Notifications.progress(applicationContext, text)
        val id = 4000 + (inputData.getLong(KEY_TASK_ID, 0) % 1000).toInt()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_TASK_ID, -1); if (id < 0) return Result.failure()
        val app = applicationContext as DateiAgentApp
        runCatching { setForeground(foreground("Aufgabe wird bearbeitet")) }  // kann z. B. im Hintergrund-Start scheitern; Arbeit laeuft dennoch
        return try {
            val outcome = app.container.newRunner().run(id)
            when (outcome) {
                is Outcome.Completed, Outcome.WaitingApproval, Outcome.Stopped -> Result.success()
                is Outcome.Failed -> Result.success(Data.Builder().putString("error", outcome.message).build())
            }
        } catch (e: CancellationException) {
            // System hat den Worker gestoppt (Zeitlimit, Constraints). Zustand ist gesichert; WorkManager plant neu.
            throw e
        } catch (e: Exception) {
            app.container.db.tasks().get(id)?.let { app.container.db.tasks().update(it.copy(status = "QUEUED", error = "Unterbrochen: ${e.message}")) }
            if (runAttemptCount < 3) Result.retry() else {
                app.container.db.tasks().setStatus(id, "FAILED"); Result.failure()
            }
        }
    }
}
