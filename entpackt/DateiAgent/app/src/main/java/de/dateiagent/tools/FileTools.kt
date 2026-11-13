package de.dateiagent.tools

import de.dateiagent.extract.FileTypes
import de.dateiagent.extract.OfficeWriter
import de.dateiagent.extract.TextExtractor
import de.dateiagent.fs.FsEntry
import de.dateiagent.fs.FsException
import de.dateiagent.fs.PathValidator
import de.dateiagent.fs.VirtualFs
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val BACKUP_DIR = ".ki-backup"
private const val MAX_READ_BYTES = 25L * 1024 * 1024
private const val DEFAULT_CHUNK = 12_000

private fun fmtDate(ms: Long) = if (ms <= 0) "?" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.GERMANY).format(Date(ms))
private fun fmtEntry(e: FsEntry) = (if (e.isDir) "[Ordner] " else "") + e.path + (if (e.isDir) "" else "  (${e.size} B, ${fmtDate(e.modified)})")

/** Legt fehlende Ordner entlang des Pfads an (nur innerhalb der Sandbox). */
fun VirtualFs.ensureDirs(dir: String) {
    if (dir.isEmpty()) return
    var cur = ""
    for (seg in dir.split('/')) {
        cur = PathValidator.join(cur, seg)
        val st = stat(cur)
        if (st == null) mkdir(cur) else if (!st.isDir) throw FsException("'$cur' ist eine Datei, kein Ordner.")
    }
}

private fun walk(fs: VirtualFs, start: String, recursive: Boolean, cap: Int = 3000): List<FsEntry> {
    val out = ArrayList<FsEntry>(); val queue = ArrayDeque<String>(); queue += start
    while (queue.isNotEmpty() && out.size < cap) {
        val d = queue.removeFirst()
        for (e in fs.list(d)) {
            if (e.name == BACKUP_DIR) continue
            out += e
            if (e.isDir && recursive) queue += e.path
            if (out.size >= cap) break
        }
    }
    return out
}

private fun uniquePath(fs: VirtualFs, path: String): String {
    if (fs.stat(path) == null) return path
    val dir = PathValidator.parent(path); val n = PathValidator.nameOf(path)
    val base = n.substringBeforeLast('.', n); val ext = n.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var i = 2
    while (fs.stat(PathValidator.join(dir, "$base ($i)$ext")) != null) i++
    return PathValidator.join(dir, "$base ($i)$ext")
}

