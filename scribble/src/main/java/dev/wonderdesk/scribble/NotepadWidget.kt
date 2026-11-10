package dev.wonderdesk.scribble

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen notepad: shows your pinned (or latest) note, tap to open it, + for a new one. */
class NotepadWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { refresh(c) }

    companion object {
        fun refresh(c: Context) {
            val app = c.applicationContext
            Thread {
                val mgr = AppWidgetManager.getInstance(app)
                val ids = mgr.getAppWidgetIds(ComponentName(app, NotepadWidget::class.java))
                if (ids.isEmpty()) return@Thread
                val note = runCatching { NoteStore(app).all().filter { it.trashed == 0L && !it.sketch }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated }).firstOrNull() }.getOrNull()
                val v = RemoteViews(app.packageName, R.layout.widget_notepad)
                v.setTextViewText(R.id.n_title, note?.displayTitle ?: "Scribble")
                v.setTextViewText(R.id.n_body, note?.body?.take(1200)?.replace("- [ ] ", "☐ ")?.replace("- [x] ", "☑ ") ?: "Tap + to write a note.")
                val open = PendingIntent.getActivity(app, 1, Intent(app, MainActivity::class.java).putExtra("open", note?.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val add = PendingIntent.getActivity(app, 2, Intent(app, MainActivity::class.java).putExtra("new", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                v.setOnClickPendingIntent(R.id.n_root, open); v.setOnClickPendingIntent(R.id.n_add, add)
                ids.forEach { mgr.updateAppWidget(it, v) }
            }.start()
        }
    }
}
