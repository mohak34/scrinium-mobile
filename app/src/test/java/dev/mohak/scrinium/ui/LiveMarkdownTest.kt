package dev.mohak.scrinium.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveMarkdownTest {
    private val text = "# Title\nsee **bold** and [[Plan|the plan]]\n- [ ] task #todo"

    @Test
    fun hidesMarksAwayFromTheCursor() {
        val l = liveLayout(text, -1, -1)
        assertEquals("Title\nsee bold and the plan\n- [ ] task #todo", l.visible)
        assertTrue(LiveStyle(LiveKind.Heading1, 0, 5) in l.styles)
        assertTrue(LiveStyle(LiveKind.Bold, 10, 14) in l.styles)
        assertTrue(LiveStyle(LiveKind.Link, 19, 27) in l.styles)
    }

    @Test
    fun cursorLineKeepsItsMarks() {
        val second = text.indexOf("see")
        val l = liveLayout(text, second + 2, second + 2)
        assertEquals("Title\nsee **bold** and [[Plan|the plan]]\n- [ ] task #todo", l.visible)
    }

    @Test
    fun offsetMapsRoundTripOnVisibleChars() {
        val l = liveLayout(text, -1, -1)
        assertEquals(l.visible.length + 1, l.visToOrig.size)
        for (v in l.visible.indices) {
            val o = l.visToOrig[v]
            assertEquals(l.visible[v], text[o])
            assertEquals(v, l.origToVis[o])
        }
        // A hidden mark maps forward to the next visible character.
        assertEquals(l.origToVis[text.indexOf("bold")], l.origToVis[text.indexOf("**bold")])
        assertEquals(l.visible.length, l.origToVis[text.length])
    }
}
