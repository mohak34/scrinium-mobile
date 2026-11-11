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

    @Test
    fun decodesTheDestinationOnly() {
        assertEquals(ImageSource.Vault("my notes/my img.png"), resolveImage("<my img.png>", "my notes/a.md"))
        assertEquals(ImageSource.Vault("a/my img.png"), resolveImage("my%20img.png?v=2#x", "a/b.md"))
        assertEquals(ImageSource.Vault("a+b.png"), resolveImage("a+b.png", "n.md"))
        assertEquals(ImageSource.Vault("x/100%.png"), resolveImage("/api/assets/x/100%.png", "n.md"))
    }

    @Test
    fun uploadsGoToTheProjectAndKeepSpacesInOneLink() {
        assertEquals("work/attachments", attachmentFolder("work/plan/a.md", listOf("work", "home")))
        assertEquals("attachments", attachmentFolder("workshop/a.md", listOf("work")))
        assertEquals("![my pic](<attachments/my pic.png>)", attachmentMarkdown("a.md", "attachments/my pic.png"))
    }
}
