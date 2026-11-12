package de.dateiagent.work

import android.content.Context
import androidx.work.*
import de.dateiagent.data.*

/** Zentrale Stelle fuer Aufgabenverwaltung: anlegen, pausieren, fortsetzen, abbrechen, Freigaben beantworten. */
class TaskRepository(private val context: Context, private val db: AppDatabase) {
    private val wm get() = WorkManager.getInstance(context)

    suspend fun create(prompt: String): Long {
        val id = db.tasks().insert(TaskEntity(prompt = prompt.trim()))
        enqueue(id); return id
    }

    fun enqueue(id: Long, replace: Boolean = true) {
        val req = OneTimeWorkRequestBuilder<AgentWorker>()
            .setInputData(workDataOf(AgentWorker.KEY_TASK_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .addTag("agent-task").build()
        wm.enqueueUniqueWork("task-$id", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    suspend fun pause(id: Long) { db.tasks().setStatus(id, TaskStatus.PAUSED); wm.cancelUniqueWork("task-$id") }

    suspend fun resume(id: Long) {
        val t = db.tasks().get(id) ?: return
        if (t.status == TaskStatus.PAUSED || t.status == TaskStatus.FAILED) {
            db.tasks().update(t.copy(status = TaskStatus.QUEUED, error = "", updatedAt = System.currentTimeMillis()))
            enqueue(id)
        }
    }

    suspend fun cancel(id: Long) { db.tasks().setStatus(id, TaskStatus.CANCELLED); wm.cancelUniqueWork("task-$id") }

    suspend fun delete(id: Long) { wm.cancelUniqueWork("task-$id"); db.tasks().delete(id) }

    suspend fun answerApproval(id: Long, approved: Boolean) {
        val t = db.tasks().get(id) ?: return
        if (t.approvalState != Approval.PENDING) return
        db.tasks().update(t.copy(approvalState = if (approved) Approval.APPROVED else Approval.DENIED, status = TaskStatus.QUEUED, updatedAt = System.currentTimeMillis()))
        db.activity().insert(ActivityEntity(taskId = id, kind = "APPROVAL", detail = if (approved) "Nutzer hat erlaubt: ${t.approvalText}" else "Nutzer hat abgelehnt: ${t.approvalText}", success = approved))
        enqueue(id)
    }

    /** Beim App-Start: unterbrochene Aufgaben (Prozess beendet) wieder aufnehmen. */
    suspend fun recoverInterrupted() {
        for (t in db.tasks().getActive()) {
            if (t.status == TaskStatus.WAITING_APPROVAL && t.approvalState == Approval.PENDING) continue // wartet auf Nutzer
            if (t.status == TaskStatus.RUNNING) {
                db.activity().insert(ActivityEntity(taskId = t.id, kind = "INFO", detail = "Unterbrochen (App/Prozess beendet) – wird wiederhergestellt."))
                db.tasks().setStatus(t.id, TaskStatus.QUEUED)
            }
            enqueue(t.id, replace = false)
        }
    }
}
