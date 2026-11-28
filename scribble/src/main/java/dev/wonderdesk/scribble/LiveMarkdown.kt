package dev.wonderdesk.scribble

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wonderdesk.shared.WonderPalette
import dev.wonderdesk.shared.wonderPalette

/*
 * Live preview, like Obsidian's: the Markdown is formatted as you type (headings big and bold, **bold**, *italic*,
 * `code`, ~~struck~~, ==highlight==, [[links]], #tags, quotes and ticked tasks), while the marks themselves stay
 * visible but faint so you can still edit them. The text is never changed, only how it looks.
 */
class LiveMarkdown(private val p: WonderPalette, private val baseSize: Int) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(style(text.text), OffsetMapping.Identity)

    private val faint get() = SpanStyle(color = p.secondaryText.copy(alpha = .55f))

    fun style(s: String): AnnotatedString {
        val b = AnnotatedString.Builder(s)
        if (s.length > 60_000) return b.toAnnotatedString()   // very long files: keep typing instant
        var lineStart = 0
        var inFence = false
        for (line in s.split('\n')) {
            val end = lineStart + line.length
            val t = line.trimStart()
            val lead = line.length - t.length
            when {
                t.startsWith("```") -> { inFence = !inFence; b.addStyle(faint.copy(fontFamily = FontFamily.Monospace), lineStart, end) }
                inFence -> b.addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = p.text.copy(alpha = .06f)), lineStart, end)
                else -> {
                    val h = Regex("^(#{1,6}) ").find(t)
                    if (h != null) {
                        val level = h.groupValues[1].length
                        // Headings stay within the line height so the ruled lines still line up.
                        val scale = when (level) { 1 -> 1.35f; 2 -> 1.25f; 3 -> 1.15f; else -> 1.05f }
                        b.addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseSize * scale).sp, color = p.text), lineStart, end)
                        b.addStyle(faint, lineStart + lead, lineStart + lead + level + 1)
                    }
                    if (t.startsWith("> ")) { b.addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = p.secondaryText), lineStart, end); b.addStyle(faint, lineStart + lead, lineStart + lead + 1) }
                    if (t.startsWith("- [x] ", true) || t.startsWith("☑ ")) {
                        val box = if (t.startsWith("☑ ")) 2 else 6
                        b.addStyle(SpanStyle(color = p.accent, fontWeight = FontWeight.Bold), lineStart + lead, lineStart + lead + box)
                        b.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = p.secondaryText), lineStart + lead + box, end)
                    } else if (t.startsWith("- [ ] ") || t.startsWith("☐ ")) {
                        b.addStyle(SpanStyle(color = p.accent, fontWeight = FontWeight.Bold), lineStart + lead, lineStart + lead + if (t.startsWith("☐ ")) 2 else 6)
                    } else Regex("^([-*+]|\\d+[.)]) ").find(t)?.let { b.addStyle(SpanStyle(color = p.accent, fontWeight = FontWeight.Bold), lineStart + lead, lineStart + lead + it.value.length - 1) }
                    if (t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))) b.addStyle(faint, lineStart, end)
                    inline(line, lineStart, b)
                }
            }
            lineStart = end + 1
        }
        return b.toAnnotatedString()
    }

    private fun inline(line: String, at: Int, b: AnnotatedString.Builder) {
        fun mark(r: Regex, open: Int, close: Int, style: SpanStyle) {
            for (m in r.findAll(line)) {
                val s = at + m.range.first; val e = at + m.range.last + 1
                b.addStyle(style, s, e); b.addStyle(faint, s, s + open); b.addStyle(faint, e - close, e)
            }
        }
        mark(Regex("`[^`\\n]+`"), 1, 1, SpanStyle(fontFamily = FontFamily.Monospace, background = p.text.copy(alpha = .08f)))
        mark(Regex("\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__"), 2, 2, SpanStyle(fontWeight = FontWeight.Bold))
        mark(Regex("(?<![*\\w])\\*[^*\\n]+\\*(?!\\*)|(?<![_\\w])_[^_\\n]+_(?![_\\w])"), 1, 1, SpanStyle(fontStyle = FontStyle.Italic))
        mark(Regex("~~[^~\\n]+~~"), 2, 2, SpanStyle(textDecoration = TextDecoration.LineThrough))
        mark(Regex("==[^=\\n]+=="), 2, 2, SpanStyle(background = Color(0x66FFD54F)))
        mark(Regex("!?\\[\\[[^\\]\\n]+\\]\\]"), 2, 2, SpanStyle(color = p.accent, fontWeight = FontWeight.Medium))
        for (m in Regex("!?\\[([^\\]\\n]+)\\]\\(([^)\\n]+)\\)").findAll(line)) {
            val s = at + m.range.first; val e = at + m.range.last + 1
            val textEnd = at + m.groups[1]!!.range.last + 1
            b.addStyle(SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline), s, textEnd + 1)
            b.addStyle(faint, textEnd + 1, e)
        }
        for (m in Regex("(?<=^|\\s)#[\\p{L}\\d_/-]*\\p{L}[\\p{L}\\d_/-]*").findAll(line))
            b.addStyle(SpanStyle(color = p.accent, background = p.accent.copy(alpha = .10f)), at + m.range.first, at + m.range.last + 1)
    }
}

