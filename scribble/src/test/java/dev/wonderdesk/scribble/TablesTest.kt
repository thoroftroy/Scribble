package dev.wonderdesk.scribble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TablesTest {
    private val note = "Intro\n\n| Name | Qty |\n| --- | ---: |\n| Pear | 10 |\n| Apple | 9 |\n\nOutro"

    @Test fun findsTheTableAndKeepsEverythingElseAsText() {
        val s = splitSegments(note)
        assertEquals(listOf(false, true, false), s.map { it.table })
        assertEquals(note, s.joinToString("") { note.substring(it.start, it.end) })
        assertTrue(note.substring(s[1].start, s[1].end).startsWith("| Name"))
    }

    @Test fun noTableMeansOneTextPiece() {
        val s = splitSegments("just text | with a pipe\nand more")
        assertEquals(1, s.size); assertEquals(false, s[0].table)
    }

    @Test fun tableAtTheVeryEndAndStart() {
        val t = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        val s = splitSegments(t)
        assertEquals(true, s.first().table)
        assertEquals(t, s.joinToString("") { t.substring(it.start, it.end) })
    }

    @Test fun roundTripKeepsCellsAndAlignment() {
        val g = parseGrid("| Name | Qty |\n| :--- | ---: |\n| Pear | 10 |\n| Apple | 9 |")
        assertEquals(listOf('l', 'r'), g.align)
        assertEquals("| Name | Qty |\n| --- | ---: |\n| Pear | 10 |\n| Apple | 9 |", gridToMarkdown(g))
    }

    @Test fun pipesInsideCellsSurvive() {
        val g = parseGrid("| a | b |\n| --- | --- |\n| x \\| y | z |")
        assertEquals("x | y", g.rows[1][0])
        assertTrue(gridToMarkdown(g).contains("x \\| y"))
    }

    @Test fun sortsNumbersAsNumbersAndKeepsTheHeader() {
        val g = parseGrid("| n | v |\n| --- | --- |\n| a | 10 |\n| b | 9 |\n| c | 100 |")
        assertEquals(listOf("b", "a", "c"), sortGrid(g, 1, false).rows.drop(1).map { it[0] })
        assertEquals(listOf("c", "a", "b"), sortGrid(g, 1, true).rows.drop(1).map { it[0] })
        assertEquals(listOf("n", "v"), sortGrid(g, 1, true).rows[0])
    }

    @Test fun movingRowsAndColumns() {
        assertEquals(listOf(2, 1, 3), listOf(1, 2, 3).moved(1, 0))
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).moved(0, 5))
    }
}

class TablesEditLoopTest {
    @Test fun editingACellDoesNotGrowTheTable() {
        var text = "Plan\n| Column 1 | Column 2 |\n| --- | --- |\n|  |  |\n|  |  |\n"
        repeat(4) { n ->
            val sg = splitSegments(text).first { it.table }
            val raw = text.substring(sg.start, sg.end)
            val g = parseGrid(raw.trimEnd('\n'))
            val g2 = g.copy(rows = g.rows.mapIndexed { ri, r -> if (ri == 1) r.mapIndexed { ci, s -> if (ci == 0) s + "x" else s } else r })
            val keep = raw.length - raw.trimEnd('\n').length
            text = text.replaceRange(sg.start, sg.end, gridToMarkdown(g2) + "\n".repeat(keep))
            assertEquals("round $n", 3, parseGrid(text.substring(splitSegments(text).first { it.table }.let { it.start }, splitSegments(text).first { it.table }.end).trimEnd('\n')).rows.size)
        }
    }
}
