package dev.mohak.scrinium.ui

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PrintNoteTest {
    @Test
    fun rendersBlocksAndEscapes() {
        val md = """
            ---
            title: Secret
            ---
            # Plan <x>
            See [[Ideas|my ideas]] and **bold**.
            - [x] done
            - [ ] todo
            ![shot](a.png)
            ![gone](b.png)
            ```
            <tag>
            ```
        """.trimIndent()
        val html = noteHtml("Plan", md) { if (it == "a.png") "data:image/png;base64,AA" else null }
        assertFalse("title: Secret" in html)
        assertTrue("<h1>Plan &lt;x&gt;</h1>" in html)
        assertTrue("See my ideas and <strong>bold</strong>." in html)
        assertTrue("<li>&#9745; done</li>" in html && "<li>&#9744; todo</li>" in html)
        assertTrue("src=\"data:image/png;base64,AA\"" in html)
        assertFalse("b.png" in html)
        assertTrue("<pre><code>&lt;tag&gt;</code></pre>" in html)
    }
}
