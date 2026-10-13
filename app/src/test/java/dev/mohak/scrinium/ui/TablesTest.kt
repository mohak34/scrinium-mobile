package dev.mohak.scrinium.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TablesTest {
    @Test
    fun parsesAlignmentPipesAndRaggedRows() {
        val lines = listOf(
            "intro",
            "| a | b | c |",
            "|:--|:-:|--:|",
            "| `x|y` | a\\|b |",
            "1 | 2 | 3 | 4",
            "",
            "after"
        )
        val (table, end) = parseTable(lines, 1)!!
        assertEquals(listOf(ColumnAlign.Start, ColumnAlign.Center, ColumnAlign.End), table.align)
        assertEquals(listOf("a", "b", "c"), table.rows[0])
        assertEquals(listOf("`x|y`", "a|b", ""), table.rows[1])
        assertEquals(listOf("1", "2", "3"), table.rows[2])
        assertEquals(5, end)
    }

    @Test
    fun keepsWikilinkAliasesAndStopsAtBlocks() {
        val lines = listOf("| [[Note|Alias]] | b |", "|---|---|", "only", "# Next | Section")
        val (table, end) = parseTable(lines, 0)!!
        assertEquals(listOf("[[Note|Alias]]", "b"), table.rows[0])
        assertEquals(listOf("only", ""), table.rows[1])
        assertEquals(3, end)
    }

    @Test
    fun rejectsNonTables() {
        assertNull(parseTable(listOf("a | b", "not a delimiter"), 0))
        assertNull(parseTable(listOf("a | b | c", "--- | ---"), 0))
        assertNull(parseTable(listOf("---", "a | b"), 0))
    }
}
