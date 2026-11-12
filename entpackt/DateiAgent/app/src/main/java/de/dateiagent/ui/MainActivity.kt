package de.dateiagent.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.dateiagent.agent.PlanStep
import de.dateiagent.ai.Providers
import de.dateiagent.data.*
import de.dateiagent.fs.FolderStatus
import de.dateiagent.fs.FsEntry
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { AppRoot() } }
    }
}

enum class Screen(val title: String) {
    Chat("KI-Chat"), Files("Dateimanager"), Folder("Freigegebener Ordner"), Tasks("Aufgaben"),
    Log("Aktivitätsprotokoll"), Settings("KI-Einstellungen"), Permissions("Berechtigungen")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    var screen by remember { mutableStateOf(if (vm.folderStatus is FolderStatus.NoFolder) Screen.Folder else Screen.Chat) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshFolder() }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet {
            Text("DateiAgent", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
            Screen.values().forEach { s ->
                NavigationDrawerItem(label = { Text(s.title) }, selected = s == screen, onClick = { screen = s; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(screen.title) }, navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menü") } })
        }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (screen) {
                    Screen.Chat -> ChatScreen(vm) { screen = it }
                    Screen.Files -> FilesScreen(vm)
                    Screen.Folder -> FolderScreen(vm)
                    Screen.Tasks -> TasksScreen(vm)
                    Screen.Log -> LogScreen(vm)
                    Screen.Settings -> SettingsScreen(vm)
                    Screen.Permissions -> PermissionsScreen(vm)
                }
            }
        }
    }
}

private val fmt = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)
private val json = Json { ignoreUnknownKeys = true }
private fun plan(t: TaskEntity): List<PlanStep> = runCatching { json.decodeFromString(ListSerializer(PlanStep.serializer()), t.planJson) }.getOrDefault(emptyList())
private fun statusLabel(s: String) = when (s) {
    TaskStatus.QUEUED -> "Wartet"; TaskStatus.RUNNING -> "Läuft"; TaskStatus.WAITING_APPROVAL -> "Wartet auf Bestätigung"
    TaskStatus.PAUSED -> "Pausiert"; TaskStatus.COMPLETED -> "Abgeschlossen"; TaskStatus.FAILED -> "Fehlgeschlagen"; TaskStatus.CANCELLED -> "Abgebrochen"; else -> s
}

// ------------------------------------------------------------------------- Chat
@Composable
fun ChatScreen(vm: MainViewModel, go: (Screen) -> Unit) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val problem = vm.setupProblem
    Column(Modifier.fillMaxSize()) {
        if (problem != null) {
            Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Einrichtung unvollständig", style = MaterialTheme.typography.titleSmall)
                    Text(problem)
                    Row { TextButton({ go(Screen.Folder) }) { Text("Ordner") }; TextButton({ go(Screen.Settings) }) { Text("KI-Einstellungen") } }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tasks.isEmpty()) item { Text("Gib mir eine Aufgabe, z. B.:\n„Finde alle Rechnungen aus 2025 und erstelle eine Excel-Übersicht mit Datum, Rechnungsnummer, Firma und Betrag.“", color = MaterialTheme.colorScheme.outline) }
            items(tasks, key = { it.id }) { TaskCard(it, vm, compact = false) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Aufgabe in normaler Sprache …") }, maxLines = 5)
            IconButton(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank() && problem == null) { Icon(Icons.Default.Send, "Senden") }
        }
    }
}

