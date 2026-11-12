package de.dateiagent.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

object TaskStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val WAITING_APPROVAL = "WAITING_APPROVAL"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    val active = listOf(QUEUED, RUNNING, WAITING_APPROVAL)
}

object Approval { const val NONE = "NONE"; const val PENDING = "PENDING"; const val APPROVED = "APPROVED"; const val DENIED = "DENIED" }

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prompt: String,
    val status: String = TaskStatus.QUEUED,
    val goal: String = "",
    val planJson: String = "[]",          // Liste von PlanStep
    val currentStep: String = "",
    val iterations: Int = 0,
    val messagesJson: String = "[]",      // vollstaendiger Konversationsverlauf (Checkpoint)
    val resultText: String = "",
    val error: String = "",
    val approvalCallId: String = "",
    val approvalText: String = "",
    val approvalState: String = Approval.NONE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "activity", indices = [Index("taskId"), Index("ts")])
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val ts: Long = System.currentTimeMillis(),
    val kind: String,       // TOOL, TRANSMIT, APPROVAL, INFO, ERROR
    val tool: String = "",
    val path: String = "",
    val detail: String = "",
    val success: Boolean = true,
)

@Entity(tableName = "memory", indices = [Index("kind")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,        // TASK_RESULT, PREFERENCE, FILE_NOTE, ANALYZED_FILE
    val key: String = "",    // z.B. Dateipfad
    val text: String,
    val fileModified: Long = 0,
    val fileSize: Long = 0,
    val taskId: Long = 0,
    val ts: Long = System.currentTimeMillis(),
)

@Dao
interface TaskDao {
    @Insert suspend fun insert(t: TaskEntity): Long
    @Update suspend fun update(t: TaskEntity)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: Long): TaskEntity?
    @Query("SELECT * FROM tasks WHERE id = :id") fun observe(id: Long): Flow<TaskEntity?>
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC") fun observeAll(): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE status IN ('RUNNING','WAITING_APPROVAL','QUEUED')") suspend fun getActive(): List<TaskEntity>
    @Query("SELECT status FROM tasks WHERE id = :id") suspend fun statusOf(id: Long): String?
    @Query("UPDATE tasks SET status = :status WHERE id = :id") suspend fun setStatus(id: Long, status: String)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface ActivityDao {
    @Insert suspend fun insert(a: ActivityEntity): Long
    @Query("SELECT * FROM activity ORDER BY ts DESC LIMIT 500") fun observeRecent(): Flow<List<ActivityEntity>>
    @Query("SELECT * FROM activity WHERE taskId = :taskId ORDER BY ts ASC") suspend fun forTask(taskId: Long): List<ActivityEntity>
}

@Dao
interface MemoryDao {
    @Insert suspend fun insert(m: MemoryEntity): Long
    @Query("SELECT * FROM memory WHERE kind = :kind AND `key` = :key ORDER BY ts DESC LIMIT 1") suspend fun latest(kind: String, key: String): MemoryEntity?
    @Query("SELECT * FROM memory WHERE kind != 'ANALYZED_FILE' ORDER BY ts DESC LIMIT 400") suspend fun recent(): List<MemoryEntity>
    @Query("SELECT * FROM memory ORDER BY ts DESC LIMIT 200") fun observe(): Flow<List<MemoryEntity>>
    @Query("DELETE FROM memory WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM memory") suspend fun clear()
}

@Database(entities = [TaskEntity::class, ActivityEntity::class, MemoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun activity(): ActivityDao
    abstract fun memory(): MemoryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "dateiagent.db").build()
    }
}
