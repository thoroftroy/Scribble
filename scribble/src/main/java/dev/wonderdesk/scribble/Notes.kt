package dev.wonderdesk.scribble

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One note. [title] is the file name without extension, [folder] the sub-folder path inside the notes folder. */
data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val body: String = "",
    val folder: String = "",
    val pinned: Boolean = false,
    val color: Int = 0,
    val sketch: Boolean = false,
    val strokes: String = "",
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val trashed: Long = 0L,
    val ext: String = "md",
    /** For a document opened from anywhere on the phone: its full path. Edits go straight to that file. */
    val external: String = "",
) {
    val displayTitle: String get() = title.ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("# ")?.removePrefix("☐ ")?.removePrefix("☑ ")?.take(60) ?: if (sketch) "Sketch" else "New note" }
    val preview: String get() = body.trim()
    val checklist: Pair<Int, Int>? get() {
        val lines = body.lines()
        val total = lines.count { isTodo(it) }
        return if (total == 0) null else lines.count { isDone(it) } to total
    }
    val words: Int get() = (title + " " + body).split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
    /** [[Wiki links]] in this note. */
    val links: Set<String> get() = Regex("\\[\\[([^\\]|#]+)").findAll(body).map { it.groupValues[1].trim().lowercase() }.toSet()
}

fun isTodo(l: String) = l.trimStart().let { it.startsWith("☐ ") || it.startsWith("☑ ") || it.startsWith("- [ ] ") || it.startsWith("- [x] ", true) }
fun isDone(l: String) = l.trimStart().let { it.startsWith("☑ ") || it.startsWith("- [x] ", true) }

/* ------------------------------------------- where notes live -------------------------------------------- */

/** A folder of notes: the app's private folder, or any folder the user picked (for example an Obsidian vault). */
interface Fs {
    val label: String
    fun walk(): List<Pair<String, Long>>       // relative paths of files with last-modified time
    fun dirs(): List<String>
    fun read(path: String): ByteArray?
    fun write(path: String, data: ByteArray)
    fun delete(path: String)
    fun mkdir(path: String)
    fun exists(path: String): Boolean
}

private val SKIP = setOf(".scribble", ".obsidian", ".trash", ".git")
val NOTE_EXT = setOf("md", "markdown", "txt")

class FileFs(private val root: File, override val label: String) : Fs {
    init { root.mkdirs() }
    override fun walk(): List<Pair<String, Long>> = root.walkTopDown().onEnter { it == root || it.name !in SKIP }.filter { it.isFile }
        .map { it.relativeTo(root).path to it.lastModified() }.toList()
    override fun dirs() = root.walkTopDown().onEnter { it == root || it.name !in SKIP }.filter { it.isDirectory && it != root }.map { it.relativeTo(root).path }.toList()
    override fun read(path: String) = runCatching { File(root, path).readBytes() }.getOrNull()
    override fun write(path: String, data: ByteArray) { val f = File(root, path); f.parentFile?.mkdirs(); val t = File(f.path + ".tmp"); t.writeBytes(data); t.renameTo(f) }
    override fun delete(path: String) { File(root, path).delete() }
    override fun mkdir(path: String) { File(root, path).mkdirs() }
    override fun exists(path: String) = File(root, path).exists()
}