@Composable
fun TaskCard(t: TaskEntity, vm: MainViewModel, compact: Boolean) {
    val steps = plan(t); val done = steps.count { it.status == "done" || it.status == "skipped" }
    var expanded by remember { mutableStateOf(!compact) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t.prompt, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(statusLabel(t.status)) })
                Spacer(Modifier.width(8.dp)); Text(fmt.format(Date(t.createdAt)), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
            if (t.status in TaskStatus.active || t.status == TaskStatus.PAUSED) {
                if (steps.isNotEmpty()) LinearProgressIndicator(progress = { done / steps.size.toFloat() }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (t.currentStep.isNotBlank()) "Schritt: ${t.currentStep}" else "Plane …", fontSize = 13.sp)
            }
            if (t.approvalState == Approval.PENDING) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, null); Spacer(Modifier.width(6.dp)); Text("Bestätigung erforderlich", style = MaterialTheme.typography.titleSmall) }
                        Text(t.approvalText)
                        Row { Button({ vm.approve(t.id, true) }) { Text("Erlauben") }; Spacer(Modifier.width(8.dp)); OutlinedButton({ vm.approve(t.id, false) }) { Text("Ablehnen") } }
                    }
                }
            }
            if (steps.isNotEmpty()) {
                TextButton({ expanded = !expanded }) { Text(if (expanded) "Plan ausblenden" else "Plan anzeigen ($done/${steps.size})") }
                if (expanded) steps.forEachIndexed { i, s -> Text((when (s.status) { "done" -> "✓ "; "in_progress" -> "▶ "; "skipped" -> "– "; else -> "○ " }) + "${i + 1}. ${s.text}", fontSize = 13.sp) }
            }
            if (t.resultText.isNotBlank()) { HorizontalDivider(); Text(t.resultText, fontSize = 14.sp) }
            if (t.error.isNotBlank()) Text(t.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Row {
                if (t.status in listOf(TaskStatus.RUNNING, TaskStatus.QUEUED)) TextButton({ vm.pause(t.id) }) { Text("Pausieren") }
                if (t.status == TaskStatus.PAUSED || t.status == TaskStatus.FAILED) TextButton({ vm.resume(t.id) }) { Icon(Icons.Default.PlayArrow, null); Text("Fortsetzen") }
                if (t.status in TaskStatus.active || t.status == TaskStatus.PAUSED) TextButton({ vm.cancel(t.id) }) { Text("Abbrechen") }
            }
        }
    }
}

// ------------------------------------------------------------------------- Aufgaben
@Composable
fun TasksScreen(vm: MainViewModel) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tasks.isEmpty()) item { Text("Noch keine Aufgaben.") }
        items(tasks, key = { it.id }) { t ->
            Column { TaskCard(t, vm, compact = true)
                if (t.status !in TaskStatus.active) TextButton({ vm.delete(t.id) }) { Icon(Icons.Default.Delete, null); Text("Aus Liste entfernen") } }
        }
    }
}

// ------------------------------------------------------------------------- Ordner
@Composable
fun FolderScreen(vm: MainViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onFolderPicked(it) }
    val st = vm.folderStatus
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Die App arbeitet ausschließlich innerhalb des Ordners, den du hier freigibst. Die Freigabe bleibt auch nach einem Neustart erhalten und kann jederzeit widerrufen werden.")
        when (st) {
            FolderStatus.NoFolder -> Text("Noch kein Ordner freigegeben.", color = MaterialTheme.colorScheme.error)
            is FolderStatus.Invalid -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Text(st.reason, Modifier.padding(12.dp)) }
            is FolderStatus.Valid -> Card { Column(Modifier.padding(12.dp)) { Text("Freigegeben: ${st.displayName}", style = MaterialTheme.typography.titleMedium); Text(st.uri.toString(), fontSize = 11.sp, fontFamily = FontFamily.Monospace); Text("Dauerhafte Berechtigung: aktiv ✓") } }
        }
        Button({ picker.launch(null) }) { Text(if (st is FolderStatus.Valid) "Anderen Ordner wählen" else "Ordner auswählen") }
        if (st !is FolderStatus.NoFolder) OutlinedButton({ vm.releaseFolder() }) { Text("Freigabe widerrufen") }
        Text("Tipp: Lege am besten einen eigenen Ordner (z. B. „KI-Ordner“) an und wähle diesen. Wähle nicht den Wurzelordner des Speichers oder „Download“ – Android erlaubt das aus Sicherheitsgründen teils nicht.", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
    }
}

