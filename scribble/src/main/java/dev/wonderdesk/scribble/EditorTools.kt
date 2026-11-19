package dev.wonderdesk.scribble

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wonderdesk.shared.wonderPalette

/* ------------------------------------------------------------------ pasted tables */

private fun decode(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
private fun escCell(s: String) = s.trim().replace("|", "\\|").replace("\r", "").replace("\n", "<br>")

private fun mdTable(rows: List<List<String>>): String {
    val cols = rows.maxOf { it.size }.coerceAtLeast(1)
    val full = rows.map { r -> (r + List(cols - r.size) { "" }).map(::escCell) }
    val sb = StringBuilder()
    sb.append("| ").append(full[0].joinToString(" | ")).append(" |\n")
    sb.append("|").append(" --- |".repeat(cols)).append("\n")
    full.drop(1).forEach { sb.append("| ").append(it.joinToString(" | ")).append(" |\n") }
    return sb.toString().trimEnd('\n')
}

/** Rows and cells of an HTML table (what Obsidian, browsers and Docs put on the clipboard). */
private fun htmlRows(html: String): List<List<String>>? {
    if (!html.contains("<table", ignoreCase = true)) return null
    val rows = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).map { tr ->
        Regex("<t[hd][^>]*>(.*?)</t[hd]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(tr.groupValues[1]).map { td ->
            decode(td.groupValues[1].replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("</p>\\s*<p[^>]*>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")).trim()
        }.toList()
    }.filter { it.isNotEmpty() }.toList()
    return rows.takeIf { it.size >= 1 && it.any { r -> r.size >= 2 } }
}

private fun isRule(line: String) = line.trim().trim('|').split("|").let { c -> c.isNotEmpty() && c.all { it.trim().matches(Regex(":?-{2,}:?")) } }

/**
 * Turns a pasted table into a proper Markdown table: tab separated rows (a rendered table copied from Obsidian,
 * a web page or a spreadsheet), an HTML table from the clipboard, or pipe rows that lack the outer pipes.
 * Returns null when the pasted text is not a table.
 */
