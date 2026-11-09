package dev.mohak.scrinium.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitlesTest {
    private val multiline = "---\ntitle: Long\n  name here\ntags: a\n---\n# Heading"

    @Test
    fun onlyTheTopLevelTitleKeyCounts() {
        assertEquals("Real", effectiveTitle("---\nauthor:\n  title: Nested\n---\n# Real", "stem"))
        assertEquals("Long name here", effectiveTitle(multiline, "stem"))
        assertEquals("Quoted: yes", effectiveTitle("---\ntitle: \"Quoted: yes\"\n---\n", "stem"))
    }

    @Test
    fun headingsInsideCodeFencesAreNotTitles() {
        assertEquals("Yes", effectiveTitle("```\n# not a title\n```\n# Yes", "stem"))
        assertEquals("B", noteTitle("a/B.MD", "~~~\n# hidden\n~~~"))
    }

    @Test
    fun rewritingReplacesTheWholeValue() {
        assertEquals("---\ntitle: New\ntags: a\n---\n# Heading", setEffectiveTitle(multiline, "New"))
        assertEquals("---\ntitle: \"true\"\n---", setEffectiveTitle("---\ntitle: old\n---", "true"))
        assertEquals("```\n# code\n```\n# New", setEffectiveTitle("```\n# code\n```\n# Old", "New"))
        assertNull(setEffectiveTitle("no title here", "New"))
    }

    @Test
    fun renamesKeepCustomTitles() {
        assertEquals("# New", retitle("# Old", "Old.md", "New.md"))
        assertEquals("# My own title", retitle("# My own title", "Old.md", "New.md"))
        assertEquals("plain text", retitle("plain text", "Old.md", "New.md"))
    }
}
