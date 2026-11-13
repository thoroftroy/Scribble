@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.wonderdesk.scribble

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class MainActivity : WonderActivity() {
    @Composable override fun Content() = ScribbleApp()
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); pendingIntent.value = intent }
    companion object { val pendingIntent = mutableStateOf<Intent?>(null) }
}

/** What Android must allow for this app to work fully; shown as a banner when off and in Settings. */
private val APP_NEEDS by lazy { listOf(
    WonderNeed("All files access", "Finds and edits text and Markdown files anywhere on your phone", required = false, access = SpecialAccess.Kind.ALL_FILES),
    WonderNeed.notifications("Note reminders", required = false),
) }

private const val ABOUT = "Scribble keeps your notes, lists and sketches on this phone. Organise them in folders, pin the important ones, " +
    "search everything, and import or export plain text and Markdown files. Deleted notes stay in the Trash for 30 days."

@Composable
fun ScribbleApp() {
    val context = LocalContext.current
    val store = remember { NoteStore(context) }
    val notes = remember { mutableStateListOf<Note>() }
    var folders by remember { mutableStateOf(store.folders()) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("all") } // all | pinned | trash | folder:<name>
    var query by rememberSaveable { mutableStateOf("") }
    var sortRecent by rememberSaveable { mutableStateOf(context.prefs("scribble").getBoolean("sortRecent", true)) }
    var listMode by rememberSaveable { mutableStateOf(context.prefs("scribble").getBoolean("list", false)) }
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    val externals = remember { mutableStateListOf<Note>() }
    fun upsert(n: Note) {
        if (n.external.isNotEmpty()) {
            // A document from elsewhere on the phone: save straight back to its own file.
            val i = externals.indexOfFirst { it.id == n.id }
            if (i >= 0) externals[i] = n else externals.add(n)
            scope.launch(Dispatchers.IO) { runCatching { Documents.save(n) } }
            return
        }
        val i = notes.indexOfFirst { it.id == n.id }
        if (i >= 0) notes[i] = n else notes.add(0, n)
        scope.launch(Dispatchers.IO) { store.save(n); NotepadWidget.refresh(context) }
    }
    fun newNote(body: String = "", title: String = "", sketch: Boolean = false): Note {
        val n = Note(title = title, body = body, sketch = sketch, folder = filter.removePrefix("folder:").takeIf { filter.startsWith("folder:") } ?: "")
        upsert(n); openId = n.id; return n
    }
    LaunchedEffect(Unit) {
        val all = withContext(Dispatchers.IO) { store.all() }
        notes.addAll(all)
        // Bring over the single draft from older versions of Scribble.
        val old = context.getSharedPreferences("draft", Context.MODE_PRIVATE)
        val draft = old.getString("text", null)
        if (!draft.isNullOrBlank() && all.isEmpty()) {
            upsert(Note(title = old.getString("name", "")?.removeSuffix(".txt").orEmpty().takeIf { it != "Note" } ?: "", body = draft))
            old.edit().clear().apply()
        }
        loaded = true
    }
    val toast = remember { mutableStateOf<(String) -> Unit>({}) }
    // Shared text and opened files become new notes.
    val incoming by MainActivity.pendingIntent
    LaunchedEffect(loaded, incoming) {
        if (!loaded) return@LaunchedEffect
        val intent = incoming ?: (context as ComponentActivity).intent.also { (context as ComponentActivity).intent = Intent() }
        MainActivity.pendingIntent.value = null
        intent?.getStringExtra("open")?.let { id -> if (notes.any { it.id == id }) openId = id }
        if (intent?.getBooleanExtra("new", false) == true) newNote()
        when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { newNote(it.take(1_000_000), intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()) }
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                // With all-files access, edit the real file in place; otherwise bring a copy in as a note.
                val doc = if (Documents.canEditAll()) Documents.uriToPath(context, uri)?.let { Documents.open(it) } else null
                if (doc != null) { externals.removeAll { it.id == doc.id }; externals.add(doc); openId = doc.id }
                else runCatching { readText(context, uri) }.onSuccess { (t, name) -> newNote(t, name) }.onFailure { toast.value(it.message ?: "Couldn't open that file") }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { readText(context, uri) } }
                .onSuccess { (t, name) -> newNote(t, name); toast.value("Imported $name") }
                .onFailure { toast.value(it.message ?: "Couldn't read that file") }
        }
    }
    val open = openId?.let { id -> notes.find { it.id == id } ?: externals.find { it.id == id } }
    if (open != null) {
        if (open.sketch) SketchEditor(open, store, onChangeLocal = { n -> val i = notes.indexOfFirst { it.id == n.id }; if (i >= 0) notes[i] = n }, onClose = { openId = null }, onDelete = { upsert(open.copy(trashed = System.currentTimeMillis())); openId = null })
        else NoteEditor(open, folders, notes, onOpenLink = { t ->
            val target = notes.firstOrNull { it.trashed == 0L && it.displayTitle.equals(t, true) }
            if (target != null) openId = target.id else newNote(title = t)
        }, onChange = ::upsert, onClose = {
            if (open.external.isEmpty() && open.title.isBlank() && open.body.isBlank()) { notes.remove(open); scope.launch(Dispatchers.IO) { store.deleteForever(open) } }
            openId = null
        }, onDelete = {
            if (open.external.isNotEmpty()) { toast.value("This file lives in ${java.io.File(open.external).parentFile?.name}. Delete it in Wayfinder."); openId = null }
            else { upsert(open.copy(trashed = System.currentTimeMillis())); openId = null }
        }, onNewFolder = { f -> folders = (folders + f).distinct(); store.saveFolders(folders) })
        return
    }
    var newFolder by remember { mutableStateOf(false) }
    var allFilesGuide by remember { mutableStateOf(false) }
    if (allFilesGuide) AccessGuide(SpecialAccess.Kind.ALL_FILES, "Scribble uses it to find text and Markdown documents anywhere on your phone and to save edits back into them. Nothing leaves your phone.",
        onDismiss = { allFilesGuide = false }, onGranted = { filter = "device" })
    var confirmEmpty by remember { mutableStateOf(false) }
    fun reloadFrom(tree: android.net.Uri?) {
        loaded = false
        scope.launch {
            val list = withContext(Dispatchers.IO) { store.useFolder(tree); store.all() to store.folders() }
            notes.clear(); notes.addAll(list.first); folders = list.second; filter = "all"; loaded = true
            NotepadWidget.refresh(context)
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { t ->
        if (t != null) { runCatching { context.contentResolver.takePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }; reloadFrom(t) }
    }
    WonderApp(needs = APP_NEEDS, 
        appName = "Scribble", about = ABOUT,
        title = when { filter == "pinned" -> "Pinned"; filter == "device" -> "On this phone"; filter == "trash" -> "Trash"; filter.startsWith("folder:") -> filter.removePrefix("folder:"); else -> "Scribble" },
        actions = {
            if (filter != "trash") {
                ToolbarButton(if (listMode) WonderIcons.GridView else WonderIcons.ViewList, if (listMode) "Show as cards" else "Show as a list", {
                    listMode = !listMode; context.prefs("scribble").edit().putBoolean("list", listMode).apply() })
                ToolbarButton(WonderIcons.Brush, "New sketch", { newNote(sketch = true) })
                ToolbarButton(WonderIcons.NoteAdd, "New note", { newNote() })
            }
        },
        settings = {
            Panel(title = "Notes folder", footer = "Notes are plain Markdown and text files. Pick any folder, such as an Obsidian vault or Documents/Notes, to keep them where other apps and computers can open them.") {
                ListRow(store.fs.label, subtitle = if (!store.usesSharedFolder) "Private to Scribble" else if (store.prefs.getString("path", null) != null && Documents.canEditAll()) "Shared folder · fast direct access" else "Shared folder", icon = WonderIcons.Folder, tint = Color(0xFF4A90E2))
                PanelDivider()
                ListRow("Choose a folder…", icon = WonderIcons.FolderOpen, tint = Color(0xFF2E9E44), chevron = true, onClick = { folderPicker.launch(null) })
                if (store.usesSharedFolder) { PanelDivider(); ListRow("Go back to the private folder", icon = WonderIcons.Lock, tint = Color(0xFF7A7F87), onClick = { reloadFrom(null) }) }
            }
            Panel(title = "Documents on this phone", footer = "With all files access, Scribble lists every text and Markdown file on your phone and saves your edits straight into the original file.") {
                SwitchRow("All files access", Documents.canEditAll(), { allFilesGuide = true }, icon = WonderIcons.Storage, tint = Color(0xFF16A085))
            }
            Panel(title = "Editor") {
                var pv by remember { mutableStateOf(store.prefs.getBoolean("openInPreview", false)) }
                SwitchRow("Open notes in preview", pv, { pv = it; store.prefs.edit().putBoolean("openInPreview", it).apply() }, subtitle = "Show formatted Markdown first; tap Edit to change it")
            }
        },
        menu = listOfNotNull(
            WonderMenuItem("Import text file", WonderIcons.UploadFile) { importer.launch(arrayOf("text/*", "application/json", "application/xml")) },
            WonderMenuItem("New folder", WonderIcons.CreateNewFolder) { newFolder = true },
            WonderMenuItem(if (sortRecent) "Sort by title" else "Sort by last edited", WonderIcons.Sort) { sortRecent = !sortRecent; context.prefs("scribble").edit().putBoolean("sortRecent", sortRecent).apply() },
            if (filter == "trash") WonderMenuItem("Empty trash", WonderIcons.DeleteSweep, danger = true) { confirmEmpty = true } else null,
            if (filter.startsWith("folder:")) WonderMenuItem("Delete this folder", WonderIcons.Delete, danger = true) {
                val f = filter.removePrefix("folder:")
                notes.filter { it.folder == f }.forEach { upsert(it.copy(folder = "")) }
                folders = folders - f; store.saveFolders(folders); filter = "all"
            } else null,
        ),
    ) {
        toast.value = LocalToast.current
        val p = wonderPalette()
        val shown = remember(notes.toList(), filter, query, sortRecent) {
            notes.filter { n ->
                when {
                    filter == "trash" -> n.trashed > 0
                    n.trashed > 0 -> false
                    filter == "pinned" -> n.pinned
                    filter.startsWith("folder:") -> n.folder == filter.removePrefix("folder:")
                    else -> true
                } && (query.isBlank() || n.title.contains(query, true) || n.body.contains(query, true))
            }.sortedWith(compareByDescending<Note> { it.pinned && filter != "trash" }.then(if (sortRecent) compareByDescending { it.updated } else compareBy { it.displayTitle.lowercase() }))
        }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchField(query, { query = it }, "Search notes")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("All notes", filter == "all", { filter = "all" }, WonderIcons.Notes)
                    Chip("Pinned", filter == "pinned", { filter = "pinned" }, WonderIcons.PushPin)
                    Chip("On this phone", filter == "device", { filter = "device" }, WonderIcons.PhoneAndroid)
                    folders.forEach { f -> Chip(f, filter == "folder:$f", { filter = "folder:$f" }, WonderIcons.Folder) }
                    Chip("New folder", false, { newFolder = true }, WonderIcons.Add)
                    Chip("Trash", filter == "trash", { filter = "trash" }, WonderIcons.Delete)
                }
            }
            if (filter == "device") DeviceDocuments(query, needAccess = { allFilesGuide = true }) { path ->
                scope.launch {
                    val doc = withContext(Dispatchers.IO) { Documents.open(path) }
                    if (doc == null) toast.value("Couldn't open that file") else { externals.removeAll { it.id == doc.id }; externals.add(doc); openId = doc.id }
                }
            }
            else if (!loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
            else if (shown.isEmpty()) EmptyState(
                when { query.isNotBlank() -> "No matches"; filter == "trash" -> "Trash is empty"; filter == "pinned" -> "Nothing pinned"; else -> "No notes yet" },
                when { query.isNotBlank() -> "No note contains “$query”."; filter == "trash" -> "Deleted notes stay here for 30 days."; filter == "pinned" -> "Pin a note from its toolbar to keep it on top."; else -> "Write a note, start a checklist or draw a sketch." },
                action = if (filter != "trash" && query.isBlank()) ({ WButton("New note", { newNote() }, icon = WonderIcons.NoteAdd, kind = ButtonKind.Primary) }) else null
            ) else if (listMode) LazyColumn(Modifier.fillMaxSize(), state = rememberTabTopList(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // List view: swipe right to pin or unpin, left to move to the trash (with Undo).
                items(shown, key = { it.id }) { n -> Box(Modifier.animateItemPlacement()) {
                    val card = @Composable { NoteCard(n, store, onClick = { if (n.trashed == 0L) openId = n.id }, trash = n.trashed > 0,
                        onRestore = { val r = n.copy(trashed = 0L); val i = notes.indexOfFirst { it.id == n.id }; if (i >= 0) notes[i] = r; scope.launch(Dispatchers.IO) { store.restore(r) } },
                        onDeleteForever = { notes.remove(n); scope.launch(Dispatchers.IO) { store.deleteForever(n) } },
                        onPin = { upsert(n.copy(pinned = !n.pinned)) }) }
                    if (n.trashed > 0L) card() else SwipeActions(
                        start = SwipeAction(if (n.pinned) "Unpin" else "Pin", WonderIcons.PushPin, Color(0xFFE39A12)) { upsert(n.copy(pinned = !n.pinned)) },
                        end = SwipeAction("Trash", WonderIcons.Delete, p.danger, destructive = true) { upsert(n.copy(trashed = System.currentTimeMillis())); toast.value("Moved to the Trash. You can restore it from there for 30 days") },
                        modifier = Modifier.clip(RoundedCornerShape(p.panelRadius + 4.dp))) { card() }
                }}
            } else LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalItemSpacing = 10.dp) {
                items(shown, key = { it.id }) { n -> Box(Modifier.animateItemPlacement()) {
                    NoteCard(n, store, onClick = { if (n.trashed == 0L) openId = n.id }, trash = n.trashed > 0,
                        onRestore = { val r = n.copy(trashed = 0L); val i = notes.indexOfFirst { it.id == n.id }; if (i >= 0) notes[i] = r; scope.launch(Dispatchers.IO) { store.restore(r) } },
                        onDeleteForever = { notes.remove(n); scope.launch(Dispatchers.IO) { store.deleteForever(n) } },
                        onPin = { upsert(n.copy(pinned = !n.pinned)) })
                }}
            }
        }
    }
    if (newFolder) InputDialog("New folder", "Folder name", confirm = "Create", onDismiss = { newFolder = false }) { name ->
        folders = (folders + name).distinct(); store.saveFolders(folders); filter = "folder:$name"
    }
    if (confirmEmpty) ConfirmDialog("Empty trash?", "Notes in the trash will be deleted permanently.", "Empty trash", onConfirm = {
        notes.filter { it.trashed > 0 }.forEach { n -> notes.remove(n); scope.launch(Dispatchers.IO) { store.deleteForever(n) } }
    }, onDismiss = { confirmEmpty = false }, danger = true)
}