class ListFilesTool : AgentTool() {
    override val name = "list_files"
    override val description = "Listet Dateien (keine Ordner) in einem Ordner auf, optional rekursiv. Pfade sind relativ zum freigegebenen Ordner (\"\" = Wurzel)."
    override val parameters = schema(emptyList(),
        "path" to prop("string", "Ordnerpfad, Standard: Wurzel"),
        "recursive" to prop("boolean", "Auch Unterordner durchsuchen (Standard false)"),
        "extension" to prop("string", "Optional: nur diese Dateiendung, z. B. pdf"),
        "limit" to prop("integer", "Max. Anzahl Ergebnisse (Standard 200)"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = PathValidator.normalize(args.str("path")); val limit = args.int("limit", 200).coerceIn(1, 1000)
        val ext = args.str("extension")?.trim()?.trimStart('.')?.lowercase()
        val files = walk(ctx.fs, path, args.bool("recursive", false)).filter { !it.isDir && (ext == null || FileTypes.ext(it.name) == ext) }
        val shown = files.take(limit)
        return ToolResult(true, if (files.isEmpty()) "Keine Dateien gefunden in '$path'." else shown.joinToString("\n") { fmtEntry(it) } + (if (files.size > limit) "\n… ${files.size - limit} weitere" else "") + "\nGesamt: ${files.size} Datei(en).")
    }
}

class ListDirectoriesTool : AgentTool() {
    override val name = "list_directories"
    override val description = "Listet Unterordner auf, optional rekursiv."
    override val parameters = schema(emptyList(), "path" to prop("string", "Ordnerpfad, Standard: Wurzel"), "recursive" to prop("boolean", "Rekursiv (Standard false)"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val dirs = walk(ctx.fs, PathValidator.normalize(args.str("path")), args.bool("recursive", false)).filter { it.isDir }
        return ToolResult(true, if (dirs.isEmpty()) "Keine Unterordner." else dirs.joinToString("\n") { it.path } + "\nGesamt: ${dirs.size} Ordner.")
    }
}

class GetFileInfoTool : AgentTool() {
    override val name = "get_file_info"
    override val description = "Liefert Metadaten (Typ, Größe, Änderungsdatum) und ob die Datei bereits früher analysiert wurde."
    override val parameters = schema(listOf("path"), "path" to prop("string", "Pfad der Datei/des Ordners"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val p = PathValidator.normalize(args.reqStr("path"))
        val e = ctx.fs.stat(p) ?: return ToolResult(false, "Nicht gefunden: '$p'")
        val prev = ctx.memory?.analyzedInfo(p)
        val note = when { prev == null -> "noch nie analysiert"; prev.fileModified == e.modified && prev.fileSize == e.size -> "bereits analysiert (${fmtDate(prev.ts)}), seitdem unverändert"; else -> "früher analysiert, seitdem geändert" }
        return ToolResult(true, "Pfad: ${e.path.ifEmpty { "/" }}\nTyp: ${if (e.isDir) "Ordner" else (e.mime ?: FileTypes.ext(e.name))}\nGröße: ${e.size} Bytes\nGeändert: ${fmtDate(e.modified)}\nStatus: $note", paths = listOf(p))
    }
}

private suspend fun readText(ctx: ToolContext, path: String): Pair<FsEntry, de.dateiagent.extract.Extracted> {
    val e = ctx.fs.stat(path) ?: throw FsException("Nicht gefunden: '$path'")
    if (e.isDir) throw FsException("'$path' ist ein Ordner.")
    val bytes = ctx.fs.readBytes(path, MAX_READ_BYTES)
    val ex = ctx.extractor.extract(e.name, bytes)
    ctx.memory?.markAnalyzed(path, e.modified, e.size, ctx.taskId)
    return e to ex
}

private fun chunked(path: String, text: String, note: String, chunk: Int, size: Int): ToolResult {
    val parts = TextExtractor.chunk(text, size)
    if (chunk !in parts.indices) return ToolResult(false, "Abschnitt $chunk existiert nicht; '$path' hat ${parts.size} Abschnitt(e) (0-basiert).")
    val head = "Datei: $path · Abschnitt ${chunk + 1}/${parts.size} · ${text.length} Zeichen gesamt" + (if (note.isNotEmpty()) "\nHinweis: $note" else "") +
        (if (chunk + 1 < parts.size) "\n(Weiter mit chunk=${chunk + 1})" else "") + "\n---\n"
    return ToolResult(true, head + parts[chunk], paths = listOf(path), transmitsFileContent = true)
}

class ReadFileTool : AgentTool() {
    override val name = "read_file"
    override val description = "Liest eine Datei. Textformate, PDF, DOCX, XLSX werden als Text geliefert (große Dateien in Abschnitten, Parameter chunk). Bilder (jpg/png) werden zur Bildanalyse an die KI übergeben."
    override val parameters = schema(listOf("path"),
        "path" to prop("string", "Dateipfad"),
        "chunk" to prop("integer", "Abschnittsnummer ab 0 (Standard 0)"),
        "chunk_size" to prop("integer", "Zeichen pro Abschnitt (Standard 12000)"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = PathValidator.normalize(args.reqStr("path"))
        val e = ctx.fs.stat(path) ?: return ToolResult(false, "Nicht gefunden: '$path'")
        if (FileTypes.ext(e.name) in FileTypes.images) {
            if (!ctx.imageConsent) return ToolResult(false, "Bildanalyse ist nicht freigegeben. Der Nutzer kann sie in den KI-Einstellungen erlauben (Bild wird dann an die Cloud-KI gesendet).")
            val enc = ctx.images ?: return ToolResult(false, "Bildverarbeitung auf dieser Plattform nicht verfügbar.")
            val url = enc.toJpegDataUrl(ctx.fs.readBytes(path, MAX_READ_BYTES))
            ctx.memory?.markAnalyzed(path, e.modified, e.size, ctx.taskId)
            return ToolResult(true, "Bild '$path' wurde der KI zur Analyse angehängt.", images = listOf(url), paths = listOf(path), transmitsFileContent = true)
        }
        val (_, ex) = readText(ctx, path)
        return chunked(path, ex.text, ex.note, args.int("chunk", 0), args.int("chunk_size", DEFAULT_CHUNK).coerceIn(1000, 40_000))
    }
}

class ExtractTextTool : AgentTool() {
    override val name = "extract_text"
    override val description = "Extrahiert Text aus PDF, DOCX, XLSX, HTML usw. (abschnittsweise). Für XLSX werden Zellen tab-getrennt je Blatt ausgegeben."
    override val parameters = schema(listOf("path"), "path" to prop("string", "Dateipfad"), "chunk" to prop("integer", "Abschnitt ab 0"), "chunk_size" to prop("integer", "Zeichen pro Abschnitt (Standard 12000)"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = PathValidator.normalize(args.reqStr("path"))
        val (_, ex) = readText(ctx, path)
        return chunked(path, ex.text, ex.note, args.int("chunk", 0), args.int("chunk_size", DEFAULT_CHUNK).coerceIn(1000, 40_000))
    }
}

class SearchFilesTool : AgentTool() {
    override val name = "search_files"
    override val description = "Durchsucht Dateien rekursiv nach Namen und (optional) Inhalten (Text, PDF, DOCX, XLSX). Gibt Treffer mit Textausschnitt zurück."
    override val parameters = schema(listOf("query"),
        "query" to prop("string", "Suchbegriff (Groß-/Kleinschreibung egal)"),
        "path" to prop("string", "Startordner, Standard Wurzel"),
        "search_content" to prop("boolean", "Auch Dateiinhalte durchsuchen (Standard true)"),
        "extension" to prop("string", "Optional: nur diese Endung"),
        "max_results" to prop("integer", "Standard 30"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = args.reqStr("query").trim().lowercase(); if (q.isEmpty()) return ToolResult(false, "Leerer Suchbegriff.")
        val ext = args.str("extension")?.trim()?.trimStart('.')?.lowercase(); val max = args.int("max_results", 30).coerceIn(1, 200)
        val content = args.bool("search_content", true)
        val lines = ArrayList<String>(); var scanned = 0; var skipped = 0; var transmits = false
        for (e in walk(ctx.fs, PathValidator.normalize(args.str("path")), true).filter { !it.isDir && (ext == null || FileTypes.ext(it.name) == ext) }) {
            if (lines.size >= max) break
            if (e.name.lowercase().contains(q)) { lines += "NAME: ${e.path}"; continue }
            if (!content || FileTypes.ext(e.name) !in FileTypes.supported - FileTypes.images || e.size > 5L * 1024 * 1024) continue
            if (scanned >= 400) { skipped++; continue }
            scanned++
            val text = try { ctx.extractor.extract(e.name, ctx.fs.readBytes(e.path, 5L * 1024 * 1024)).text } catch (_: Exception) { continue }
            val i = text.lowercase().indexOf(q)
            if (i >= 0) { lines += "INHALT: ${e.path} … ${text.substring(maxOf(0, i - 60), minOf(text.length, i + q.length + 80)).replace('\n', ' ')} …"; transmits = true }
        }
        val tail = if (skipped > 0) "\nHinweis: $skipped Dateien wegen Limit (400 Inhaltsprüfungen) nicht durchsucht – Suche eingrenzen." else ""
        return ToolResult(true, if (lines.isEmpty()) "Keine Treffer für '$q'.$tail" else lines.joinToString("\n") + "\n${lines.size} Treffer.$tail", transmitsFileContent = transmits)
    }
}

class CreateFileTool : AgentTool() {
    override val name = "create_file"
    override val description = "Erstellt eine neue Datei (fehlende Ordner werden angelegt). Für .xlsx wird 'content' als CSV (Komma oder Semikolon, erste Zeile = Kopfzeile) in eine echte Excel-Datei umgewandelt; für .docx wird Text (eine Zeile = ein Absatz) in ein Word-Dokument umgewandelt; sonst Text. Existiert die Datei, schlägt der Aufruf fehl, außer overwrite=true (dann Bestätigung durch den Nutzer, Backup wird angelegt)."
    override val parameters = schema(listOf("path", "content"), "path" to prop("string", "Zielpfad inkl. Dateiname"), "content" to prop("string", "Inhalt"), "overwrite" to prop("boolean", "Vorhandene Datei überschreiben (Standard false)"))
    override fun approvalPrompt(args: JsonObject, ctx: ToolContext): String? {
        if (!args.bool("overwrite", false)) return null
        val p = runCatching { PathValidator.normalize(args.str("path")) }.getOrNull() ?: return null
        return if (ctx.fs.stat(p) != null) "Die KI möchte die vorhandene Datei \"$p\" überschreiben (ein Backup wird in $BACKUP_DIR/ angelegt)." else null
    }
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = PathValidator.normalize(args.reqStr("path")); val content = args.reqStr("content")
        val overwrite = args.bool("overwrite", false)
        val existing = ctx.fs.stat(path)
        if (existing != null && existing.isDir) return ToolResult(false, "'$path' ist ein Ordner.")
        if (existing != null && !overwrite) return ToolResult(false, "Datei existiert bereits: '$path'. Wähle einen anderen Namen oder setze overwrite=true.")
        ctx.fs.ensureDirs(PathValidator.parent(path))
        if (existing != null) backup(ctx.fs, path, ctx.now())
        val bytes = encode(path, content)
        val written = ctx.fs.writeBytes(path, bytes, overwrite)
        if (written.size != bytes.size.toLong()) return ToolResult(false, "Verifikation fehlgeschlagen: geschrieben ${bytes.size} B, gefunden ${written.size} B.", paths = listOf(path), mutated = true)
        return ToolResult(true, "Datei erstellt und verifiziert: $path (${written.size} Bytes).", paths = listOf(path), mutated = true)
    }
    companion object {
        fun encode(path: String, content: String): ByteArray = when (FileTypes.ext(path)) {
            "xlsx" -> OfficeWriter.xlsx(OfficeWriter.parseCsv(content))
            "docx" -> OfficeWriter.docx(content)
            "pdf", "jpg", "jpeg", "png" -> throw FsException("Dateien dieses Typs können nicht aus Text erzeugt werden.")
            else -> content.toByteArray(Charsets.UTF_8)
        }
    }
}

fun backup(fs: VirtualFs, path: String, now: Long) {
    fs.ensureDirs(BACKUP_DIR)
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
    val target = uniquePath(fs, PathValidator.join(BACKUP_DIR, "${stamp}_${PathValidator.nameOf(path)}"))
    fs.writeBytes(target, fs.readBytes(path), false)
}

class UpdateFileTool : AgentTool() {
    override val name = "update_file"
    override val description = "Bearbeitet eine vorhandene TEXT-Datei (txt, md, csv, json, xml, html). Modi: append (anhängen), replace_text (find → replace, find muss genau einmal vorkommen), overwrite (kompletter neuer Inhalt; erfordert Bestätigung). Vor jeder Änderung wird ein Backup angelegt."
    override val parameters = schema(listOf("path", "mode"),
        "path" to prop("string", "Dateipfad"),
        "mode" to prop("string", "Bearbeitungsmodus", listOf("append", "replace_text", "overwrite")),
        "content" to prop("string", "Text für append/overwrite"),
        "find" to prop("string", "Zu ersetzender Text (replace_text)"),
        "replace" to prop("string", "Ersatztext (replace_text)"))
    override fun approvalPrompt(args: JsonObject, ctx: ToolContext): String? =
        if (args.str("mode") == "overwrite") "Die KI möchte den kompletten Inhalt von \"${args.str("path")}\" ersetzen (Backup in $BACKUP_DIR/)." else null
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = PathValidator.normalize(args.reqStr("path")); val mode = args.reqStr("mode")
        val e = ctx.fs.stat(path) ?: return ToolResult(false, "Nicht gefunden: '$path'")
        if (e.isDir) return ToolResult(false, "'$path' ist ein Ordner.")
        if (FileTypes.ext(e.name) !in FileTypes.text) return ToolResult(false, "update_file unterstützt nur Textdateien. Für Office-Dateien eine neue Version mit create_file (overwrite=true) erzeugen.")
        val old = ctx.extractor.decode(ctx.fs.readBytes(path, MAX_READ_BYTES))
        val new = when (mode) {
            "append" -> old + (if (old.endsWith("\n") || old.isEmpty()) "" else "\n") + args.reqStr("content")
            "overwrite" -> args.reqStr("content")
            "replace_text" -> {
                val f = args.reqStr("find"); val n = Regex.escape(f).toRegex().findAll(old).count()
                if (n == 0) return ToolResult(false, "Text zum Ersetzen nicht gefunden."); if (n > 1) return ToolResult(false, "Text kommt $n× vor; 'find' muss eindeutig sein.")
                old.replace(f, args.str("replace") ?: "")
            }
            else -> return ToolResult(false, "Unbekannter Modus '$mode'.")
        }
        backup(ctx.fs, path, ctx.now())
        val bytes = new.toByteArray(Charsets.UTF_8)
        val w = ctx.fs.writeBytes(path, bytes, true)
        val check = ctx.extractor.decode(ctx.fs.readBytes(path, MAX_READ_BYTES))
        if (w.size != bytes.size.toLong() || check != new) return ToolResult(false, "Verifikation fehlgeschlagen: Inhalt weicht ab. Backup liegt in $BACKUP_DIR/.", paths = listOf(path), mutated = true)
        return ToolResult(true, "Datei aktualisiert und verifiziert: $path (${w.size} Bytes, Backup angelegt).", paths = listOf(path), mutated = true)
    }
}

class RenameFileTool : AgentTool() {
    override val name = "rename_file"
    override val description = "Benennt eine Datei oder einen Ordner um (nur neuer Name, kein Pfad)."
    override val parameters = schema(listOf("path", "new_name"), "path" to prop("string", "Aktueller Pfad"), "new_name" to prop("string", "Neuer Name ohne Pfad"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val p = PathValidator.normalize(args.reqStr("path")); val r = ctx.fs.rename(p, args.reqStr("new_name"))
        return ToolResult(true, "Umbenannt: $p → ${r.path} (verifiziert).", paths = listOf(p, r.path), mutated = true)
    }
}

class CopyFileTool : AgentTool() {
    override val name = "copy_file"
    override val description = "Kopiert eine Datei an einen neuen Pfad (Zielordner wird bei Bedarf angelegt). Überschreibt nie."
    override val parameters = schema(listOf("from", "to"), "from" to prop("string", "Quellpfad"), "to" to prop("string", "Zielpfad inkl. Dateiname"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val from = PathValidator.normalize(args.reqStr("from")); val to = PathValidator.normalize(args.reqStr("to"))
        ctx.fs.ensureDirs(PathValidator.parent(to))
        val src = ctx.fs.stat(from) ?: return ToolResult(false, "Quelle nicht gefunden: '$from'")
        val r = ctx.fs.copy(from, to)
        if (r.size != src.size) return ToolResult(false, "Verifikation fehlgeschlagen: Größe weicht ab.", paths = listOf(from, to), mutated = true)
        return ToolResult(true, "Kopiert: $from → ${r.path} (verifiziert, ${r.size} B).", paths = listOf(from, r.path), mutated = true)
    }
}

class MoveFileTool : AgentTool() {
    override val name = "move_file"
    override val description = "Verschiebt eine Datei an einen neuen Pfad (Zielordner wird bei Bedarf angelegt). Überschreibt nie."
    override val parameters = schema(listOf("from", "to"), "from" to prop("string", "Quellpfad"), "to" to prop("string", "Zielpfad inkl. Dateiname"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val from = PathValidator.normalize(args.reqStr("from")); val to = PathValidator.normalize(args.reqStr("to"))
        if (ctx.fs.stat(from) == null) return ToolResult(false, "Quelle nicht gefunden: '$from'")
        ctx.fs.ensureDirs(PathValidator.parent(to))
        val r = ctx.fs.move(from, to)
        return ToolResult(true, "Verschoben: $from → ${r.path} (verifiziert).", paths = listOf(from, r.path), mutated = true)
    }
}

class CreateDirectoryTool : AgentTool() {
    override val name = "create_directory"
    override val description = "Erstellt einen Ordner (inkl. fehlender Zwischenordner)."
    override val parameters = schema(listOf("path"), "path" to prop("string", "Ordnerpfad"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val p = PathValidator.normalize(args.reqStr("path")); if (p.isEmpty()) return ToolResult(false, "Kein Ordnername.")
        ctx.fs.ensureDirs(p)
        return ToolResult(ctx.fs.stat(p)?.isDir == true, "Ordner vorhanden/erstellt (verifiziert): $p", paths = listOf(p), mutated = true)
    }
}

class DeleteFileTool : AgentTool() {
    override val name = "delete_file"
    override val description = "Löscht eine Datei oder einen leeren/nicht leeren Ordner. Erfordert IMMER Bestätigung durch den Nutzer. Nur verwenden, wenn der Nutzer das Löschen ausdrücklich wünscht."
    override val parameters = schema(listOf("path"), "path" to prop("string", "Pfad"))
    override fun approvalPrompt(args: JsonObject, ctx: ToolContext): String {
        val p = runCatching { PathValidator.normalize(args.str("path")) }.getOrDefault(args.str("path") ?: "?")
        val e = runCatching { ctx.fs.stat(p) }.getOrNull()
        return "Die KI möchte " + (if (e?.isDir == true) "den Ordner" else "die Datei") + " \"$p\" unwiderruflich LÖSCHEN."
    }
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val p = PathValidator.normalize(args.reqStr("path"))
        if (ctx.fs.stat(p) == null) return ToolResult(false, "Nicht gefunden: '$p'")
        ctx.fs.delete(p)
        return ToolResult(true, "Gelöscht (verifiziert): $p", paths = listOf(p), mutated = true)
    }
}

class GenerateReportTool : AgentTool() {
    override val name = "generate_report"
    override val description = "Speichert einen Bericht als Datei (.md, .txt, .html oder .docx je nach Endung). Existiert der Name schon, wird automatisch ein freier Name gewählt (nichts wird überschrieben)."
    override val parameters = schema(listOf("path", "title", "content"), "path" to prop("string", "Zielpfad, z. B. Berichte/Zusammenfassung.md"), "title" to prop("string", "Titel"), "content" to prop("string", "Berichtstext (Markdown)"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        var path = PathValidator.normalize(args.reqStr("path")); val title = args.reqStr("title"); val body = args.reqStr("content")
        if (path.isEmpty() || FileTypes.ext(path) !in setOf("md", "txt", "html", "docx")) path += ".md"
        ctx.fs.ensureDirs(PathValidator.parent(path)); path = uniquePath(ctx.fs, path)
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(ctx.now()))
        val bytes = when (FileTypes.ext(path)) {
            "html" -> "<!doctype html><html lang=\"de\"><meta charset=\"utf-8\"><title>${title.escHtml()}</title><body><h1>${title.escHtml()}</h1><p><small>Erstellt: $stamp</small></p><pre style=\"white-space:pre-wrap;font-family:sans-serif\">${body.escHtml()}</pre></body></html>".toByteArray()
            "docx" -> OfficeWriter.docx("$title\nErstellt: $stamp\n\n$body")
            "txt" -> "$title\nErstellt: $stamp\n\n$body".toByteArray()
            else -> "# $title\n\n_Erstellt:${stamp}_\n\n$body\n".toByteArray()
        }
        val w = ctx.fs.writeBytes(path, bytes, false)
        if (w.size != bytes.size.toLong()) return ToolResult(false, "Verifikation fehlgeschlagen.", paths = listOf(path), mutated = true)
        return ToolResult(true, "Bericht gespeichert und verifiziert: $path (${w.size} B).", paths = listOf(path), mutated = true)
    }
    private fun String.escHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

class RememberTool : AgentTool() {
    override val name = "remember"
    override val description = "Speichert eine Information dauerhaft im lokalen Gedächtnis (z. B. Arbeitsweise des Nutzers, Notiz zu einem Dokument)."
    override val parameters = schema(listOf("kind", "text"), "kind" to prop("string", "Art", listOf("PREFERENCE", "FILE_NOTE", "FACT")), "key" to prop("string", "Stichwort/Dateipfad"), "text" to prop("string", "Zu merkender Text"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val mem = ctx.memory ?: return ToolResult(false, "Gedächtnis nicht verfügbar.")
        mem.remember(args.reqStr("kind"), args.str("key") ?: "", args.reqStr("text"), ctx.taskId)
        return ToolResult(true, "Gespeichert.")
    }
}

class RecallTool : AgentTool() {
    override val name = "recall"
    override val description = "Durchsucht das lokale Gedächtnis nach früheren Aufgaben, Ergebnissen und Notizen."
    override val parameters = schema(listOf("query"), "query" to prop("string", "Suchbegriffe"))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val mem = ctx.memory ?: return ToolResult(false, "Gedächtnis nicht verfügbar.")
        val r = mem.recall(args.reqStr("query"), 6)
        return ToolResult(true, if (r.isEmpty()) "Keine passenden Erinnerungen." else r.joinToString("\n---\n") { "[${it.kind}] ${it.text}" }, transmitsFileContent = r.isNotEmpty())
    }
}

object ToolRegistry {
    fun default(): List<AgentTool> = listOf(
        ListFilesTool(), ListDirectoriesTool(), ReadFileTool(), SearchFilesTool(), ExtractTextTool(),
        CreateFileTool(), UpdateFileTool(), RenameFileTool(), CopyFileTool(), MoveFileTool(),
        CreateDirectoryTool(), GetFileInfoTool(), GenerateReportTool(), DeleteFileTool(), RememberTool(), RecallTool(),
    )
}