fun pastedTableToMarkdown(text: String, html: String?): String? {
    val t = text.trim('\n', '\r')
    if (t.isBlank()) return null
    val lines = t.lines().filter { it.isNotBlank() }
    // 1. HTML on the clipboard that matches what was pasted.
    if (html != null) htmlRows(html)?.let { rows -> if (rows.size >= 2 || (rows.size == 1 && rows[0].size >= 2 && lines.size >= 1)) return mdTable(rows) }
    // 2. Tab separated rows.
    if (lines.size >= 2 && lines.all { it.contains('\t') }) return mdTable(lines.map { it.split('\t') })
    // 3. Pipe rows without the outer pipes, or with a ragged shape.
    if (lines.size >= 2 && lines.all { it.contains('|') } && lines.any { isRule(it) } && !lines.all { it.trimStart().startsWith("|") && it.trimEnd().endsWith("|") }) {
        val rows = lines.filterNot { isRule(it) }.map { l -> l.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|")).map { it.trim() } }
        return mdTable(rows)
    }
    return null
}

fun clipboardHtml(context: Context): String? = runCatching {
    context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.htmlText
}.getOrNull()

fun clipboardText(context: Context): String? = runCatching {
    context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

/** If [new] is [old] with a pasted table dropped in, returns the text with that table converted. */
fun convertPastedTable(old: TextFieldValue, new: TextFieldValue, context: Context): TextFieldValue? {
    val grew = new.text.length - old.text.length
    if (grew < 5 || new.selection.collapsed.not()) return null
    var pre = 0
    val max = minOf(old.text.length, new.text.length)
    while (pre < max && old.text[pre] == new.text[pre]) pre++
    var suf = 0
    while (suf < old.text.length - pre && old.text[old.text.length - 1 - suf] == new.text[new.text.length - 1 - suf]) suf++
    val inserted = new.text.substring(pre, new.text.length - suf)
    if (!inserted.contains('\n') && !inserted.contains('\t') && !inserted.contains('|')) return null
    val clip = clipboardText(context)
    val html = if (clip != null && clip.trim() == inserted.trim()) clipboardHtml(context) else null
    val md = pastedTableToMarkdown(inserted, html) ?: return null
    val before = new.text.substring(0, pre)
    val after = new.text.substring(new.text.length - suf)
    val lead = if (before.isEmpty() || before.endsWith("\n\n")) "" else if (before.endsWith("\n")) "\n" else "\n\n"
    val tail = if (after.startsWith("\n\n")) "" else if (after.startsWith("\n")) "\n" else if (after.isEmpty()) "\n" else "\n\n"
    val out = before + lead + md + tail + after
    val caret = (before + lead + md + tail).length
    return TextFieldValue(out, TextRange(caret))
}

/* ------------------------------------------------------------------ moving and selecting */

private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

fun wordLeft(t: String, from: Int): Int {
    var i = from.coerceIn(0, t.length)
    while (i > 0 && !isWordChar(t[i - 1])) i--
    while (i > 0 && isWordChar(t[i - 1])) i--
    return i
}

fun wordRight(t: String, from: Int): Int {
    var i = from.coerceIn(0, t.length)
    while (i < t.length && !isWordChar(t[i])) i++
    while (i < t.length && isWordChar(t[i])) i++
    return i
}

fun lineStart(t: String, at: Int) = t.lastIndexOf('\n', (at - 1).coerceAtLeast(-1)).let { if (at == 0) 0 else it + 1 }
fun lineEnd(t: String, at: Int): Int = t.indexOf('\n', at.coerceIn(0, t.length)).let { if (it < 0) t.length else it }

/** Same column one line up or down (clamped to the line's length). */
fun lineUpDown(t: String, at: Int, down: Boolean): Int {
    val s = lineStart(t, at)
    val col = at - s
    if (down) {
        val e = lineEnd(t, at)
        if (e >= t.length) return t.length
        val ns = e + 1
        return (ns + col).coerceAtMost(lineEnd(t, ns))
    }
    if (s == 0) return 0
    val ps = lineStart(t, s - 1)
    return (ps + col).coerceAtMost(s - 1)
}

/**
 * A row of cursor and selection buttons above the keyboard: character, word, line and paragraph moves, a
 * Select switch that turns every move into extending a selection, select word / line / all, cut, copy and paste.
 */
@Composable
fun EditorBar(value: TextFieldValue, selecting: Boolean, onSelecting: (Boolean) -> Unit, onChange: (TextFieldValue) -> Unit, onPaste: () -> Unit, onCopy: (Boolean) -> Unit, anchor: Int, onAnchor: (Int) -> Unit) {
    val p = wonderPalette()
    val t = value.text
    val active = value.selection.end
    fun go(to: Int) {
        val dest = to.coerceIn(0, t.length)
        if (selecting) {
            val a = if (value.selection.collapsed) value.selection.start else anchor
            onAnchor(a)
            onChange(value.copy(selection = TextRange(a, dest)))
        } else onChange(value.copy(selection = TextRange(dest)))
    }
    @Composable fun key(label: String, strong: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
        Box(Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(8.dp))
            .background(if (strong) p.accent else p.panel).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 11.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (strong) androidx.compose.ui.graphics.Color.White else if (enabled) p.text else p.secondaryText.copy(alpha = .5f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth().background(p.panel.copy(alpha = .9f)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        key(if (selecting) "Selecting" else "Select", strong = selecting) { onSelecting(!selecting); if (!selecting) onAnchor(value.selection.start) }
        key("⇤") { go(lineStart(t, active)) }
        key("◂◂") { go(wordLeft(t, active)) }
        key("◂") { go(active - 1) }
        key("▸") { go(active + 1) }
        key("▸▸") { go(wordRight(t, active)) }
        key("⇥") { go(lineEnd(t, active)) }
        key("▲") { go(lineUpDown(t, active, false)) }
        key("▼") { go(lineUpDown(t, active, true)) }
        key("Word") { val s = wordLeft(t, wordRight(t, active)); onAnchor(s); onChange(value.copy(selection = TextRange(s, wordRight(t, s)))); onSelecting(true) }
        key("Line") { val s = lineStart(t, active); onAnchor(s); onChange(value.copy(selection = TextRange(s, minOf(lineEnd(t, active) + 1, t.length)))); onSelecting(true) }
        key("All") { onAnchor(0); onChange(value.copy(selection = TextRange(0, t.length))); onSelecting(true) }
        key("Cut", enabled = !value.selection.collapsed) { onCopy(true) }
        key("Copy", enabled = !value.selection.collapsed) { onCopy(false) }
        key("Paste") { onPaste() }
    }
}
