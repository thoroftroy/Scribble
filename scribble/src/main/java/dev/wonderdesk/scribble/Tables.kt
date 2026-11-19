package dev.wonderdesk.scribble

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*

/* ============================================================================================================
 * Markdown tables, shown and edited as a real grid inside the note while you write.
 * ========================================================================================================== */

/** One piece of a note: plain text, or a table. [start] and [end] are character offsets in the note (end exclusive). */
data class Seg(val start: Int, val end: Int, val table: Boolean)

private fun isRow(line: String) = line.trimStart().startsWith("|")
private fun isSeparator(line: String) = isRow(line) && line.trim().trim('|').split('|').all { it.trim().matches(Regex(":?-{1,}:?")) } && line.contains('-')

/** Splits a note into text and table pieces. A table is a header row, a separator row and any rows after, all starting with a pipe. */
fun splitSegments(text: String): List<Seg> {
    val out = ArrayList<Seg>()
    var pos = 0; var textStart = 0
    val lines = text.split('\n')
    var i = 0
    while (i < lines.size) {
        val len = lines[i].length
        if (isRow(lines[i]) && i + 1 < lines.size && isSeparator(lines[i + 1]) && !isSeparator(lines[i])) {
            var j = i + 2; var end = pos + len + 1 + lines[i + 1].length
            while (j < lines.size && isRow(lines[j])) { end += 1 + lines[j].length; j++ }
            if (pos > textStart) out += Seg(textStart, pos, false)
            // the newline that ends the table belongs to the table
            val tableEnd = if (end < text.length && text[end] == '\n') end + 1 else end
            out += Seg(pos, tableEnd, true)
            pos = tableEnd; textStart = tableEnd
            i = j; continue
        }
        pos += len + 1; i++
    }
    if (textStart < text.length || out.isEmpty() || out.last().table) out += Seg(textStart, text.length, false)
    return out
}

/** A table as cells: row 0 is the header. [align] is "l", "c" or "r" per column. */
data class Grid(val rows: List<List<String>>, val align: List<Char>) {
    val cols get() = rows.maxOfOrNull { it.size } ?: 0
}

private fun splitCells(line: String): List<String> {
    val t = line.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
    val out = ArrayList<String>(); val sb = StringBuilder(); var i = 0
    while (i < t.length) { if (t[i] == '\\' && i + 1 < t.length && t[i + 1] == '|') { sb.append('|'); i += 2 } else if (t[i] == '|') { out += sb.toString().trim(); sb.clear(); i++ } else { sb.append(t[i]); i++ } }
    out += sb.toString().trim()
    return out
}

fun parseGrid(markdown: String): Grid {
    val lines = markdown.trimEnd('\n').split('\n')
    val head = splitCells(lines[0])
    val sep = splitCells(lines.getOrElse(1) { "" })
    val align = head.indices.map { c -> val s = sep.getOrElse(c) { "---" }; when { s.startsWith(":") && s.endsWith(":") && s.length > 1 -> 'c'; s.endsWith(":") -> 'r'; else -> 'l' } }
    val rows = listOf(head) + lines.drop(2).map { r -> splitCells(r).let { cs -> cs + List((head.size - cs.size).coerceAtLeast(0)) { "" } } }
    return Grid(rows.map { r -> r.take(head.size) }, align)
}

fun gridToMarkdown(g: Grid): String {
    val n = g.cols.coerceAtLeast(1)
    fun cell(s: String) = s.replace("\n", " ").replace("|", "\\|")
    fun row(r: List<String>) = "| " + (0 until n).joinToString(" | ") { cell(r.getOrElse(it) { "" }) } + " |"
    val sep = "| " + (0 until n).joinToString(" | ") { when (g.align.getOrElse(it) { 'l' }) { 'c' -> ":---:"; 'r' -> "---:"; else -> "---" } } + " |"
    return (listOf(row(g.rows.firstOrNull() ?: emptyList()), sep) + g.rows.drop(1).map { row(it) }).joinToString("\n")
}

