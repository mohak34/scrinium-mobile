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

class TableMetaTest {
    @Test
    fun settingsLineSortsAndFiltersRows() {
        val lines = listOf(
            """<!-- scrinium-table: {"v":1,"cols":{"1":{"type":"status","options":["todo","done"]}},"sort":{"col":1,"dir":1},"filter":{"0":{"text":"a"}}} -->""",
            "| name | state |",
            "|---|---|",
            "| apple | done |",
            "| bob | todo |",
            "| banana | todo |",
            "| avocado | |",
        )
        val (table, end) = parseTable(lines, 0, today = "2026-11-10")!!
        assertEquals(listOf("name", "banana", "apple", "avocado"), table.rows.map { it[0] })
        assertEquals(7, end)
    }

    @Test
    fun numbersAndDatesSortByValue() {
        val meta = parseTableMeta("""<!-- scrinium-table: {"v":1,"cols":{"0":{"type":"number"}},"sort":{"col":0,"dir":-1}} -->""")!!
        assertEquals(listOf(1, 2, 0), tableView(listOf(listOf("9"), listOf("$1,200"), listOf("10")), meta, "2026-11-10"))
        val due = parseTableMeta("""<!-- scrinium-table: {"v":1,"filter":{"0":{"date":"week"}}} -->""")!!
        val rows = listOf(listOf("2026-11-09"), listOf("2026-11-12"), listOf("2026-12-30"))
        assertEquals(listOf(1), tableView(rows, due, "2026-11-10"))
        assertNull(parseTableMeta("<!-- scrinium-table: {\"v\":2} -->"))
    }
}
