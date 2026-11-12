package de.dateiagent.fs

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * VirtualFs auf Basis des Storage Access Framework. Arbeitet ausschliesslich unterhalb des
 * vom Nutzer gewaehlten Baum-URIs; relative Pfade werden Segment fuer Segment aufgeloest,
 * es gibt keinen Weg, ueber den Baum hinaus zu gelangen.
 */
class SafFileSystem(context: Context, private val treeUri: Uri) : VirtualFs {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val appContext = context.applicationContext
    private val root: DocumentFile = DocumentFile.fromTreeUri(appContext, treeUri)
        ?: throw FsException("Der freigegebene Ordner ist nicht mehr erreichbar.")

    private fun resolve(rawPath: String): DocumentFile? {
        val path = PathValidator.normalize(rawPath)
        var cur: DocumentFile = root
        if (path.isEmpty()) return cur
        for (seg in path.split('/')) {
            cur = cur.listFiles().firstOrNull { it.name == seg } ?: return null
        }
        return cur
    }

    private fun require(path: String): DocumentFile =
        resolve(path) ?: throw FsException("Nicht gefunden: '${PathValidator.normalize(path)}'")

    private fun entry(path: String, f: DocumentFile) = FsEntry(
        path = PathValidator.normalize(path),
        name = f.name ?: "",
        isDir = f.isDirectory,
        size = if (f.isDirectory) 0 else f.length(),
        modified = f.lastModified(),
        mime = f.type,
    )

