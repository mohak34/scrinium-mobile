package dev.mohak.scrinium.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

class EditTextTest {
    private fun at(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))

    @Test
    fun wrapPutsCursorBetweenMarks() {
        val v = wrapSelection(at("ab", 1), "**")
        assertEquals("a****b", v.text)
        assertEquals(TextRange(3), v.selection)
    }

    @Test
    fun wrapKeepsSelectionInside() {
        val v = wrapSelection(TextFieldValue("say hi", TextRange(4, 6)), "[[", "]]")
        assertEquals("say [[hi]]", v.text)
        assertEquals(TextRange(6, 8), v.selection)
    }

    @Test
    fun checkboxAddsReplacesAndRemoves() {
        val added = toggleLinePrefix(at("one\n  two", 7), "- [ ] ")
        assertEquals("one\n  - [ ] two", added.text)
        assertEquals(13, added.selection.start)
        assertEquals("- [ ] x", toggleLinePrefix(at("- x", 3), "- [ ] ").text)
        val removed = toggleLinePrefix(at("- [x] done", 10), "- [ ] ")
        assertEquals("done", removed.text)
        assertEquals(4, removed.selection.start)
    }

    @Test
    fun bulletTogglesOff() {
        assertEquals("x", toggleLinePrefix(at("- x", 3), "- ").text)
        assertEquals("- x", toggleLinePrefix(at("1. x", 4), "- ").text)
    }
}