private fun readText(context: Context, uri: Uri): Pair<String, String> {
    val data = context.contentResolver.openInputStream(uri)!!.use { input ->
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (true) { val n = input.read(buf); if (n < 0) break; if (out.size() + n > 2 * 1024 * 1024) throw IllegalStateException("That file is larger than 2 MB"); out.write(buf, 0, n) }
        out.toByteArray()
    }
    val text = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
    }.getOrElse { String(data, Charsets.ISO_8859_1) }.removePrefix("﻿")
    return text to context.displayName(uri).substringBeforeLast('.')
}

private fun paper(n: Note, dark: Boolean) = Color(if (dark) NOTE_COLORS[n.color.coerceIn(0, 5)].second else NOTE_COLORS[n.color.coerceIn(0, 5)].first)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(n: Note, store: NoteStore, onClick: () -> Unit, trash: Boolean, onRestore: () -> Unit, onDeleteForever: () -> Unit, onPin: () -> Unit) {
    val p = wonderPalette()
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(p.panelRadius)
    Box {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(paper(n, p.dark)).border(1.dp, p.panelBorder, shape)
                .combinedClickable(onClick = { if (trash) menu = true else onClick() }, onLongClick = { menu = true }).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(n.displayTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (n.pinned) Icon(WonderIcons.PushPin, "Pinned", Modifier.size(16.dp), tint = p.accent)
            }
            if (n.sketch) {
                val bmp = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, n.updated) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { store.sketchBitmap(n)?.let { b -> android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }.getOrNull() } }.value
                if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(6.dp)))
                else Icon(WonderIcons.Brush, null, tint = p.secondaryText)
            } else if (n.preview.isNotBlank()) Text(n.preview, style = MaterialTheme.typography.bodyMedium, color = p.text.copy(alpha = .8f), maxLines = 8, overflow = TextOverflow.Ellipsis)
            n.checklist?.let { (done, total) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AquaProgress(done.toFloat() / total, Modifier.weight(1f))
                    Text("  $done/$total", style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
                }
            }
            Row {
                if (n.folder.isNotEmpty()) Text(n.folder + " · ", style = MaterialTheme.typography.labelSmall, color = p.accent)
                Text(relativeTime(n.updated), style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
            }
        }
        DropdownMenu(menu, { menu = false }, Modifier.background(p.panel)) {
            if (trash) {
                DropdownMenuItem({ Text("Restore") }, { menu = false; onRestore() }, leadingIcon = { Icon(WonderIcons.Restore, null) })
                DropdownMenuItem({ Text("Delete forever", color = p.danger) }, { menu = false; onDeleteForever() }, leadingIcon = { Icon(WonderIcons.Delete, null, tint = p.danger) })
            } else {
                DropdownMenuItem({ Text(if (n.pinned) "Unpin" else "Pin") }, { menu = false; onPin() }, leadingIcon = { Icon(WonderIcons.PushPin, null) })
                DropdownMenuItem({ Text("Open") }, { menu = false; onClick() }, leadingIcon = { Icon(WonderIcons.Edit, null) })
            }
        }
    }
}