/* ------------------------------------------------------------------ formatting */

/** Wraps the selection (or the word at the cursor) in [open]/[close], or takes the marks off again if already there. */
fun wrapSelection(v: TextFieldValue, open: String, close: String = open): TextFieldValue {
    val t = v.text
    var s = v.selection.min; var e = v.selection.max
    if (s == e) { // no selection: use the word under the cursor, or insert an empty pair
        val ws = wordLeft(t, wordRight(t, s).coerceAtMost(t.length)).coerceAtMost(s)
        val we = wordRight(t, ws)
        if (we > s && ws <= s && t.substring(ws, we).any(Char::isLetterOrDigit)) { s = ws; e = we }
        else return TextFieldValue(t.replaceRange(s, s, open + close), TextRange(s + open.length))
    }
    if (s >= open.length && t.startsWith(open, s - open.length) && t.startsWith(close, e))
        return TextFieldValue(t.substring(0, s - open.length) + t.substring(s, e) + t.substring(e + close.length), TextRange(s - open.length, e - open.length))
    return TextFieldValue(t.substring(0, s) + open + t.substring(s, e) + close + t.substring(e), TextRange(s + open.length, e + open.length))
}

/** Puts [prefix] at the start of the cursor's line, replacing another line prefix of the same kind; tapping again removes it. */
fun toggleLinePrefix(v: TextFieldValue, prefix: String): TextFieldValue {
    val t = v.text
    val ls = lineStart(t, v.selection.start)
    val le = lineEnd(t, ls)
    val line = t.substring(ls, le)
    val existing = Regex("^(#{1,6} |> |- \\[[ xX]\\] |[-*+] |\\d+[.)] )").find(line)?.value ?: ""
    val newLine = if (existing == prefix) line.removePrefix(existing) else prefix + line.removePrefix(existing)
    val delta = newLine.length - line.length
    return TextFieldValue(t.replaceRange(ls, le, newLine), TextRange((v.selection.start + delta).coerceIn(ls, ls + newLine.length), (v.selection.end + delta).coerceIn(ls, ls + newLine.length)))
}

/** Cycles the cursor's line through no heading, #, ## and ###. */
fun cycleHeading(v: TextFieldValue): TextFieldValue {
    val t = v.text
    val ls = lineStart(t, v.selection.start)
    val line = t.substring(ls, lineEnd(t, ls))
    val level = Regex("^(#{1,6}) ").find(line)?.groupValues?.get(1)?.length ?: 0
    // Passing the line's own prefix again takes it off, so after ### the line goes back to plain text.
    return toggleLinePrefix(v, if (level >= 3) "#".repeat(level) + " " else "#".repeat(level + 1) + " ")
}

