package dev.wonderdesk.shared

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@Composable
fun EditorScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("draft", Context.MODE_PRIVATE) }
    val op = rememberOperation()
    var text by remember { mutableStateOf(prefs.getString("text", "") ?: "") }
    var fileName by remember { mutableStateOf(prefs.getString("name", "Note.txt") ?: "Note.txt") }
    var origin by remember { mutableStateOf("Local recovered draft") }
    var saved by remember { mutableStateOf(true) }
    var pendingExport by remember { mutableStateOf("") }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var replace by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var drawMode by remember { mutableStateOf(false) }
    val statistics = remember(text) { textStatistics(text) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { value ->
        if (value != null) {
            val exportText = pendingExport
            op.run("Exporting UTF-8 text to a new document", {
                context.contentResolver.openOutputStream(value, "w")!!.use { it.write(exportText.toByteArray(Charsets.UTF_8)) }
            }) { op.notice("New document written. The original source was not overwritten.") }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { value -> pendingImport = value }
    LaunchedEffect(text, fileName) {
        saved = false
        delay(250)
        val currentText = text; val currentName = fileName
        saved = withContext(Dispatchers.IO) { prefs.edit().putString("text", currentText).putString("name", currentName).commit() }
        if (!saved) op.notice("Draft could not be saved. Export it before leaving.")
    }
    LaunchedEffect(Unit) {
        val intent = (context as ComponentActivity).intent
        val shared = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        if (shared != null) {
            prefs.edit().putString("previous", text).apply()
            text = shared.take(1_000_000); origin = "Shared text in local draft"
        } else pendingImport = intent.data
    }
    if (drawMode) {
        // Simple drawing pad, saved as PNG next to the text draft.
        val drawBitmap = remember { mutableStateOf<Bitmap?>(null) }
        val drawColor = remember { mutableStateOf(0xFF000000.toInt()) }
        var last by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { drawMode = false }) { Text("Back to text") }
                listOf(0xFF000000.toInt(), 0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt()).forEach { color ->
                    TextButton(onClick = { drawColor.value = color }) { Text(if (drawColor.value == color) "•" else "", color = androidx.compose.ui.graphics.Color(color)) }
                }
                TextButton(onClick = {
                    val bitmap = drawBitmap.value ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    val file = File(context.filesDir, "scribble-drawing.png")
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    op.notice("Drawing saved inside the app. Export it with Share below.")
                }) { Text("Save") }
                TextButton(onClick = {
                    val file = File(context.filesDir, "scribble-drawing.png")
                    if (file.exists()) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        context.share(uri, "image/png", op::notice)
                    } else op.notice("Save the drawing first.")
                }) { Text("Share") }
            }
            Canvas(Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color.White)
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { offset ->
                        last = offset
                    }, onDrag = { change, _ ->
                        val bmp = drawBitmap.value
                        val target = if (bmp == null || bmp.width != size.width.toInt() || bmp.height != size.height.toInt()) {
                            val fresh = Bitmap.createBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            fresh.eraseColor(android.graphics.Color.WHITE)
                            drawBitmap.value = fresh
                            fresh
                        } else bmp
                        val canvas = android.graphics.Canvas(target)
                        val paint = android.graphics.Paint().apply { color = drawColor.value; strokeWidth = 12f; isAntiAlias = true; style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
                        canvas.drawLine(last.x, last.y, change.position.x, change.position.y, paint)
                        last = change.position
                    })
                }) {
                drawBitmap.value?.let { drawImage(it.asImageBitmap()) }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AquaButton(onClick = { open.launch(arrayOf("text/*", "application/json", "application/xml")) }, enabled = !op.busy) { Text("Import file") }
            AquaSecondaryButton(onClick = { pendingExport = text; create.launch(fileName.ifBlank { "Note.txt" }) }, enabled = !op.busy) { Text("Export copy") }
            TextButton(onClick = { replace = true }, enabled = !op.busy) { Text("Find") }
            TextButton(onClick = { drawMode = true }) { Text("Draw ✏️") }
        }
        OutlinedTextField(fileName, { fileName = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Export file name") }, singleLine = true)
        Text("$origin · ${if (saved) "Draft saved locally" else "Saving draft…"}", style = MaterialTheme.typography.bodySmall)
        Text("${statistics.words} words · ${statistics.lines} lines · ${statistics.characters} characters · ~${readingTimeMinutes(statistics.words)} min read", style = MaterialTheme.typography.bodySmall)
        OperationStatus(op)
        OutlinedTextField(text, { if (it.length <= 1_000_000) text = it else op.notice("Draft limit is 1,000,000 characters. Export and start another draft.") }, Modifier.fillMaxWidth().weight(1f), label = { Text("Plain text / Markdown") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                context.launchSafely(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share text"), op::notice)
            }) { Text("Share text") }
            TextButton(onClick = { text = prefs.getString("previous", text) ?: text; origin = "Previous imported draft recovered" }, enabled = prefs.contains("previous")) { Text("Recover previous") }
        }
    }
    pendingImport?.let { uri ->
        AlertDialog(onDismissRequest = { pendingImport = null }, title = { Text("Replace local draft with imported text?") }, text = { Text("Your current draft will be kept as the previous draft. Imports are limited to 1 MiB of valid UTF-8. This will not modify the source file.") }, confirmButton = {
            TextButton(onClick = {
                pendingImport = null
                op.run("Reading UTF-8 file", {
                    val data = context.contentResolver.openInputStream(uri)!!.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 1024 * 1024) { "File exceeds 1 MiB" }; output.write(buffer, 0, n) }
                        output.toByteArray()
                    }
                    require(data.size <= 1024 * 1024) { "File exceeds 1 MiB" }
                    val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString().removePrefix("\uFEFF")
                    decoded to context.displayName(uri)
                }) {
                    prefs.edit().putString("previous", text).apply()
                    text = it.first; fileName = it.second; origin = "Imported copy in local draft"; op.notice("Imported. Export copy writes a new document; the source is read-only here.")
                }
            }) { Text("Import copy") }
        }, dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } })
    }
    if (replace) AlertDialog(onDismissRequest = { replace = false }, title = { Text("Find and replace (case-sensitive)") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(find, { find = it }, label = { Text("Find") })
            OutlinedTextField(replacement, { replacement = it }, label = { Text("Replace with") })
            if (find.isNotEmpty()) Text("${countTextMatches(text, find)} matching positions")
        }
    }, confirmButton = { TextButton(onClick = { if (find.isNotEmpty()) { prefs.edit().putString("previous", text).apply(); text = text.replace(find, replacement) }; replace = false }) { Text("Replace all") } }, dismissButton = { TextButton(onClick = { replace = false }) { Text("Close") } })
}