// ------------------------------------------------------------------------- Dateimanager
@Composable
fun FilesScreen(vm: MainViewModel) {
    LaunchedEffect(vm.folderStatus) { if (vm.folderStatus is FolderStatus.Valid) vm.browse(vm.path) }
    var newDir by remember { mutableStateOf(false) }; var name by remember { mutableStateOf("") }
    var delTarget by remember { mutableStateOf<FsEntry?>(null) }
    var renTarget by remember { mutableStateOf<FsEntry?>(null) }
    Column(Modifier.fillMaxSize()) {
        if (vm.folderStatus !is FolderStatus.Valid) { Text("Bitte zuerst einen Ordner freigeben.", Modifier.padding(16.dp)); return }
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.up() }, enabled = vm.path.isNotEmpty()) { Icon(Icons.Default.ArrowBack, "Hoch") }
            Text("/" + vm.path, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            IconButton({ vm.browse(vm.path) }) { Icon(Icons.Default.Refresh, "Aktualisieren") }
            IconButton({ newDir = true; name = "" }) { Icon(Icons.Default.Add, "Neuer Ordner") }
        }
        vm.fmError?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.fillMaxSize()) {
            if (vm.entries.isEmpty() && vm.fmError == null) item { Text("Ordner ist leer.", Modifier.padding(16.dp)) }
            items(vm.entries, key = { it.path }) { e ->
                ListItem(
                    headlineContent = { Text((if (e.isDir) "📁 " else "📄 ") + e.name) },
                    supportingContent = { if (!e.isDir) Text("${e.size / 1024} KB · ${fmt.format(Date(e.modified))}") },
                    trailingContent = { Row { IconButton({ renTarget = e; name = e.name }) { Icon(Icons.Default.Edit, "Umbenennen") }; IconButton({ delTarget = e }) { Icon(Icons.Default.Delete, "Löschen") } } },
                    modifier = Modifier.clickable { if (e.isDir) vm.browse(e.path) else vm.openPreview(e) })
            }
        }
    }
    if (newDir) AlertDialog({ newDir = false }, confirmButton = { TextButton({ vm.makeDir(name); newDir = false }) { Text("Erstellen") } }, dismissButton = { TextButton({ newDir = false }) { Text("Abbrechen") } },
        title = { Text("Neuer Ordner") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) })
    renTarget?.let { e -> AlertDialog({ renTarget = null }, confirmButton = { TextButton({ vm.renameEntry(e, name); renTarget = null }) { Text("Umbenennen") } }, dismissButton = { TextButton({ renTarget = null }) { Text("Abbrechen") } },
        title = { Text("Umbenennen") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) }) }
    delTarget?.let { e -> AlertDialog({ delTarget = null }, confirmButton = { TextButton({ vm.deleteEntry(e); delTarget = null }) { Text("Löschen") } }, dismissButton = { TextButton({ delTarget = null }) { Text("Abbrechen") } },
        title = { Text("Wirklich löschen?") }, text = { Text("\"${e.path}\" wird unwiderruflich gelöscht.") }) }
    vm.preview?.let { (n, text) -> AlertDialog({ vm.preview = null }, confirmButton = { TextButton({ vm.preview = null }) { Text("Schließen") } }, title = { Text(n) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, fontSize = 12.sp) } }) }
}

// ------------------------------------------------------------------------- Protokoll
@Composable
fun LogScreen(vm: MainViewModel) {
    val acts by vm.activity.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Alle Aktionen der KI und des Nutzers. Dateiinhalte, die an die Cloud-KI gehen, sind als ÜBERTRAGUNG markiert.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) }
        items(acts, key = { it.id }) { a ->
            val label = when (a.kind) { "TRANSMIT" -> "☁ ÜBERTRAGUNG"; "APPROVAL" -> "✋ FREIGABE"; "ERROR" -> "⚠ FEHLER"; "MANUAL" -> "👤 MANUELL"; "TOOL" -> "🔧 ${a.tool}"; else -> "ℹ INFO" }
            Column { Text("${fmt.format(Date(a.ts))} · Aufgabe ${a.taskId} · $label ${if (!a.success) "(fehlgeschlagen)" else ""}", fontSize = 12.sp, color = if (a.success) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error)
                if (a.path.isNotBlank()) Text(a.path, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (a.detail.isNotBlank()) Text(a.detail, fontSize = 13.sp); HorizontalDivider(Modifier.padding(top = 4.dp)) }
        }
    }
}