/* ------------------------------------------ text editor --------------------------------------- */

@Composable
private fun NoteEditor(note: Note, folders: List<String>, allNotes: List<Note>, onOpenLink: (String) -> Unit, onChange: (Note) -> Unit, onClose: () -> Unit, onDelete: () -> Unit, onNewFolder: (String) -> Unit) {
    val context = LocalContext.current
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(TextFieldValue(note.body)) }
    val undo = remember(note.id) { ArrayDeque<TextFieldValue>() }
    val redo = remember(note.id) { ArrayDeque<TextFieldValue>() }
    var lastSnap by remember(note.id) { mutableLongStateOf(0L) }
    var current by remember(note.id) { mutableStateOf(note) }
    var find by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var remind by remember { mutableStateOf(false) }
    var reminderAt by remember(note.id) { mutableLongStateOf(Reminders.at(context, note.id)) }
    var tick by remember { mutableIntStateOf(0) }
    val titleFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(note.id) { if (note.title.isEmpty() && note.body.isEmpty()) runCatching { delay(150); titleFocus.requestFocus() } }
    LaunchedEffect(title, body.text) {
        if (title == current.title && body.text == current.body) return@LaunchedEffect
        delay(350)
        current = current.copy(title = title, body = body.text, updated = System.currentTimeMillis())
        onChange(current)
    }
    fun update(n: Note) { current = n.copy(title = title, body = body.text); onChange(current) }
    fun setBody(v: TextFieldValue, snapshot: Boolean = true) {
        val now = System.currentTimeMillis()
        if (snapshot && v.text != body.text && (now - lastSnap > 800 || undo.isEmpty())) { undo.addLast(body); if (undo.size > 200) undo.removeFirst(); redo.clear(); lastSnap = now }
        body = v; tick++
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(((if (title.isNotBlank()) "# $title\n\n" else "") + body.text).toByteArray()) }
        }
    }
    var preview by rememberSaveable(note.id) { mutableStateOf(note.body.isNotBlank() && context.prefs("scribble").getBoolean("openInPreview", false)) }
    WonderApp(
        appName = "Scribble", about = ABOUT, title = "", onBack = onClose,
        actions = {
            ToolbarButton(WonderIcons.Undo, "Undo", { if (undo.isNotEmpty()) { redo.addLast(body); body = undo.removeLast(); tick++ } }, enabled = tick >= 0 && undo.isNotEmpty())
            ToolbarButton(WonderIcons.Redo, "Redo", { if (redo.isNotEmpty()) { undo.addLast(body); body = redo.removeLast(); tick++ } }, enabled = tick >= 0 && redo.isNotEmpty())
            ToolbarButton(if (preview) WonderIcons.Edit else WonderIcons.Visibility, if (preview) "Edit" else "Preview", { preview = !preview })
            if (!preview) ToolbarButton(WonderIcons.CheckCircle, "Checklist item", {
                val (t, c) = toggleCheck(body.text, body.selection.start); setBody(TextFieldValue(t, TextRange(c)))
            })
            ToolbarButton(WonderIcons.PushPin, if (current.pinned) "Unpin" else "Pin", { update(current.copy(pinned = !current.pinned)) }, tint = if (current.pinned) wonderPalette().accent else null)
        },
        menu = listOf(
            WonderMenuItem(if (reminderAt > 0) "Reminder: " + Reminders.label(reminderAt) else "Remind me", WonderIcons.Notifications) { remind = true },
            WonderMenuItem("Share", WonderIcons.Share) {
                context.launchSafely(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, (if (title.isNotBlank()) "$title\n\n" else "") + body.text), "Share note")) {}
            },
            WonderMenuItem("Find and replace", WonderIcons.Search) { find = true },
            WonderMenuItem("Paper colour", WonderIcons.Palette) { colorMenu = true },
            WonderMenuItem(if (current.folder.isEmpty()) "Move to folder" else "Folder: ${current.folder}", WonderIcons.DriveFileMove) { folderMenu = true },
            WonderMenuItem("Export as file", WonderIcons.Download) { exporter.launch((title.ifBlank { current.displayTitle }).replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".md") },
            WonderMenuItem("Move to trash", WonderIcons.Delete, danger = true) { onDelete() },
        )
    ) {
        val noteToast = LocalToast.current
        if (remind) ReminderDialog(current.id, title.ifBlank { current.displayTitle }, onDismiss = { remind = false; reminderAt = Reminders.at(context, current.id) }) { noteToast(it) }
        val p = wonderPalette()
        val bg = paper(current, p.dark)
        Column(Modifier.fillMaxSize().background(bg)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                BasicTextField(title, { title = it.take(200).replace("\n", "") }, Modifier.fillMaxWidth().focusRequester(titleFocus),
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = p.text, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(p.accent), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner -> if (title.isEmpty()) Text("Title", style = MaterialTheme.typography.headlineSmall, color = p.secondaryText.copy(alpha = .6f)); inner() })
                Text(DateUtils.formatDateTime(context, current.updated, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR), style = MaterialTheme.typography.labelSmall, color = p.secondaryText, modifier = Modifier.padding(vertical = 6.dp))
                val lineHeight = 26.sp
                val ruled = p.text.copy(alpha = if (p.dark) .08f else .07f)
                if (preview) {
                    MarkdownView(body.text, onToggle = { line -> setBody(TextFieldValue(toggleLine(body.text, line))) }, onLink = onOpenLink)
                    val me = (title.ifBlank { current.displayTitle }).lowercase()
                    val back = allNotes.filter { it.id != current.id && it.trashed == 0L && me in it.links }
                    if (back.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp)); SectionLabel("Linked from ${back.size} note${if (back.size == 1) "" else "s"}")
                        back.forEach { b -> Text("↩ " + b.displayTitle, Modifier.clickable { onOpenLink(b.displayTitle) }.padding(vertical = 6.dp), color = p.accent, style = MaterialTheme.typography.bodyLarge) }
                    }
                } else BasicTextField(body, { v ->
                    val cont = continueList(body.text, v.text, v.selection.start)
                    if (cont != null) setBody(TextFieldValue(cont.first, TextRange(cont.second))) else setBody(v)
                }, Modifier.fillMaxWidth().defaultMinSize(minHeight = 400.dp).drawBehind {
                    val step = lineHeight.toPx()
                    var y = step - 2
                    while (y < size.height) { drawLine(ruled, Offset(0f, y), Offset(size.width, y), 1.5f); y += step }
                }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.text, lineHeight = lineHeight), cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner -> if (body.text.isEmpty()) Text("Start writing…", style = MaterialTheme.typography.bodyLarge, color = p.secondaryText.copy(alpha = .6f)); inner() })
            }
            Row(Modifier.fillMaxWidth().background(p.panel.copy(alpha = .7f)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val words = current.copy(title = title, body = body.text).words
                Text("$words word${if (words == 1) "" else "s"} · ${body.text.length} characters · ${(words / 220).coerceAtLeast(if (words > 0) 1 else 0)} min read",
                    style = MaterialTheme.typography.labelMedium, color = p.secondaryText, modifier = Modifier.weight(1f))
                current.checklist?.let { (d, t) -> Text("☑ $d/$t", style = MaterialTheme.typography.labelMedium, color = p.accent) }
            }
        }
    }
    if (find) FindReplaceDialog(body.text, onDismiss = { find = false }) { setBody(TextFieldValue(it)) }
    if (colorMenu) WonderDialog(onDismissRequest = { colorMenu = false }, title = { Text("Paper colour") }, text = {
        val p = wonderPalette()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NOTE_COLORS.forEachIndexed { i, (l, d) ->
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(if (p.dark) d else l)).border(if (current.color == i) 3.dp else 1.dp, if (current.color == i) p.accent else p.panelBorder, CircleShape)
                    .clickable { update(current.copy(color = i)); colorMenu = false })
            }
        }
    }, confirmButton = { TextButton(onClick = { colorMenu = false }) { Text("Done") } })
    if (folderMenu) WonderDialog(onDismissRequest = { folderMenu = false }, title = { Text("Move to folder") }, text = {
        Panel {
            ListRow("No folder", icon = WonderIcons.Notes, tint = Color(0xFF7A7F87), selected = current.folder.isEmpty(), onClick = { update(current.copy(folder = "")); folderMenu = false })
            folders.forEach { f -> PanelDivider(); ListRow(f, icon = WonderIcons.Folder, tint = Color(0xFF4A90E2), selected = current.folder == f, onClick = { update(current.copy(folder = f)); folderMenu = false }) }
            PanelDivider(); ListRow("New folder…", icon = WonderIcons.CreateNewFolder, tint = Color(0xFF2E9E44), onClick = { folderMenu = false; newFolder = true })
        }
    }, confirmButton = { TextButton(onClick = { folderMenu = false }) { Text("Cancel") } })
    if (newFolder) InputDialog("New folder", "Folder name", confirm = "Create", onDismiss = { newFolder = false }) { f -> onNewFolder(f); update(current.copy(folder = f)) }
}