/** Sorts the body rows by a column; numbers sort as numbers. */
fun sortGrid(g: Grid, col: Int, descending: Boolean): Grid {
    val body = g.rows.drop(1)
    val cmp = Comparator<List<String>> { a, b ->
        val x = a.getOrElse(col) { "" }; val y = b.getOrElse(col) { "" }
        val xn = x.replace(",", "").toDoubleOrNull(); val yn = y.replace(",", "").toDoubleOrNull()
        if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y, ignoreCase = true)
    }
    return g.copy(rows = listOf(g.rows[0]) + body.sortedWith(if (descending) cmp.reversed() else cmp))
}

fun <T> List<T>.moved(from: Int, to: Int): List<T> { if (from == to || from !in indices || to !in indices) return this; val m = toMutableList(); val x = m.removeAt(from); m.add(to, x); return m }

/* ------------------------------------------------------------------ the grid on screen */

@Composable
fun TableEditor(markdown: String, onChange: (String) -> Unit) {
    val p = wonderPalette()
    val grid = remember(markdown) { parseGrid(markdown) }
    fun commit(g: Grid) = onChange(gridToMarkdown(g))
    val cols = grid.cols
    val cellW = 116.dp; val rowH = 44.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    var colMenu by remember { mutableStateOf<Int?>(null) }
    var rowMenu by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, p.panelBorder, RoundedCornerShape(10.dp))) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                grid.rows.forEachIndexed { r, row ->
                    var drag by remember(markdown, r) { mutableStateOf(0f) }
                    Row(Modifier.height(rowH).background(if (r == 0) p.accent.copy(alpha = .16f) else if (r % 2 == 0) p.panel.copy(alpha = .5f) else androidx.compose.ui.graphics.Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                        // handle: tap for row options, drag up or down to move the row
                        Box(Modifier.width(30.dp).fillMaxHeight().pointerInput(markdown, r) {
                            detectDragGestures(onDragEnd = { drag = 0f }, onDragCancel = { drag = 0f }) { change, amount ->
                                change.consume(); drag += amount.y
                                if (r > 0 && kotlin.math.abs(drag) > rowPx * .6f) {
                                    val to = (r + if (drag > 0) 1 else -1).coerceIn(1, grid.rows.lastIndex)
                                    if (to != r) commit(grid.copy(rows = grid.rows.moved(r, to)))
                                    drag = 0f
                                }
                            }
                        }.clickable { rowMenu = r }, contentAlignment = Alignment.Center) {
                            Text(if (r == 0) "▾" else "⋮⋮", color = p.secondaryText, style = MaterialTheme.typography.labelMedium)
                            DropdownMenu(rowMenu == r, { rowMenu = null }) {
                                if (r == 0) {
                                    DropdownMenuItem({ Text("Insert row below") }, { rowMenu = null; commit(grid.copy(rows = grid.rows.toMutableList().also { it.add(1, List(cols) { "" }) })) })
                                    DropdownMenuItem({ Text("Add a column") }, { rowMenu = null; commit(Grid(grid.rows.map { it + "" }, grid.align + 'l')) })
                                } else {
                                    DropdownMenuItem({ Text("Insert row above") }, { rowMenu = null; commit(grid.copy(rows = grid.rows.toMutableList().also { it.add(r, List(cols) { "" }) })) })
                                    DropdownMenuItem({ Text("Insert row below") }, { rowMenu = null; commit(grid.copy(rows = grid.rows.toMutableList().also { it.add(r + 1, List(cols) { "" }) })) })
                                    DropdownMenuItem({ Text("Duplicate row") }, { rowMenu = null; commit(grid.copy(rows = grid.rows.toMutableList().also { it.add(r + 1, row) })) })
                                    if (r > 1) DropdownMenuItem({ Text("Move up") }, { rowMenu = null; commit(grid.copy(rows = grid.rows.moved(r, r - 1))) })
                                    if (r < grid.rows.lastIndex) DropdownMenuItem({ Text("Move down") }, { rowMenu = null; commit(grid.copy(rows = grid.rows.moved(r, r + 1))) })
                                    DropdownMenuItem({ Text("Delete row", color = p.danger) }, { rowMenu = null; commit(grid.copy(rows = grid.rows.filterIndexed { i, _ -> i != r })) })
                                }
                            }
                        }
                        for (c in 0 until cols) {
                            Box(Modifier.width(cellW).fillMaxHeight().border(.5.dp, p.panelBorder.copy(alpha = .6f)).padding(horizontal = 8.dp), contentAlignment = when (grid.align.getOrElse(c) { 'l' }) { 'c' -> Alignment.Center; 'r' -> Alignment.CenterEnd; else -> Alignment.CenterStart }) {
                                BasicTextField(row.getOrElse(c) { "" }, { v -> commit(grid.copy(rows = grid.rows.mapIndexed { ri, rr -> if (ri == r) (rr + List((c + 1 - rr.size).coerceAtLeast(0)) { "" }).mapIndexed { ci, s -> if (ci == c) v.replace("\n", " ") else s } else rr })) },
                                    Modifier.fillMaxWidth(), singleLine = true, cursorBrush = SolidColor(p.accent),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.text, fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal, textAlign = when (grid.align.getOrElse(c) { 'l' }) { 'c' -> TextAlign.Center; 'r' -> TextAlign.End; else -> TextAlign.Start }))
                                if (r == 0) Box(Modifier.align(Alignment.TopEnd).size(22.dp).clickable { colMenu = c }, contentAlignment = Alignment.Center) { Text("▾", color = p.secondaryText, style = MaterialTheme.typography.labelSmall) }
                                if (r == 0) DropdownMenu(colMenu == c, { colMenu = null }) {
                                    DropdownMenuItem({ Text("Sort A to Z (small to large)") }, { colMenu = null; commit(sortGrid(grid, c, false)) })
                                    DropdownMenuItem({ Text("Sort Z to A (large to small)") }, { colMenu = null; commit(sortGrid(grid, c, true)) })
                                    DropdownMenuItem({ Text("Align left") }, { colMenu = null; commit(grid.copy(align = grid.align.toMutableList().also { while (it.size <= c) it.add('l'); it[c] = 'l' })) })
                                    DropdownMenuItem({ Text("Align centre") }, { colMenu = null; commit(grid.copy(align = grid.align.toMutableList().also { while (it.size <= c) it.add('l'); it[c] = 'c' })) })
                                    DropdownMenuItem({ Text("Align right") }, { colMenu = null; commit(grid.copy(align = grid.align.toMutableList().also { while (it.size <= c) it.add('l'); it[c] = 'r' })) })
                                    DropdownMenuItem({ Text("Insert column left") }, { colMenu = null; commit(Grid(grid.rows.map { it.toMutableList().also { x -> x.add(c, "") } }, grid.align.toMutableList().also { it.add(c, 'l') })) })
                                    DropdownMenuItem({ Text("Insert column right") }, { colMenu = null; commit(Grid(grid.rows.map { it.toMutableList().also { x -> x.add(c + 1, "") } }, grid.align.toMutableList().also { it.add(c + 1, 'l') })) })
                                    if (c > 0) DropdownMenuItem({ Text("Move column left") }, { colMenu = null; commit(Grid(grid.rows.map { it.moved(c, c - 1) }, grid.align.moved(c, c - 1))) })
                                    if (c < cols - 1) DropdownMenuItem({ Text("Move column right") }, { colMenu = null; commit(Grid(grid.rows.map { it.moved(c, c + 1) }, grid.align.moved(c, c + 1))) })
                                    if (cols > 1) DropdownMenuItem({ Text("Delete column", color = p.danger) }, { colMenu = null; commit(Grid(grid.rows.map { it.filterIndexed { i, _ -> i != c } }, grid.align.filterIndexed { i, _ -> i != c })) })
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(p.panel.copy(alpha = .6f)).padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("+ Row", Modifier.clickable { commit(grid.copy(rows = grid.rows + listOf(List(cols) { "" }))) }.padding(8.dp), color = p.accent, style = MaterialTheme.typography.labelLarge)
            Text("+ Column", Modifier.clickable { commit(Grid(grid.rows.map { it + "" }, grid.align + 'l')) }.padding(8.dp), color = p.accent, style = MaterialTheme.typography.labelLarge)
        }
    }
}