class SafFs(private val context: Context, tree: Uri) : Fs {
    private val root = DocumentFile.fromTreeUri(context, tree) ?: error("That folder isn't available")
    override val label: String = root.name ?: "Folder"
    private val cache = HashMap<String, DocumentFile>()
    private fun dir(path: String, create: Boolean): DocumentFile? {
        if (path.isEmpty()) return root
        cache[path]?.let { return it }
        val parent = dir(path.substringBeforeLast('/', ""), create) ?: return null
        val n = path.substringAfterLast('/')
        val d = parent.findFile(n)?.takeIf { it.isDirectory } ?: if (create) parent.createDirectory(n) else null
        return d?.also { cache[path] = it }
    }
    private fun file(path: String) = dir(path.substringBeforeLast('/', ""), false)?.findFile(path.substringAfterLast('/'))
    override fun walk(): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        fun go(d: DocumentFile, p: String) { d.listFiles().forEach { f -> val n = f.name ?: return@forEach
            val rel = if (p.isEmpty()) n else "$p/$n"
            if (f.isDirectory) { if (n !in SKIP) { cache[rel] = f; go(f, rel) } } else out += rel to f.lastModified() } }
        go(root, ""); return out
    }
    override fun dirs(): List<String> { walk(); return cache.keys.toList() }
    override fun read(path: String) = runCatching { context.contentResolver.openInputStream(file(path)!!.uri)!!.use { it.readBytes() } }.getOrNull()
    override fun write(path: String, data: ByteArray) {
        val parent = dir(path.substringBeforeLast('/', ""), true) ?: error("Couldn't open the folder")
        val n = path.substringAfterLast('/')
        val f = parent.findFile(n) ?: parent.createFile(if (n.endsWith(".png")) "image/png" else if (n.endsWith(".txt")) "text/plain" else "text/markdown", n) ?: error("Couldn't create $n")
        // Some providers add .txt/.md themselves; if the name changed, accept it.
        context.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(data) }
    }
    override fun delete(path: String) { file(path)?.delete() }
    override fun mkdir(path: String) { dir(path, true) }
    override fun exists(path: String) = file(path) != null
}

/* ------------------------------------------------- store ------------------------------------------------- */

/**
 * Notes are real files. Scribble keeps only small extras (pins, colours, sketch strokes, trash)
 * in .scribble/index.json, so the folder stays a normal Markdown folder any app can open.
 */
class NoteStore(private val context: Context) {
    val prefs = context.getSharedPreferences("scribble", Context.MODE_PRIVATE)
    var fs: Fs = openFs(); private set
    private var index = JSONObject()   // id -> {path, pinned, color, created, trashed, strokes}

    private fun openFs(): Fs {
        // Scribble 0.5.6–0.5.10 stored the view mode under "tree", the same name as the notes folder; clear that.
        if (prefs.all["tree"] is Boolean) prefs.edit().remove("tree").apply()
        if (prefs.all["list"] is Boolean) prefs.edit().remove("list").apply()
        // With all-files access, a folder on the phone is read directly, which is far faster than through the picker.
        prefs.getString("path", null)?.let { p -> val f = File(p); if (android.os.Environment.isExternalStorageManager() && f.isDirectory) return FileFs(f, f.name) }
        return prefs.getString("tree", null)?.let { t -> runCatching { SafFs(context, Uri.parse(t)) }.getOrNull() }
            ?: FileFs(File(context.filesDir, "vault"), "On this phone")
    }

    val usesSharedFolder get() = prefs.getString("tree", null) != null || prefs.getString("path", null) != null

    fun useFolder(tree: Uri?) {
        val path = tree?.let { Documents.treeToPath(it) }
        prefs.edit().apply {
            if (tree == null) { remove("tree"); remove("path") } else { putString("tree", tree.toString()); if (path != null) putString("path", path) else remove("path") }
        }.apply()
        fs = openFs(); indexLoaded = false
    }

    private fun loadIndex() { index = runCatching { JSONObject(String(fs.read(".scribble/index.json")!!)) }.getOrDefault(JSONObject()) }
    private fun saveIndex() = fs.write(".scribble/index.json", index.toString().toByteArray())