@Composable
private fun FindReplaceDialog(text: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    val count = remember(find, matchCase, text) { if (find.isEmpty()) 0 else Regex(Regex.escape(find), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)).findAll(text).count() }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Find and replace") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(find, { find = it }, "Find", supporting = if (find.isEmpty()) null else "$count match${if (count == 1) "" else "es"}")
            Field(replace, { replace = it }, "Replace with")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(matchCase, { matchCase = it }); Text("Match case") }
        }
    }, confirmButton = {
        TextButton(enabled = count > 0, onClick = {
            onApply(Regex(Regex.escape(find), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)).replace(text, Regex.escapeReplacement(replace))); onDismiss()
        }) { Text("Replace all") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/* -------------------------------------------- sketches ---------------------------------------- */

private data class Stroke2(val color: Int, val width: Float, val points: List<Offset>, val shape: Int = 0)

private fun strokesToJson(list: List<Stroke2>) = JSONArray(list.map { s ->
    JSONObject().put("c", s.color).put("w", s.width.toDouble()).put("s", s.shape).put("p", JSONArray(s.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }))
}).toString()

private fun strokesFromJson(s: String): List<Stroke2> = runCatching {
    val a = JSONArray(s)
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i); val pts = o.getJSONArray("p")
        Stroke2(o.getInt("c"), o.getDouble("w").toFloat(), (0 until pts.length() / 2).map { Offset(pts.getDouble(it * 2).toFloat(), pts.getDouble(it * 2 + 1).toFloat()) }, o.optInt("s"))
    }
}.getOrDefault(emptyList())

