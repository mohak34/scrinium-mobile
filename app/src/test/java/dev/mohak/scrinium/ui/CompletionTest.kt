package dev.mohak.scrinium.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionTest {
    private val paths = listOf("Plan.md", "work/Planning.md", "Ideas.md")

    @Test
    fun suggestsNotesAfterBrackets() {
        val text = "see [[pla"
        val s = completions(text, text.length, "Note.md", paths, emptyList())
        assertEquals(listOf("Plan", "Planning"), s.map { it.label })
        assertEquals(Suggestion("Planning", "work/Planning.md", 6, "Planning]]"), s[1])
        assertEquals(emptyList(), completions("[[Plan]] x", 10, "Note.md", paths, emptyList()))
    }

    @Test
    fun insertsAPathWhenTheNameWouldOpenAnotherNote() {
        val two = listOf("work/Plan.md", "personal/Plan.md", "personal/Source.md")
        val text = "[[Pla"
        for (source in listOf("Root.md", "personal/Source.md")) {
            for (s in completions(text, text.length, source, two, emptyList())) {
                val target = s.insert.removeSuffix("]]")
                assertEquals(s.detail, resolveWikilink(target, source, two))
            }
        }
        val fromPersonal = completions(text, text.length, "personal/Source.md", two, emptyList())
        assertEquals(listOf("Plan]]", "work/Plan]]"), fromPersonal.map { it.insert }.sorted())
    }

    @Test
    fun suggestsTagsAfterHash() {
        val tags = vaultTags(listOf("#work #work/q4 #life", "# Heading\n#work"))
        assertEquals(listOf("work", "life", "work/q4"), tags)
        val text = "todo #wo"
        assertEquals(listOf("#work", "#work/q4"), completions(text, text.length, "Note.md", paths, tags).map { it.label })
        assertEquals(emptyList(), completions("a#wo", 4, "Note.md", paths, tags))
    }
}
