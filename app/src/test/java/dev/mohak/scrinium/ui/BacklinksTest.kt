package dev.mohak.scrinium.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BacklinksTest {
    private val paths = listOf("Ideas.md", "deep/Ideas.md", "projects/Plan.md", "Journal.md")

    @Test
    fun resolvesExactPathThenShortestStem() {
        assertEquals("deep/Ideas.md", resolveWikilink("deep/Ideas", paths))
        assertEquals("Ideas.md", resolveWikilink("ideas", paths))
        assertEquals("projects/Plan.md", resolveWikilink("Plan.md", paths))
        assertNull(resolveWikilink("Missing", paths))
    }

    @Test
    fun findsLinkedAndUnlinkedMentions() {
        val notes = listOf(
            "projects/Plan.md" to "# Plan\nsee [[Plan]] itself",
            "Journal.md" to "today\nread [[plan|the plan]] and [[Plan#Goals]]",
            "Ideas.md" to "a plan without brackets",
            "deep/Ideas.md" to "![[Plan]] is an embed, not a link"
        )
        val result = findBacklinks("projects/Plan.md", notes)
        assertEquals(listOf(Backlink("Journal.md", "read the plan and Plan")), result.linked)
        assertEquals(listOf("Ideas.md", "deep/Ideas.md"), result.unlinked.map { it.path })
    }

    @Test
    fun linksFirstBareMentionKeepingCase() {
        assertEquals(
            "[[Plan]] here, [[Plan]] there\nPlan again",
            linkFirstMention("[[Plan]] here, Plan there\nPlan again", "projects/Plan.md")
        )
        assertNull(linkFirstMention("only [[Plan]]", "projects/Plan.md"))
    }
}
