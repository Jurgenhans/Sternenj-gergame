package de.dateiagent.fs

/** Eintrag im freigegebenen Ordner. [path] ist immer relativ zur Ordnerwurzel ("" = Wurzel). */
data class FsEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val mime: String?,
)

class FsException(message: String) : Exception(message)

/** Abstraktion des Dateizugriffs. Produktiv: [SafFileSystem]; in Tests: In-Memory. */
interface VirtualFs {
    fun list(path: String): List<FsEntry>
    fun stat(path: String): FsEntry?
    fun readBytes(path: String, maxBytes: Long = Long.MAX_VALUE): ByteArray
    /** Schreibt Datei; legt fehlende Elternordner NICHT an. Liefert den Eintrag nach dem Schreiben. */
    fun writeBytes(path: String, data: ByteArray, overwrite: Boolean): FsEntry
    fun mkdir(path: String): FsEntry
    fun delete(path: String)
    fun rename(path: String, newName: String): FsEntry
    fun copy(from: String, to: String): FsEntry
    fun move(from: String, to: String): FsEntry
}

/** Zentrale Pfadvalidierung. Jeder Pfad der KI laeuft hier durch, bevor er das Dateisystem erreicht. */
object PathValidator {
    private val forbiddenChars = Regex("[\\u0000-\\u001F\\\\:*?\"<>|]")

    /** Normalisiert einen relativen Pfad. Wirft [FsException] bei Ausbruchsversuchen. */
    fun normalize(raw: String?): String {
        val input = (raw ?: "").trim()
        if (input.contains('\u0000')) throw FsException("Ungültiger Pfad (Steuerzeichen).")
        if (input.contains('\\')) throw FsException("Ungültiger Pfad: Backslash ist nicht erlaubt. Verwende '/'.")
        if (input.startsWith("content:") || input.contains("://")) throw FsException("URIs sind nicht erlaubt; nur relative Pfade.")
        val parts = ArrayList<String>()
        for (seg in input.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> throw FsException("Pfad verlässt den freigegebenen Ordner ('..' ist nicht erlaubt).")
                else -> {
                    if (forbiddenChars.containsMatchIn(seg)) throw FsException("Ungültiger Name '$seg'.")
                    parts += seg
                }
            }
        }
        return parts.joinToString("/")
    }

    fun validateName(name: String): String {
        val n = name.trim()
        if (n.isEmpty() || n == "." || n == ".." || n.contains('/') || forbiddenChars.containsMatchIn(n))
            throw FsException("Ungültiger Datei-/Ordnername '$name'.")
        return n
    }

    fun parent(path: String): String = path.substringBeforeLast('/', "")
    fun nameOf(path: String): String = path.substringAfterLast('/')
    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"
}
