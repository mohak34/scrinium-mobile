package dev.mohak.scrinium.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionTest {
    private val paths = listOf("Plan.md", "work/Planning.md", "Ideas.md")

    @Test
    fun suggestsNotesAfterBrackets() {
        val text = "see [[pla"
        val s = completions(text, text.length, paths, emptyList())
        assertEquals(listOf("Plan", "Planning"), s.map { it.label })
        assertEquals(Suggestion("Planning", "work/Planning.md", 6, "Planning]]"), s[1])
        assertEquals(emptyList(), completions("[[Plan]] x", 10, paths, emptyList()))
    }

    @Test
    fun suggestsTagsAfterHash() {
        val tags = vaultTags(listOf("#work #work/q4 #life", "# Heading\n#work"))
        assertEquals(listOf("work", "life", "work/q4"), tags)
        val text = "todo #wo"
        assertEquals(listOf("#work", "#work/q4"), completions(text, text.length, paths, tags).map { it.label })
        assertEquals(emptyList(), completions("a#wo", 4, paths, tags))
    }
}