/** The bar above the keyboard for formatting, Obsidian style. */
@Composable
fun FormatBar(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, onMore: () -> Unit) {
    val p = wonderPalette()
    @Composable fun key(label: String, desc: String, style: SpanStyle? = null, onClick: () -> Unit) {
        Box(Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(8.dp)).background(p.panel)
            .clickable(onClickLabel = desc, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(label, color = p.text, fontSize = 15.sp, fontWeight = style?.fontWeight ?: FontWeight.SemiBold, fontStyle = style?.fontStyle, textDecoration = style?.textDecoration, fontFamily = style?.fontFamily, maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth().background(p.panel.copy(alpha = .9f)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        key("[[ ]]", "Link to a note") { onChange(wrapSelection(value, "[[", "]]")) }
        key("H", "Heading") { onChange(cycleHeading(value)) }
        key("B", "Bold", SpanStyle(fontWeight = FontWeight.Black)) { onChange(wrapSelection(value, "**")) }
        key("I", "Italic", SpanStyle(fontStyle = FontStyle.Italic)) { onChange(wrapSelection(value, "*")) }
        key("☐", "Task") { onChange(toggleLinePrefix(value, "- [ ] ")) }
        key("•", "Bullet list") { onChange(toggleLinePrefix(value, "- ")) }
        key("1.", "Numbered list") { onChange(toggleLinePrefix(value, "1. ")) }
        key("#", "Tag") { val at = value.selection.start; onChange(TextFieldValue(value.text.replaceRange(at, value.selection.end, "#"), TextRange(at + 1))) }
        key("❝", "Quote") { onChange(toggleLinePrefix(value, "> ")) }
        key("S", "Strikethrough", SpanStyle(textDecoration = TextDecoration.LineThrough)) { onChange(wrapSelection(value, "~~")) }
        key("Mark", "Highlight") { onChange(wrapSelection(value, "==")) }
        key("<>", "Code", SpanStyle(fontFamily = FontFamily.Monospace)) { onChange(wrapSelection(value, "`")) }
        key("Cursor ▸", "Cursor and selection keys") { onMore() }
    }
}

/* ------------------------------------------------------------------ [[ link suggestions */

/** The unfinished "[[query" just before the cursor, as (start of query, query), or null. */
fun openLinkQuery(v: TextFieldValue): Pair<Int, String>? {
    if (!v.selection.collapsed) return null
    val before = v.text.substring(0, v.selection.start)
    val m = Regex("\\[\\[([^\\]\\[\\n|#]{0,80})$").find(before) ?: return null
    return (m.range.first + 2) to m.groupValues[1]
}

/** The name of the complete [[link]] the cursor is inside, if any. */
fun linkAtCursor(v: TextFieldValue): String? {
    val t = v.text; val c = v.selection.start
    val s = t.lastIndexOf("[[", c - 1).takeIf { it >= 0 } ?: return null
    val e = t.indexOf("]]", s).takeIf { it >= 0 && c in (s + 2)..(it + 2) } ?: return null
    if (t.substring(s, e).contains('\n')) return null
    return t.substring(s + 2, e).substringBefore('|').substringBefore('#').trim().ifEmpty { null }
}

/**
 * Shown above the keyboard while you type "[[": notes whose name matches, best first, and a way to link a note
 * that doesn't exist yet. With the cursor inside a finished link it offers to open that note instead.
 */
@Composable
fun LinkSuggestions(value: TextFieldValue, titles: List<String>, onChange: (TextFieldValue) -> Unit, onOpen: (String) -> Unit) {
    val p = wonderPalette()
    val q = openLinkQuery(value)
    val inside = if (q == null) linkAtCursor(value) else null
    if (q == null && inside == null) return
    @Composable fun chip(label: String, strong: Boolean = false, onClick: () -> Unit) {
        Box(Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(16.dp)).background(if (strong) p.accent else p.accent.copy(alpha = .14f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(label, color = if (strong) Color.White else p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth().background(p.panel.copy(alpha = .95f)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (inside != null) {
            chip("Open “$inside”", strong = true) { onOpen(inside) }
            return@Row
        }
        val (start, query) = q!!
        val ql = query.trim().lowercase()
        val matches = titles.distinct().filter { ql.isEmpty() || it.lowercase().contains(ql) }
            .sortedWith(compareBy({ !it.lowercase().startsWith(ql) }, { it.length })).take(12)
        fun pick(name: String) {
            val t = value.text
            val after = t.substring(value.selection.start)
            val closer = if (after.startsWith("]]")) "" else "]]"
            val nt = t.substring(0, start) + name + closer + after
            onChange(TextFieldValue(nt, TextRange(start + name.length + 2)))
        }
        if (matches.isEmpty() && ql.isEmpty()) Text("Type a note's name", color = p.secondaryText, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
        matches.forEach { m -> chip(m) { pick(m) } }
        if (ql.isNotEmpty() && matches.none { it.equals(query.trim(), true) }) chip("+ New note “${query.trim()}”") { pick(query.trim()) }
    }
}
