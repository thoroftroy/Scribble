package dev.wonderdesk.scribble

import org.junit.Assert.*
import org.junit.Test

class PasteTableTest {
    @Test fun tab_separated_rows_become_a_markdown_table() {
        val md = pastedTableToMarkdown("Name\tQty\nApples\t3\nPears\t5", null)!!
        assertEquals("| Name | Qty |\n| --- | --- |\n| Apples | 3 |\n| Pears | 5 |", md)
    }

    @Test fun obsidian_rendered_html_table() {
        val html = "<table><thead><tr><th>Col A</th><th>Col B</th></tr></thead><tbody><tr><td>1</td><td>x &amp; y</td></tr><tr><td>2</td><td>a<br>b</td></tr></tbody></table>"
        val md = pastedTableToMarkdown("Col A\tCol B\n1\tx & y\n2\ta b", html)!!
        assertEquals("| Col A | Col B |\n| --- | --- |\n| 1 | x & y |\n| 2 | a<br>b |", md)
    }

    @Test fun pipes_without_the_outer_pipes_get_them() {
        val md = pastedTableToMarkdown("Name | Qty\n--- | ---\nApples | 3", null)!!
        assertEquals("| Name | Qty |\n| --- | --- |\n| Apples | 3 |", md)
    }

    @Test fun ragged_rows_are_padded_and_pipes_in_cells_are_escaped() {
        val md = pastedTableToMarkdown("A\tB\tC\n1\t2", null)!!
        assertEquals("| A | B | C |\n| --- | --- | --- |\n| 1 | 2 |  |", md)
        val esc = pastedTableToMarkdown("A\tB\nx|y\tz", null)!!
        assertTrue(esc, esc.contains("x\\|y"))
    }

    @Test fun ordinary_text_is_left_alone() {
        assertNull(pastedTableToMarkdown("just a sentence\nwith two lines", null))
        assertNull(pastedTableToMarkdown("one line with\ta tab", null))
    }

    @Test fun word_and_line_moves() {
        val t = "hello brave world\nsecond line here"
        assertEquals(5, wordRight(t, 0))
        assertEquals(11, wordRight(t, 6))
        assertEquals(17, wordRight(t, 12))
        assertEquals(0, wordLeft(t, 5))
        assertEquals(18, lineStart(t, 25))
        assertEquals(17, lineEnd(t, 3))
        // the same column one line down, clamped
        assertEquals(18 + 3, lineUpDown(t, 3, true))
        assertEquals(3, lineUpDown(t, 21, false))
        assertEquals(t.length, lineUpDown(t, 30, true))
    }
}