    private fun pathOf(n: Note): String {
        val safe = n.displayTitle.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), " ").trim().ifBlank { "Untitled" }.take(80)
        val dir = if (n.folder.isEmpty()) "" else n.folder.trimEnd('/') + "/"
        return dir + safe + "." + (if (n.sketch) "png" else n.ext)
    }

    @Synchronized fun all(): List<Note> {
        migrateOldStore()
        loadIndex()
        val byPath = HashMap<String, String>()
        index.keys().forEach { id -> index.optJSONObject(id)?.optString("path")?.let { byPath[it] = id } }
        val notes = mutableListOf<Note>()
        val seen = HashSet<String>()
        fs.walk().forEach { (path, mtime) ->
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in NOTE_EXT && ext != "png") return@forEach
            if (ext == "png" && byPath[path] == null && !path.contains("Sketches", true)) return@forEach  // only sketches Scribble made
            val id = byPath[path] ?: UUID.randomUUID().toString().also { index.put(it, JSONObject().put("path", path).put("created", mtime)) }
            seen += id
            val meta = index.getJSONObject(id)
            val folder = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/').substringBeforeLast('.')
            val body = if (ext == "png") "" else fs.read(path)?.toString(Charsets.UTF_8)?.removePrefix("﻿") ?: ""
            notes += Note(id, name, body, folder, meta.optBoolean("pinned"), meta.optInt("color"), ext == "png", meta.optString("strokes"),
                meta.optLong("created", mtime), mtime, 0L, if (ext == "png") "md" else ext)
        }
        // Trashed notes live in .scribble/trash
        val trash = index.optJSONObject("_trash") ?: JSONObject()
        val cutoff = System.currentTimeMillis() - 30L * 86_400_000L
        trash.keys().asSequence().toList().forEach { id ->
            val t = trash.getJSONObject(id)
            if (t.getLong("at") < cutoff) { fs.delete(".scribble/trash/$id"); trash.remove(id); return@forEach }
            notes += Note(id, t.optString("title"), fs.read(".scribble/trash/$id")?.toString(Charsets.UTF_8) ?: "", t.optString("folder"), color = t.optInt("color"), trashed = t.getLong("at"), ext = t.optString("ext", "md"))
        }
        index.put("_trash", trash)
        index.keys().asSequence().toList().filter { it != "_trash" && it !in seen }.forEach { index.remove(it) }
        runCatching { saveIndex() }
        return notes
    }

    /** Writes the note's file, renaming it when the title or folder changed. */
    @Synchronized fun save(n: Note) {
        if (n.trashed > 0) { trash(n); return }
        loadIndexIfNeeded()
        val meta = index.optJSONObject(n.id) ?: JSONObject().put("created", n.created)
        val old = meta.optString("path", "")
        var path = pathOf(n)
        if (path != old && fs.exists(path)) { var i = 2; val base = path.substringBeforeLast('.'); val e = path.substringAfterLast('.'); while (fs.exists("$base $i.$e")) i++; path = "$base $i.$e" }
        if (n.sketch) fs.read(".scribble/sketch-${n.id}.png")?.let { fs.write(path, it) } else fs.write(path, n.body.toByteArray())
        if (old.isNotEmpty() && old != path) fs.delete(old)
        meta.put("path", path).put("pinned", n.pinned).put("color", n.color).put("strokes", n.strokes)
        index.put(n.id, meta); saveIndex()
    }

    private var indexLoaded = false
    private fun loadIndexIfNeeded() { if (!indexLoaded) { loadIndex(); indexLoaded = true } }

    private fun trash(n: Note) {
        loadIndexIfNeeded()
        val path = index.optJSONObject(n.id)?.optString("path")
        if (!n.sketch) fs.write(".scribble/trash/${n.id}", n.body.toByteArray())
        path?.let { fs.delete(it) }
        index.remove(n.id)
        val trash = index.optJSONObject("_trash") ?: JSONObject()
        trash.put(n.id, JSONObject().put("title", n.title).put("folder", n.folder).put("at", n.trashed).put("color", n.color).put("ext", n.ext))
        index.put("_trash", trash); saveIndex()
    }

    @Synchronized fun restore(n: Note) {
        loadIndexIfNeeded()
        index.optJSONObject("_trash")?.remove(n.id); fs.delete(".scribble/trash/${n.id}"); saveIndex()
        save(n.copy(trashed = 0L))
    }

    @Synchronized fun deleteForever(note: Note) {
        loadIndexIfNeeded()
        if (note.trashed > 0) { index.optJSONObject("_trash")?.remove(note.id); fs.delete(".scribble/trash/${note.id}") }
        else { index.optJSONObject(note.id)?.optString("path")?.let { fs.delete(it) }; index.remove(note.id) }
        saveIndex()
    }

    /** Working copy of a sketch's picture, before it's saved under its name. */
    fun sketchFile(note: Note): File = File(context.cacheDir, "sketch-${note.id}.png")
    @Synchronized fun commitSketch(note: Note) { sketchFile(note).takeIf { it.exists() }?.let { fs.write(".scribble/sketch-${note.id}.png", it.readBytes()) }; save(note) }
    fun sketchBitmap(note: Note): ByteArray? = fs.read(".scribble/sketch-${note.id}.png")

    fun folders(): List<String> = runCatching { fs.dirs().filter { d -> d.split('/').none { it in SKIP } }.sorted() }.getOrDefault(emptyList())
    fun saveFolders(list: List<String>) = list.forEach { runCatching { fs.mkdir(it) } }

    /** Notes from older versions (JSON in files/notes) become Markdown files once. */
    private fun migrateOldStore() {
        val old = File(context.filesDir, "notes")
        if (!old.isDirectory || prefs.getBoolean("migrated_v3", false)) return
        old.listFiles { f -> f.name.endsWith(".json") && f.name != "folders.json" }?.forEach { f ->
            runCatching {
                val o = JSONObject(f.readText())
                val sketch = o.optBoolean("sketch")
                val n = Note(o.getString("id"), o.optString("title"), o.optString("body"), o.optString("folder"), o.optBoolean("pinned"), o.optInt("color"), sketch, o.optString("strokes"))
                if (o.optLong("trashed") > 0) return@runCatching
                if (sketch) File(old, "${n.id}.png").takeIf { it.exists() }?.let { fs.write(".scribble/sketch-${n.id}.png", it.readBytes()) }
                save(n)
            }
        }
        prefs.edit().putBoolean("migrated_v3", true).apply()
    }
}

