package dev.mohak.scrinium.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BacklinksTest {
    private val paths = listOf("Ideas.md", "deep/Ideas.md", "projects/Plan.md", "Journal.md")

    @Test
    fun resolvesVaultPathThenSourceFolderThenUniqueStem() {
        assertEquals("deep/Ideas.md", resolveWikilink("deep/Ideas", "Journal.md", paths))
        assertEquals("Ideas.md", resolveWikilink("ideas", "deep/Other.md", paths))
        assertEquals("projects/Plan.md", resolveWikilink("Plan.md", "Journal.md", paths))
        assertEquals("projects/Plan.md", resolveWikilink("Plan#Goals", "Journal.md", paths))
        assertNull(resolveWikilink("Missing", "Journal.md", paths))
    }

    @Test
    fun sameNameInTwoFoldersResolvesFromTheSourceFolderOnly() {
        val two = listOf("work/Plan.md", "personal/Plan.md", "personal/Source.md", "Root.md")
        assertEquals("personal/Plan.md", resolveWikilink("Plan", "personal/Source.md", two))
        assertEquals("work/Plan.md", resolveWikilink("work/Plan", "personal/Source.md", two))
        assertNull(resolveWikilink("Plan", "Root.md", two))
    }

    @Test
    fun backlinksFollowWhereTheLinkOpens() {
        val notes = listOf(
            "work/Plan.md" to "# Plan",
            "personal/Plan.md" to "# Plan",
            "personal/Source.md" to "See [[Plan]]",
            "Root.md" to "ambiguous [[Plan]], explicit [[work/Plan]]"
        )
        assertEquals(listOf("Root.md"), findBacklinks("work/Plan.md", notes).linked.map { it.path })
        assertEquals(listOf("personal/Source.md"), findBacklinks("personal/Plan.md", notes).linked.map { it.path })
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