// ------------------------------------------------------------------------- KI-Einstellungen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val mem by vm.memory.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("KI-Anbieter", style = MaterialTheme.typography.titleMedium)
        Providers.presets.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { vm.providerId = p.id; if (p.id != "custom") { vm.baseUrl = p.baseUrl; vm.model = p.defaultModel } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(vm.providerId == p.id, { vm.providerId = p.id; if (p.id != "custom") { vm.baseUrl = p.baseUrl; vm.model = p.defaultModel } }); Text(p.label)
            }
        }
        OutlinedTextField(vm.baseUrl, { vm.baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Basis-URL (…/v1)") }, singleLine = true, enabled = vm.providerId == "custom")
        OutlinedTextField(vm.model, { vm.model = it }, Modifier.fillMaxWidth(), label = { Text("Modell (frei wählbar, z. B. gpt-4o, gpt-4.1)") }, singleLine = true)
        OutlinedTextField(vm.apiKeyInput, { vm.apiKeyInput = it }, Modifier.fillMaxWidth(), label = { Text(if (vm.hasKey) "API-Schlüssel (gespeichert – zum Ersetzen neu eingeben)" else "API-Schlüssel (sk-…)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Text("Der Schlüssel wird verschlüsselt im Android Keystore abgelegt und ist nicht Teil der APK. Alternativ eine eigene Proxy-URL eintragen (siehe Doku).", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        if (vm.hasKey) TextButton({ vm.deleteKey() }) { Text("Gespeicherten Schlüssel löschen") }

        HorizontalDivider()
        Text("Datenschutz", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(vm.cloudConsent, { vm.cloudConsent = it }); Spacer(Modifier.width(8.dp)); Text("Ich erlaube, dass Inhalte von Dateien, die die KI für eine Aufgabe liest, an den oben gewählten KI-Anbieter gesendet werden. Jede Übertragung wird im Aktivitätsprotokoll vermerkt.", fontSize = 14.sp) }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(vm.imageConsent, { vm.imageConsent = it }); Spacer(Modifier.width(8.dp)); Text("Bildanalyse erlauben (Bilder werden verkleinert an die KI gesendet).", fontSize = 14.sp) }

        HorizontalDivider()
        OutlinedTextField(vm.maxIterations, { vm.maxIterations = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Max. Schritte pro Aufgabe") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(vm.workingStyle, { vm.workingStyle = it }, Modifier.fillMaxWidth(), label = { Text("Meine Arbeitsweise / Vorlieben (wird jeder Aufgabe mitgegeben)") }, minLines = 2)
        Row { Button({ vm.saveSettings() }) { Text("Speichern") }; Spacer(Modifier.width(8.dp)); OutlinedButton({ vm.testConnection() }) { Text("Verbindung testen") } }
        vm.testResult?.let { Text(it) }

        HorizontalDivider()
        Text("Gedächtnis (lokal, ${mem.size} Einträge)", style = MaterialTheme.typography.titleMedium)
        mem.take(15).forEach { m -> Row(verticalAlignment = Alignment.Top) { Text("[${m.kind}] ${m.text.take(160)}", Modifier.weight(1f), fontSize = 12.sp); IconButton({ vm.deleteMemory(m.id) }) { Icon(Icons.Default.Delete, "Eintrag löschen") } } }
        if (mem.isNotEmpty()) TextButton({ vm.clearMemory() }) { Text("Gedächtnis komplett löschen") }
    }
}

// ------------------------------------------------------------------------- Berechtigungen
@Composable
fun PermissionsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val st = vm.folderStatus
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ordnerzugriff", style = MaterialTheme.typography.titleMedium)
        Text(when (st) { FolderStatus.NoFolder -> "Kein Ordner freigegeben."; is FolderStatus.Invalid -> "Problem: ${st.reason}"; is FolderStatus.Valid -> "Aktiv für „${st.displayName}“ (Lesen + Schreiben, dauerhaft)." })
        Text("Die App besitzt keine allgemeine Speicher-Berechtigung. Sie sieht nur den freigegebenen Ordner.", fontSize = 13.sp)
        HorizontalDivider()
        Text("Benachrichtigungen", style = MaterialTheme.typography.titleMedium)
        Text("Nötig für die sichtbare Hintergrund-Benachrichtigung, Rückfragen und Ergebnisse.", fontSize = 13.sp)
        if (Build.VERSION.SDK_INT >= 33) Button({ notif.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Benachrichtigungen erlauben") }
        HorizontalDivider()
        Text("Hintergrundbetrieb", style = MaterialTheme.typography.titleMedium)
        Text("Lange Aufgaben laufen als Vordergrunddienst mit Benachrichtigung. Manche Hersteller beenden Apps aggressiv; schließe die App dann nicht per „Wischen“ und erlaube ggf. uneingeschränkten Akkuverbrauch.", fontSize = 13.sp)
        OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Akku-Einstellungen öffnen") }
        HorizontalDivider()
        Text("Externe Übertragung", style = MaterialTheme.typography.titleMedium)
        Text(if (vm.cloudConsent) "Erlaubt: Dateiinhalte, die die KI liest, gehen an ${vm.baseUrl}." else "Nicht erlaubt: Es werden keine Dateiinhalte übertragen (die KI kann dann keine Aufgaben ausführen).", fontSize = 13.sp)
        Text("Bildanalyse: " + if (vm.imageConsent) "erlaubt" else "aus", fontSize = 13.sp)
    }
}