/** Sticky-note paper colours: (light, dark) */
val NOTE_COLORS = listOf(
    0xFFFFFFFF to 0xFF2B2F35, 0xFFFFF6B8 to 0xFF4A4420, 0xFFD9F5D0 to 0xFF233F27,
    0xFFD6EBFF to 0xFF1F3550, 0xFFFFDDE6 to 0xFF4D2531, 0xFFE9DDFF to 0xFF372A52,
)
val NOTE_COLOR_NAMES = listOf("White", "Yellow", "Green", "Blue", "Pink", "Purple")

/** Toggles the checkbox on the line containing [cursor]: none -> [ ] -> [x] -> [ ]. Uses Markdown task syntax. */
fun toggleCheck(text: String, cursor: Int): Pair<String, Int> {
    val start = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0 || cursor == 0) 0 else it + 1 }.coerceAtMost(text.length)
    val line = text.substring(start)
    return when {
        line.startsWith("- [ ] ") -> text.replaceRange(start, start + 6, "- [x] ") to cursor
        line.startsWith("- [x] ", true) -> text.replaceRange(start, start + 6, "- [ ] ") to cursor
        line.startsWith("☐ ") -> text.replaceRange(start, start + 2, "☑ ") to cursor
        line.startsWith("☑ ") -> text.replaceRange(start, start + 2, "☐ ") to cursor
        else -> text.replaceRange(start, start, "- [ ] ") to cursor + 6
    }
}

/** Flip the checkbox on line [index] (for taps in preview). */
fun toggleLine(text: String, index: Int): String {
    val lines = text.lines().toMutableList()
    val l = lines.getOrNull(index) ?: return text
    val lead = l.takeWhile { it == ' ' || it == '\t' }; val r = l.drop(lead.length)
    lines[index] = lead + when {
        r.startsWith("- [ ] ") -> "- [x] " + r.drop(6); r.startsWith("- [x] ", true) -> "- [ ] " + r.drop(6)
        r.startsWith("☐ ") -> "☑ " + r.drop(2); r.startsWith("☑ ") -> "☐ " + r.drop(2); else -> r
    }
    return lines.joinToString("\n")
}