    override fun list(path: String): List<FsEntry> {
        val dir = require(path)
        if (!dir.isDirectory) throw FsException("'$path' ist kein Ordner.")
        val base = PathValidator.normalize(path)
        return dir.listFiles().mapNotNull { f ->
            val n = f.name ?: return@mapNotNull null
            entry(PathValidator.join(base, n), f)
        }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override fun stat(path: String): FsEntry? = resolve(path)?.let { entry(path, it) }

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        val f = require(path)
        if (f.isDirectory) throw FsException("'$path' ist ein Ordner.")
        resolver.openInputStream(f.uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw FsException("Datei zu groß (> ${maxBytes / 1024} KB) für diese Operation.")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
        throw FsException("Datei konnte nicht geöffnet werden: '$path'")
    }

    override fun writeBytes(path: String, data: ByteArray, overwrite: Boolean): FsEntry {
        val norm = PathValidator.normalize(path)
        if (norm.isEmpty()) throw FsException("Kein Dateiname angegeben.")
        val parent = require(PathValidator.parent(norm))
        if (!parent.isDirectory) throw FsException("Zielordner existiert nicht.")
        val name = PathValidator.validateName(PathValidator.nameOf(norm))
        val existing = parent.listFiles().firstOrNull { it.name == name }
        val target: DocumentFile = if (existing != null) {
            if (existing.isDirectory) throw FsException("'$norm' ist ein Ordner.")
            if (!overwrite) throw FsException("Datei existiert bereits: '$norm'")
            existing
        } else {
            parent.createFile(mimeFor(name), name) ?: throw FsException("Datei konnte nicht erstellt werden: '$norm'")
        }
        resolver.openOutputStream(target.uri, "wt")?.use { it.write(data); it.flush() }
            ?: throw FsException("Schreiben fehlgeschlagen: '$norm'")
        return stat(norm) ?: throw FsException("Verifikation fehlgeschlagen: Datei nach dem Schreiben nicht auffindbar.")
    }

    override fun mkdir(path: String): FsEntry {
        val norm = PathValidator.normalize(path)
        if (norm.isEmpty()) throw FsException("Kein Ordnername angegeben.")
        resolve(norm)?.let { if (it.isDirectory) return entry(norm, it) else throw FsException("Es existiert eine Datei mit diesem Namen.") }
        val parent = require(PathValidator.parent(norm))
        val name = PathValidator.validateName(PathValidator.nameOf(norm))
        parent.createDirectory(name) ?: throw FsException("Ordner konnte nicht erstellt werden: '$norm'")
        return stat(norm) ?: throw FsException("Verifikation fehlgeschlagen: Ordner nicht auffindbar.")
    }

    override fun delete(path: String) {
        val norm = PathValidator.normalize(path)
        if (norm.isEmpty()) throw FsException("Der freigegebene Ordner selbst kann nicht gelöscht werden.")
        val f = require(norm)
        if (!f.delete()) throw FsException("Löschen fehlgeschlagen: '$norm'")
        if (resolve(norm) != null) throw FsException("Verifikation fehlgeschlagen: '$norm' existiert noch.")
    }

    override fun rename(path: String, newName: String): FsEntry {
        val norm = PathValidator.normalize(path)
        if (norm.isEmpty()) throw FsException("Der freigegebene Ordner kann nicht umbenannt werden.")
        val name = PathValidator.validateName(newName)
        val parentPath = PathValidator.parent(norm)
        val f = require(norm)
        if (resolve(PathValidator.join(parentPath, name)) != null) throw FsException("Zielname existiert bereits: '$name'")
        if (!f.renameTo(name)) throw FsException("Umbenennen fehlgeschlagen.")
        return stat(PathValidator.join(parentPath, name)) ?: throw FsException("Verifikation fehlgeschlagen: umbenannte Datei nicht auffindbar.")
    }

    override fun copy(from: String, to: String): FsEntry {
        val src = require(from)
        if (src.isDirectory) throw FsException("Ordner kopieren wird nicht unterstützt; kopiere Dateien einzeln.")
        val target = PathValidator.normalize(to)
        if (resolve(target) != null) throw FsException("Ziel existiert bereits: '$target'")
        val data = readBytes(from)
        return writeBytes(target, data, overwrite = false)
    }

    override fun move(from: String, to: String): FsEntry {
        val srcPath = PathValidator.normalize(from)
        val dstPath = PathValidator.normalize(to)
        if (srcPath.isEmpty()) throw FsException("Der freigegebene Ordner kann nicht verschoben werden.")
        if (dstPath == srcPath || dstPath.startsWith("$srcPath/")) throw FsException("Ungültiges Ziel.")
        val src = require(srcPath)
        if (resolve(dstPath) != null) throw FsException("Ziel existiert bereits: '$dstPath'")
        val dstParentPath = PathValidator.parent(dstPath)
        val dstParent = require(dstParentPath)
        if (!dstParent.isDirectory) throw FsException("Zielordner existiert nicht.")
        val newName = PathValidator.validateName(PathValidator.nameOf(dstPath))
        val srcParent = require(PathValidator.parent(srcPath))
        var moved = false
        try {
            val newUri = DocumentsContract.moveDocument(resolver, src.uri, srcParent.uri, dstParent.uri)
            moved = newUri != null
        } catch (_: Exception) { /* Provider unterstützt Move nicht -> Fallback */ }
        if (!moved) {
            if (src.isDirectory) throw FsException("Dieser Speicheranbieter kann Ordner nicht verschieben.")
            writeBytes(dstPath, readBytes(srcPath), overwrite = false)
            if (!src.delete()) throw FsException("Verschieben unvollständig: Quelle konnte nicht gelöscht werden (Kopie liegt unter '$dstPath').")
        } else if (PathValidator.nameOf(srcPath) != newName) {
            val moved2 = resolve(PathValidator.join(dstParentPath, PathValidator.nameOf(srcPath)))
                ?: throw FsException("Verifikation fehlgeschlagen nach Verschieben.")
            if (!moved2.renameTo(newName)) throw FsException("Umbenennen nach Verschieben fehlgeschlagen.")
        }
        if (resolve(srcPath) != null) throw FsException("Verifikation fehlgeschlagen: Quelle existiert noch.")
        return stat(dstPath) ?: throw FsException("Verifikation fehlgeschlagen: Ziel nicht auffindbar.")
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "md", "log" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }
}
