package dev.mohak.scrinium.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LineDiffTest {
    @Test
    fun marksRemovedAndAddedLines() {
        val diff = lineDiff("a\nb\nc", "a\nx\nc")
        assertEquals(listOf("=a", "-b", "+x", "=c"), diff.map { (if (it.kind == DiffKind.Same) "=" else if (it.kind == DiffKind.Del) "-" else "+") + it.text })
    }

    @Test
    fun foldsLongUnchangedRuns() {
        val a = (1..20).joinToString("\n")
        val b = a.replace("\n10\n", "\nten\n")
        val folded = foldDiff(lineDiff(a, b))
        // 9 unchanged lines before the change keep 3, 10 after keep 3.
        assertEquals(listOf(6, 7), folded.filter { it.first == null }.map { it.second })
        assertEquals(3 + 1 + 1 + 3 + 2, folded.size)
    }
}