/** When the user presses Enter after a list line, continue the list; on an empty item, end it. */
fun continueList(old: String, new: String, cursor: Int): Pair<String, Int>? {
    if (new.length != old.length + 1 || cursor == 0 || new[cursor - 1] != '\n') return null
    val prevStart = new.lastIndexOf('\n', cursor - 2).let { if (it < 0) 0 else it + 1 }
    val prev = new.substring(prevStart, cursor - 1)
    val marker = listOf("- [ ] ", "- [x] ", "- [X] ", "☐ ", "☑ ", "• ", "- ", "* ", "> ").firstOrNull { prev.startsWith(it) } ?: Regex("^(\\d+)\\. ").find(prev)?.value ?: return null
    if (prev == marker) return new.removeRange(prevStart, cursor) to prevStart
    val next = when {
        marker.startsWith("- [") -> "- [ ] "
        marker == "☑ " -> "☐ "
        marker.first().isDigit() -> "${marker.trimEnd('.', ' ').toInt() + 1}. "
        else -> marker
    }
    return new.replaceRange(cursor, cursor, next) to cursor + next.length
}

/** Text documents anywhere on the phone (needs all-files access to edit them in place). */
object Documents {
    val EXT = setOf("md", "markdown", "txt", "text", "org", "csv", "log", "json", "xml", "yaml", "yml", "ini", "conf", "srt", "tex", "rst", "adoc")

    data class Doc(val path: String, val name: String, val folder: String, val size: Long, val modified: Long)

    fun canEditAll() = android.os.Environment.isExternalStorageManager()

    /**
     * Every text document on the phone, newest first. Android's media index rarely tracks text files,
     * so this walks storage directly (skipping Android/, hidden and app-cache folders).
     */
    fun list(c: Context): List<Doc> {
        val out = ArrayList<Doc>()
        val roots = c.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
        fun walk(d: File, depth: Int) {
            if (depth > 14 || out.size >= 5000) return
            val kids = d.listFiles() ?: return
            for (f in kids) {
                val n = f.name
                if (n.startsWith(".")) continue
                if (f.isDirectory) { if (!(depth == 0 && n == "Android") && n != "node_modules" && n != "cache") walk(f, depth + 1) }
                else if (n.substringAfterLast('.', "").lowercase() in EXT && f.length() < 4 * 1024 * 1024) out += Doc(f.path, n, d.name, f.length(), f.lastModified())
            }
        }
        roots.forEach { walk(it, 0) }
        return out.sortedByDescending { it.modified }
    }

    fun open(path: String): Note? {
        val f = File(path)
        if (!f.isFile || f.length() > 4 * 1024 * 1024) return null
        val text = runCatching { f.readText() }.getOrNull()?.removePrefix("\uFEFF") ?: return null
        return Note(id = "ext:" + path, title = f.nameWithoutExtension, body = text, folder = f.parentFile?.name ?: "", created = f.lastModified(), updated = f.lastModified(),
            ext = f.extension.ifBlank { "txt" }, external = path)
    }

    /** Saves an opened document back to its file; renames it if the title changed. Returns the (possibly new) path. */
    fun save(n: Note): String {
        var f = File(n.external)
        val wanted = n.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
        if (wanted.isNotEmpty() && wanted != f.nameWithoutExtension) {
            val t = File(f.parentFile, wanted + "." + n.ext)
            if (!t.exists() && f.renameTo(t)) f = t
        }
        val tmp = File(f.path + ".scribble-tmp")
        tmp.writeText(n.body); if (!tmp.renameTo(f)) { f.writeText(n.body); tmp.delete() }
        return f.path
    }

    /** content:// links from Wayfinder, the media store or "primary:" folder trees to a real path. */
    fun uriToPath(c: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        val auth = uri.authority ?: return null
        val segs = uri.pathSegments
        if (auth.endsWith(".files") && segs.size >= 2) {
            val rest = segs.drop(1).joinToString("/")
            return when (segs[0]) { "storage" -> android.os.Environment.getExternalStorageDirectory().path + "/" + rest; "root" -> "/storage/" + rest; else -> null }
        }
        if (auth == "media") return runCatching { c.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
        if (auth == "com.android.externalstorage.documents") return runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull()?.let { idToPath(it) }
        return null
    }

    fun treeToPath(tree: Uri): String? = runCatching { android.provider.DocumentsContract.getTreeDocumentId(tree) }.getOrNull()?.let { idToPath(it) }

    private fun idToPath(id: String): String? {
        val vol = id.substringBefore(':'); val rel = id.substringAfter(':', "")
        val base = if (vol == "primary") android.os.Environment.getExternalStorageDirectory().path else "/storage/$vol"
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}
