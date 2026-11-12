package de.dateiagent.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

sealed class FolderStatus {
    object NoFolder : FolderStatus()
    data class Valid(val uri: Uri, val displayName: String) : FolderStatus()
    data class Invalid(val reason: String) : FolderStatus()
}

/** Verwaltet den freigegebenen Ordner und die dauerhafte (persistable) URI-Berechtigung. */
class FolderAccess(private val context: Context, private val prefs: de.dateiagent.data.SettingsStore) {

    fun persist(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Alte Freigaben dieser App entfernen, damit nur EIN Ordner freigegeben ist.
        prefs.folderUri?.let { old ->
            if (old != uri.toString()) runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags)
            }
        }
        context.contentResolver.takePersistableUriPermission(uri, flags)
        prefs.folderUri = uri.toString()
    }

    fun release() {
        prefs.folderUri?.let {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        prefs.folderUri = null
    }

    fun status(): FolderStatus {
        val s = prefs.folderUri ?: return FolderStatus.NoFolder
        val uri = Uri.parse(s)
        val perm = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        if (perm == null || !perm.isReadPermission || !perm.isWritePermission)
            return FolderStatus.Invalid("Die Berechtigung für den Ordner wurde widerrufen oder ist abgelaufen. Bitte den Ordner erneut freigeben.")
        val doc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        if (doc == null || !doc.exists() || !doc.isDirectory)
            return FolderStatus.Invalid("Der freigegebene Ordner existiert nicht mehr oder ist nicht erreichbar (z. B. SD-Karte entfernt). Bitte neu freigeben.")
        if (!doc.canRead() || !doc.canWrite())
            return FolderStatus.Invalid("Für den Ordner fehlen Lese- oder Schreibrechte. Bitte neu freigeben.")
        return FolderStatus.Valid(uri, doc.name ?: "Ordner")
    }

    /** Liefert das Dateisystem oder wirft [FsException] mit verständlicher Meldung. */
    fun openFs(): VirtualFs = when (val st = status()) {
        is FolderStatus.Valid -> SafFileSystem(context, st.uri)
        is FolderStatus.Invalid -> throw FsException(st.reason)
        FolderStatus.NoFolder -> throw FsException("Es wurde noch kein Ordner freigegeben.")
    }
}
