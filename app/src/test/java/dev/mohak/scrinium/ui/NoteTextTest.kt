package dev.mohak.scrinium.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class NoteTextTest {
    private val note = """
        ---
        title: "Plan"
        tags: [work, q4]
        aliases:
          - roadmap
          - "next steps"
        # comment
        ---
        # Plan
        Some words here.
        ```
        # not a heading
        ```
        ## Goals ##
    """.trimIndent()

    @Test
    fun outlineSkipsFrontmatterAndCode() {
        assertEquals(
            listOf(OutlineEntry(1, "Plan", 8), OutlineEntry(2, "Goals", 13)),
            parseOutline(note)
        )
        assertEquals(note.indexOf("## Goals"), lineStartOffset(note, 13))
    }

    @Test
    fun propertiesFlattenLists() {
        assertEquals(
            listOf(
                PropertyRow("title", "Plan"),
                PropertyRow("tags", "work, q4"),
                PropertyRow("aliases", "roadmap, next steps")
            ),
            frontmatterProperties(note)
        )
    }

    @Test
    fun wordCountIgnoresFrontmatterAndCode() {
        // "Plan", "Some", "words", "here", "Goals"
        assertEquals(5, wordCount(note))
    }
}
