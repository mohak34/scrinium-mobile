package dev.mohak.scrinium.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImagesTest {
    @Test
    fun resolvesAgainstTheNoteFolder() {
        assertEquals(ImageSource.Vault("notes/pic.png"), resolveImage("pic.png", "notes/a.md"))
        assertEquals(ImageSource.Vault("attachments/x.png"), resolveImage("../attachments/x.png", "notes/a.md"))
        assertEquals(ImageSource.Vault("attachments/x.png"), resolveImage("attachments/x.png", "a.md"))
        assertEquals(ImageSource.External("https://e.com/i.png"), resolveImage("https://e.com/i.png", "a.md"))
        assertNull(resolveImage("data:image/png;base64,AAAA", "a.md"))
    }

    @Test
    fun insertedPathIsRelativeToTheNote() {
        assertEquals("../attachments/x.png", noteRelative("notes/a.md", "attachments/x.png"))
        assertEquals("attachments/x.png", noteRelative("a.md", "attachments/x.png"))
        assertEquals("x.png", noteRelative("attachments/a.md", "attachments/x.png"))
    }
}