@Composable
private fun SketchEditor(note: Note, store: NoteStore, onChangeLocal: (Note) -> Unit, onClose: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val strokes = remember(note.id) { mutableStateListOf<Stroke2>().apply { addAll(strokesFromJson(note.strokes)) } }
    val redo = remember(note.id) { mutableStateListOf<Stroke2>() }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by rememberSaveable { mutableIntStateOf(0xFF1B1D21.toInt()) }
    var width by rememberSaveable { mutableFloatStateOf(8f) }
    var eraser by rememberSaveable { mutableStateOf(false) }
    var tool by rememberSaveable { mutableIntStateOf(0) }
    var paperKind by remember(note.id) { mutableIntStateOf(note.color.coerceIn(0, 2)) }
    var title by remember(note.id) { mutableStateOf(note.title) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun render(): Bitmap {
        val bmp = Bitmap.createBitmap(canvasSize.width.coerceAtLeast(1), canvasSize.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); c.drawColor(android.graphics.Color.WHITE)
        val rule = android.graphics.Paint().apply { this.color = 0x22000000; strokeWidth = 2f }
        if (paperKind == 1) { var y = 64f; while (y < bmp.height) { c.drawLine(0f, y, bmp.width.toFloat(), y, rule); y += 64f } }
        if (paperKind == 2) { var g = 48f; while (g < maxOf(bmp.width, bmp.height)) { c.drawLine(g, 0f, g, bmp.height.toFloat(), rule); c.drawLine(0f, g, bmp.width.toFloat(), g, rule); g += 48f } }
        strokes.forEach { s ->
            val paint = android.graphics.Paint().apply { this.color = s.color; strokeWidth = s.width; style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND; isAntiAlias = true }
            val a = s.points.first(); val b = s.points.last()
            when (s.shape) {
                1 -> c.drawLine(a.x, a.y, b.x, b.y, paint)
                2 -> c.drawRect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y), paint)
                3 -> c.drawOval(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y), paint)
                else -> { val path = android.graphics.Path(); s.points.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y) }
                    if (s.points.size == 1) c.drawPoint(a.x, a.y, paint) else c.drawPath(path, paint) }
            }
        }
        return bmp
    }
    fun persist() {
        val n = note.copy(title = title, strokes = strokesToJson(strokes), color = paperKind, updated = System.currentTimeMillis())
        val bmp = render()
        onChangeLocal(n)
        scope.launch(Dispatchers.IO) { store.sketchFile(n).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; store.commitSketch(n) }
    }
    WonderApp(
        appName = "Scribble", about = ABOUT, title = "", onBack = { persist(); onClose() },
        actions = {
            ToolbarButton(WonderIcons.Undo, "Undo", { if (strokes.isNotEmpty()) redo.add(strokes.removeAt(strokes.lastIndex)) }, enabled = strokes.isNotEmpty())
            ToolbarButton(WonderIcons.Redo, "Redo", { if (redo.isNotEmpty()) strokes.add(redo.removeAt(redo.lastIndex)) }, enabled = redo.isNotEmpty())
            ToolbarButton(WonderIcons.Share, "Share", {
                persist()
                val f = File(context.cacheDir, "share").apply { mkdirs() }.let { File(it, "${title.ifBlank { "Sketch" }.replace(Regex("[^A-Za-z0-9 _-]"), "_")}.png") }
                f.outputStream().use { render().compress(Bitmap.CompressFormat.PNG, 100, it) }
                context.share(FileProvider.getUriForFile(context, "${context.packageName}.files", f), "image/png") {}
            })
        },
        menu = listOf(
            WonderMenuItem("Clear sketch", WonderIcons.DeleteSweep) { confirmClear = true },
            WonderMenuItem("Move to trash", WonderIcons.Delete, danger = true) { persist(); onDelete() },
        )
    ) {
        val p = wonderPalette()
        Column(Modifier.fillMaxSize()) {
            BasicTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                textStyle = MaterialTheme.typography.titleLarge.copy(color = p.text, fontWeight = FontWeight.SemiBold), cursorBrush = SolidColor(p.accent),
                decorationBox = { inner -> if (title.isEmpty()) Text("Untitled sketch", style = MaterialTheme.typography.titleLarge, color = p.secondaryText.copy(alpha = .6f)); inner() })
            val shape = RoundedCornerShape(p.panelRadius)
            Canvas(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp).clip(shape).background(Color.White).border(1.dp, p.panelBorder, shape)
                .onSizeChanged { canvasSize = it }
                .pointerInput(eraser, color, width, tool) {
                    detectDragGestures(
                        onDragStart = { live = listOf(it) },
                        onDrag = { change, _ -> live = live + change.position },
                        onDragEnd = { strokes.add(Stroke2(if (eraser) android.graphics.Color.WHITE else color, if (eraser) width * 3 else width, live, if (eraser) 0 else tool)); redo.clear(); live = emptyList() },
                        onDragCancel = { live = emptyList() })
                }
                .pointerInput(eraser, color, width) {
                    detectTapGestures(onTap = { strokes.add(Stroke2(if (eraser) android.graphics.Color.WHITE else color, if (eraser) width * 3 else width, listOf(it))); redo.clear() })
                }
            ) {
                val ruleC = Color(0x22000000)
                if (paperKind == 1) { var y = 64f; while (y < size.height) { drawLine(ruleC, Offset(0f, y), Offset(size.width, y), 2f); y += 64f } }
                if (paperKind == 2) { var g = 48f; while (g < maxOf(size.width, size.height)) { drawLine(ruleC, Offset(g, 0f), Offset(g, size.height), 2f); drawLine(ruleC, Offset(0f, g), Offset(size.width, g), 2f); g += 48f } }
                fun draw(s: Stroke2) {
                    val st = Stroke(s.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val a = s.points.first(); val b = s.points.last()
                    val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y)); val sz = androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
                    when {
                        s.shape == 1 -> drawLine(Color(s.color), a, b, s.width, StrokeCap.Round)
                        s.shape == 2 -> drawRect(Color(s.color), tl, sz, style = st)
                        s.shape == 3 -> drawOval(Color(s.color), tl, sz, style = st)
                        s.points.size == 1 -> drawCircle(Color(s.color), s.width / 2, a)
                        else -> { val path = Path(); s.points.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y) }; drawPath(path, Color(s.color), style = st) }
                    }
                }
                strokes.forEach { draw(it) }
                if (live.isNotEmpty()) draw(Stroke2(if (eraser) android.graphics.Color.WHITE else color, if (eraser) width * 3 else width, live, if (eraser) 0 else tool))
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(listOf("Pen", "Line", "Box", "Oval"), tool, Modifier.weight(1f)) { tool = it; eraser = false }
                Segmented(listOf("Plain", "Lined", "Grid"), paperKind, Modifier.weight(.8f)) { paperKind = it }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0xFF1B1D21, 0xFFE5483C, 0xFFF39C12, 0xFF2E9E44, 0xFF1E7FE8, 0xFF8E44AD).forEach { c ->
                    val on = !eraser && color == c.toInt()
                    Box(Modifier.size(if (on) 34.dp else 28.dp).clip(CircleShape).background(Color(c)).border(if (on) 3.dp else 1.dp, if (on) p.accent else p.panelBorder, CircleShape)
                        .clickable { color = c.toInt(); eraser = false })
                }
                Spacer(Modifier.weight(1f))
                listOf(4f, 8f, 16f).forEach { w ->
                    Box(Modifier.size(34.dp).clip(CircleShape).background(if (width == w) p.accent.copy(alpha = .18f) else Color.Transparent).clickable { width = w }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size((w / 1.2f + 3).dp).clip(CircleShape).background(p.text))
                    }
                }
                Box(Modifier.size(38.dp).clip(CircleShape).background(if (eraser) p.accent else p.window).border(1.dp, p.panelBorder, CircleShape).clickable { eraser = !eraser }, contentAlignment = Alignment.Center) {
                    Icon(WonderIcons.Backspace, "Eraser", tint = if (eraser) p.onAccent else p.text, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    if (confirmClear) ConfirmDialog("Clear sketch?", "This removes every stroke. You can't undo it after leaving.", "Clear", onConfirm = { redo.clear(); redo.addAll(strokes.reversed()); strokes.clear() }, onDismiss = { confirmClear = false }, danger = true)
}

/* -------------------------------------------- markdown preview -------------------------------------------- */

@Composable
private fun MarkdownView(text: String, onToggle: (Int) -> Unit, onLink: (String) -> Unit) {
    val p = wonderPalette()
    var inCode = false
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.lines().forEachIndexed { i, raw ->
            if (raw.trim().startsWith("```")) { inCode = !inCode; return@forEachIndexed }
            if (inCode) { Text(raw, Modifier.fillMaxWidth().background(p.window).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)); return@forEachIndexed }
            val l = raw.trimStart()
            when {
                l.startsWith("# ") -> Text(inline(l.drop(2), p), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 8.dp))
                l.startsWith("## ") -> Text(inline(l.drop(3), p), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp))
                l.startsWith("### ") -> Text(inline(l.drop(4), p), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                isTodo(l) -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(isDone(l), { onToggle(i) })
                    val body = l.removePrefix("- [ ] ").removePrefix("- [x] ").removePrefix("- [X] ").removePrefix("☐ ").removePrefix("☑ ")
                    LinkText(body, p, onLink, if (isDone(l)) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                }
                l.startsWith("- ") || l.startsWith("* ") || l.startsWith("• ") -> Row { Text("•  ", color = p.accent); LinkText(l.drop(2), p, onLink) }
                Regex("^\\d+\\. ").containsMatchIn(l) -> Row { Text(l.substringBefore(' ') + " ", color = p.accent); LinkText(l.substringAfter(' '), p, onLink) }
                l.startsWith("> ") -> Row(Modifier.height(IntrinsicSize.Min)) { Box(Modifier.width(3.dp).fillMaxHeight().background(p.accent)); Spacer(Modifier.width(10.dp)); LinkText(l.drop(2), p, onLink) }
                l == "---" || l == "***" -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = p.separator)
                l.isBlank() -> Spacer(Modifier.height(6.dp))
                else -> LinkText(l, p, onLink)
            }
        }
    }
}

