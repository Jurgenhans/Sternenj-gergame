package de.dateiagent.data

/** Lokales Gedaechtnis: Aufgaben-Ergebnisse, Vorlieben, Dateinotizen, analysierte Dateien. */
class MemoryRepository(private val dao: MemoryDao) {

    suspend fun saveTaskResult(taskId: Long, prompt: String, result: String) =
        dao.insert(MemoryEntity(kind = "TASK_RESULT", key = prompt.take(120), text = "Aufgabe: $prompt\nErgebnis: ${result.take(2000)}", taskId = taskId))

    suspend fun remember(kind: String, key: String, text: String, taskId: Long) =
        dao.insert(MemoryEntity(kind = kind, key = key, text = text.take(2000), taskId = taskId))

    suspend fun markAnalyzed(path: String, modified: Long, size: Long, taskId: Long) {
        val last = dao.latest("ANALYZED_FILE", path)
        if (last == null || last.fileModified != modified || last.fileSize != size)
            dao.insert(MemoryEntity(kind = "ANALYZED_FILE", key = path, text = "Analysiert: $path", fileModified = modified, fileSize = size, taskId = taskId))
    }

    suspend fun analyzedInfo(path: String): MemoryEntity? = dao.latest("ANALYZED_FILE", path)

    /** Einfaches Keyword-Scoring (lokal, ohne Netzwerk). */
    suspend fun recall(query: String, limit: Int = 5): List<MemoryEntity> {
        val words = tokenize(query)
        if (words.isEmpty()) return dao.recent().take(limit)
        return dao.recent()
            .map { m -> m to score(words, "${m.key} ${m.text}") }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryEntity, Int>> { it.second }.thenByDescending { it.first.ts })
            .take(limit).map { it.first }
    }

    companion object {
        private val stop = setOf("der", "die", "das", "und", "oder", "ein", "eine", "mit", "von", "für", "alle", "mein", "meine", "meinen", "den", "dem", "ist", "sind", "in", "im", "zu", "auf", "aus")
        fun tokenize(s: String): Set<String> =
            s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 && it !in stop }.toSet()
        fun score(words: Set<String>, text: String): Int {
            val t = text.lowercase()
            return words.count { t.contains(it) }
        }
    }
}
