package dev.wonderdesk.scribble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Home-screen notepad: shows your pinned (or latest) note, tap to open it, + for a new one. */
class NotepadWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { refresh(c) }
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action != TICK) return
        val line = i.getIntExtra("line", -1)
        val id = i.getStringExtra("note") ?: return
        if (line < 0) { c.startActivity(Intent(c, MainActivity::class.java).putExtra("open", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        val r = goAsync()
        Thread { runCatching { val store = NoteStore(c.applicationContext); store.all().find { it.id == id }?.let { n -> store.save(n.copy(body = toggleLine(n.body, line), updated = System.currentTimeMillis())) } }
            refresh(c); r.finish() }.start()
    }

    companion object {
        const val TICK = "dev.wonderdesk.scribble.WIDGET_TICK"
        fun refresh(c: Context) {
            val app = c.applicationContext
            Thread {
                val mgr = AppWidgetManager.getInstance(app)
                val ids = mgr.getAppWidgetIds(ComponentName(app, NotepadWidget::class.java))
                if (ids.isEmpty()) return@Thread
                ids.forEach { id ->
                val chosen = app.getSharedPreferences("widget-settings", Context.MODE_PRIVATE).getString("$id.note", "") ?: ""
                val note = runCatching { NoteStore(app).all().filter { it.trashed == 0L && !it.sketch }.let { all -> all.find { it.id == chosen } ?: all.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated }).firstOrNull() } }.getOrNull()
                val v = RemoteViews(app.packageName, R.layout.widget_notepad)
                v.setTextViewText(R.id.n_title, note?.displayTitle ?: "Scribble")
                val lines = note?.body?.lines()?.take(80).orEmpty()
                if (android.os.Build.VERSION.SDK_INT >= 31 && note != null) {
                    // Each line is its own row; checklist lines tick straight from the home screen.
                    val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true)
                    lines.forEachIndexed { i, l ->
                        val row = RemoteViews(app.packageName, R.layout.widget_note_line)
                        val t = l.trimStart()
                        val done = t.startsWith("- [x] ", true) || t.startsWith("☑ ")
                        val todo = t.startsWith("- [ ] ") || t.startsWith("☐ ")
                        row.setTextViewText(R.id.l_text, when { done -> "☑  " + t.drop(if (t.startsWith("☑ ")) 2 else 6); todo -> "☐  " + t.drop(if (t.startsWith("☐ ")) 2 else 6); else -> t.removePrefix("# ").removePrefix("## ") })
                        if (done) row.setInt(R.id.l_text, "setPaintFlags", android.graphics.Paint.STRIKE_THRU_TEXT_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
                        if (t.startsWith("#")) row.setFloat(R.id.l_text, "setTextSize", 16f)
                        row.setOnClickFillInIntent(R.id.l_text, Intent().putExtra("line", if (todo || done) i else -1))
                        items.addItem(i.toLong(), row)
                    }
                    v.setRemoteAdapter(R.id.n_list, items.setViewTypeCount(1).build())
                    v.setViewVisibility(R.id.n_list, android.view.View.VISIBLE); v.setViewVisibility(R.id.n_body, android.view.View.GONE)
                    v.setPendingIntentTemplate(R.id.n_list, PendingIntent.getBroadcast(app, 3 + id, Intent(app, NotepadWidget::class.java).setAction(TICK).putExtra("note", note.id).putExtra("widget", id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
                } else v.setTextViewText(R.id.n_body, note?.body?.take(1200)?.replace("- [ ] ", "☐ ")?.replace("- [x] ", "☑ ") ?: "Tap + to write a note.")
                val open = PendingIntent.getActivity(app, 1, Intent(app, MainActivity::class.java).putExtra("open", note?.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val add = PendingIntent.getActivity(app, 2, Intent(app, MainActivity::class.java).putExtra("new", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                v.setOnClickPendingIntent(R.id.n_root, open); v.setOnClickPendingIntent(R.id.n_add, add)
                mgr.updateAppWidget(id, v)
                }
            }.start()
        }
    }
}

/** Which note a notepad widget shows: your pinned (or latest) note, or one you pick. */
class NotepadWidgetSetup : dev.wonderdesk.shared.WidgetSetupActivity() {
    override fun redraw() { NotepadWidget.refresh(this) }
    @androidx.compose.runtime.Composable override fun Content() {
        val c = androidx.compose.ui.platform.LocalContext.current
        val prefs = androidx.compose.runtime.remember { dev.wonderdesk.shared.WidgetPrefs(c, id) }
        var chosen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.str("note", "")) }
        var notes by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Note>>(emptyList()) }
        androidx.compose.runtime.LaunchedEffect(Unit) { notes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { NoteStore(c).all().filter { it.trashed == 0L && !it.sketch }.sortedByDescending { it.updated } }.getOrDefault(emptyList()) } }
        dev.wonderdesk.shared.WidgetSetup("Scribble", "Notepad widget", onCancel = { finish() }, onSave = { prefs.put("note" to chosen); save() }) {
            androidx.compose.material3.Text("Checklist lines can be ticked right on the home screen (Android 12 and newer).")
            dev.wonderdesk.shared.Panel(title = "Note to show") {
                dev.wonderdesk.shared.ListRow("My pinned note, or the latest", selected = chosen.isEmpty(), onClick = { chosen = "" })
                notes.take(40).forEach { n -> dev.wonderdesk.shared.PanelDivider(); dev.wonderdesk.shared.ListRow(n.displayTitle, selected = chosen == n.id, onClick = { chosen = n.id }) }
            }
        }
    }
}