@Composable
private fun LinkText(s: String, p: WonderPalette, onLink: (String) -> Unit, deco: androidx.compose.ui.text.style.TextDecoration? = null) {
    val ann = inline(s, p)
    androidx.compose.foundation.text.ClickableText(ann, style = MaterialTheme.typography.bodyLarge.copy(color = p.text, textDecoration = deco)) { off ->
        ann.getStringAnnotations("link", off, off).firstOrNull()?.let { onLink(it.item) }
    }
}

/** **bold**, *italic*, `code`, [[wiki links]] and ==highlights==. */
private fun inline(s: String, p: WonderPalette): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val re = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`|\\[\\[([^\\]]+)]]|==(.+?)==")
    var last = 0
    re.findAll(s).forEach { m ->
        append(s.substring(last, m.range.first))
        val (b, i, c, link, hl) = m.destructured
        when {
            b.isNotEmpty() -> withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(b, p)) }
            i.isNotEmpty() -> withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(inline(i, p)) }
            c.isNotEmpty() -> withStyle(androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = p.window)) { append(c) }
            link.isNotEmpty() -> { val target = link.substringBefore('|').substringBefore('#').trim(); pushStringAnnotation("link", target)
                withStyle(androidx.compose.ui.text.SpanStyle(color = p.accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) { append(link.substringAfter('|')) }; pop() }
            hl.isNotEmpty() -> withStyle(androidx.compose.ui.text.SpanStyle(background = Color(0x66FFE135))) { append(hl) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

/** Every text document on the phone, searchable, opened in place. */
@Composable
private fun DeviceDocuments(query: String, needAccess: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val resumed = rememberIsResumed()
    var docs by remember { mutableStateOf<List<Documents.Doc>?>(null) }
    val access = remember(resumed) { Documents.canEditAll() }
    LaunchedEffect(access, resumed) { if (access && resumed) docs = withContext(Dispatchers.IO) { Documents.list(context) } }
    if (!access) { EmptyState("See every document on your phone", "Turn on all files access to browse and edit text and Markdown files anywhere on your phone, right where they are.",
        icon = WonderIcons.Description, action = { WButton("Turn on all files access", needAccess, icon = WonderIcons.Storage, kind = ButtonKind.Primary) }); return }
    val list = docs
    if (list == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }; return }
    val shown = remember(list, query) { if (query.isBlank()) list else list.filter { it.name.contains(query, true) || it.folder.contains(query, true) } }
    if (shown.isEmpty()) { EmptyState(if (query.isBlank()) "No text documents found" else "No matches", if (query.isBlank()) "Text and Markdown files you save or download will show up here." else "No document name contains “$query”.", icon = WonderIcons.Description); return }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), state = rememberTabTopList(), contentPadding = PaddingValues(16.dp)) {
        item { Text("${shown.size} document${if (shown.size == 1) "" else "s"}", Modifier.padding(6.dp, 0.dp, 6.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = p.secondaryText) }
        items(shown.size, key = { shown[it].path }) { i ->
            val d = shown[i]
            ListRow(d.name, Modifier.panelItem(i, shown.size), subtitle = "${d.folder} · ${formatBytes(d.size)} · ${relativeTime(d.modified)}",
                icon = if (d.name.endsWith(".md") || d.name.endsWith(".markdown")) WonderIcons.Article else WonderIcons.TextSnippet,
                tint = if (d.name.endsWith(".md") || d.name.endsWith(".markdown")) Color(0xFF8E44AD) else Color(0xFF7A7F87), onClick = { onOpen(d.path) })
        }
    }
}
