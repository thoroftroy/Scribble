package dev.wonderdesk.scribble

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** "Remind me" on a note: a notification at the chosen time that opens the note. Survives restarts. */
object Reminders {
    private const val CHANNEL = "scribble-reminders"
    private fun prefs(c: Context) = c.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun at(c: Context, id: String): Long = prefs(c).getLong(id, 0L).takeIf { it > System.currentTimeMillis() } ?: 0L

    fun set(c: Context, id: String, title: String, time: Long) {
        prefs(c).edit().putLong(id, time).putString("$id.title", title).apply()
        schedule(c, id, time)
    }

    fun clear(c: Context, id: String) {
        prefs(c).edit().remove(id).remove("$id.title").apply()
        c.getSystemService(AlarmManager::class.java).cancel(pending(c, id))
    }

    private fun pending(c: Context, id: String) = PendingIntent.getBroadcast(c, id.hashCode(), Intent(c, ReminderReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(c: Context, id: String, time: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(c, id)) }
    }

    fun rescheduleAll(c: Context) {
        val now = System.currentTimeMillis()
        prefs(c).all.forEach { (k, v) -> if (!k.endsWith(".title") && v is Long) { if (v > now) schedule(c, k, v) else fire(c, k) } }
    }

    fun fire(c: Context, id: String) {
        val title = prefs(c).getString("$id.title", null)?.ifBlank { null } ?: "Your note"
        prefs(c).edit().remove(id).remove("$id.title").apply()
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Note reminders", NotificationManager.IMPORTANCE_HIGH).apply { description = "Reminders you set on notes" })
        val open = PendingIntent.getActivity(c, id.hashCode(), Intent(c, MainActivity::class.java).putExtra("open", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(id.hashCode(), android.app.Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title).setContentText("Reminder from Scribble · tap to open").setContentIntent(open).setAutoCancel(true)
                .setCategory(android.app.Notification.CATEGORY_REMINDER).build())
        }
    }

    fun label(t: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val day = when {
            cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
            cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) + 1 -> "Tomorrow"
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
        }
        return "$day, " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.rescheduleAll(c)
            else -> i.getStringExtra("id")?.let { Reminders.fire(c, it) }
        }
    }
}

/** Pick when to be reminded: quick choices, or any date and time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDialog(noteId: String, title: String, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val p = wonderPalette()
    val current = remember { Reminders.at(context, noteId) }
    var custom by remember { mutableStateOf(false) }
    var pickedDay by remember { mutableStateOf<Long?>(null) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    fun set(t: Long) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        Reminders.set(context, noteId, title, t); onSet("I'll remind you " + Reminders.label(t).replaceFirstChar { it.lowercase() }); onDismiss()
    }
    fun at(daysAhead: Int, hour: Int) = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, daysAhead); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    when {
        pickedDay != null -> {
            val tp = rememberTimePickerState(9, 0)
            WonderDialog(onDismissRequest = onDismiss, title = { Text("At what time?") }, text = { TimePicker(tp) }, confirmButton = {
                TextButton(onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = pickedDay!!; set(Calendar.HOUR_OF_DAY, tp.hour); set(Calendar.MINUTE, tp.minute); set(Calendar.SECOND, 0) }
                    if (c.timeInMillis > System.currentTimeMillis()) set(c.timeInMillis) else onSet("That time has already passed")
                }) { Text("Set reminder") }
            }, dismissButton = { TextButton(onClick = { pickedDay = null }) { Text("Back") } })
        }
        custom -> {
            val dp = rememberDatePickerState(System.currentTimeMillis())
            DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
                TextButton(onClick = { dp.selectedDateMillis?.let { utc ->
                    val z = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
                    pickedDay = Calendar.getInstance().apply { set(z.get(Calendar.YEAR), z.get(Calendar.MONTH), z.get(Calendar.DAY_OF_MONTH), 0, 0, 0) }.timeInMillis
                    custom = false } }) { Text("Next") }
            }, dismissButton = { TextButton(onClick = { custom = false }) { Text("Back") } }) { DatePicker(dp) }
        }
        else -> WonderDialog(onDismissRequest = onDismiss, title = { Text("Remind me") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (current > 0) Text("Set for ${Reminders.label(current)}", style = MaterialTheme.typography.bodyMedium, color = p.accent)
                Panel {
                    ListRow("In 1 hour", icon = WonderIcons.Timer, tint = Color(0xFF1E7FE8), onClick = { set(System.currentTimeMillis() + 3_600_000L) }); PanelDivider()
                    val evening = at(0, 18)
                    if (evening > System.currentTimeMillis() + 600_000) { ListRow("This evening", subtitle = Reminders.label(evening), icon = WonderIcons.NightsStay, tint = Color(0xFF5B4BC4), onClick = { set(evening) }); PanelDivider() }
                    ListRow("Tomorrow morning", subtitle = Reminders.label(at(1, 9)), icon = WonderIcons.WbSunny, tint = Color(0xFFE39A12), onClick = { set(at(1, 9)) }); PanelDivider()
                    ListRow("Pick a date and time…", icon = WonderIcons.CalendarMonth, tint = Color(0xFFE5483C), chevron = true, onClick = { custom = true })
                    if (current > 0) { PanelDivider(); ListRow("Remove reminder", danger = true, icon = WonderIcons.Delete, onClick = { Reminders.clear(context, noteId); onSet("Reminder removed"); onDismiss() }) }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    }
}
