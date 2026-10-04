package dev.mohak.scrinium.ui

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

// PDF export: the note rendered to plain HTML and handed to Android's print
// dialog, where "Save as PDF" is one of the printers. Light page, like the
// web's print view, since it is meant for paper.

private val printImageRe = Regex("""^!\[([^\]]*)]\(\s*(<[^>]+>|[^)\s]+)(?:\s+"[^"]*")?\s*\)$""")
private val printWikiRe = Regex("""\[\[([^\]|#]+)(?:#[^\]|]*)?(?:\|([^\]]+))?]]""")

private fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun inlineHtml(raw: String): String {
    var s = esc(raw)
    s = s.replace(Regex("!\\[\\[([^\\]]+)]]"), "")
    s = printWikiRe.replace(s) { m -> m.groupValues[2].ifBlank { m.groupValues[1] } }
    s = s.replace(Regex("`([^`]+)`"), "<code>$1</code>")
    s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
    s = s.replace(Regex("(?<![*\\w])\\*([^*]+)\\*(?!\\*)"), "<em>$1</em>")
    s = s.replace(Regex("~~([^~]+)~~"), "<del>$1</del>")
    s = s.replace(Regex("==([^=]+)=="), "<mark>$1</mark>")
    s = s.replace(Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)"), "<a href=\"$2\">$1</a>")
    return s
}

/**
 * Markdown to a standalone HTML page for printing. [imageSrc] maps an
 * image reference to what the page should load (a data URI for vault
 * images, which need the API token); null drops the image.
 */
fun noteHtml(title: String, markdown: String, imageSrc: (String) -> String?): String {
    val body = StringBuilder()
    val lines = markdown.split('\n')
    var i = 0
    if (lines.firstOrNull()?.trim() == "---") {
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." }
        if (end != null) i = end + 1
    }
    var list: String? = null
    fun closeList() {
        list?.let { body.append("</$it>\n") }
        list = null
    }
    val para = mutableListOf<String>()
    fun flushPara() {
        if (para.isNotEmpty()) body.append("<p>").append(para.joinToString("<br>") { inlineHtml(it) }).append("</p>\n")
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        val heading = Regex("^(#{1,6})\\s+(.+?)\\s*#*$").matchEntire(t)
        val bullet = Regex("^[-*+]\\s+(\\[[ xX]]\\s+)?(.*)$").matchEntire(t)
        val ordered = Regex("^\\d+[.)]\\s+(.*)$").matchEntire(t)
        val image = printImageRe.matchEntire(t)
        when {
            t.startsWith("```") || t.startsWith("~~~") -> {
                flushPara(); closeList()
                val fence = t.take(3)
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) code += lines[i++]
                body.append("<pre><code>").append(esc(code.joinToString("\n"))).append("</code></pre>\n")
            }
            heading != null -> {
                flushPara(); closeList()
                val n = heading.groupValues[1].length
                body.append("<h$n>").append(inlineHtml(heading.groupValues[2])).append("</h$n>\n")
            }
            image != null -> {
                flushPara(); closeList()
                imageSrc(image.groupValues[2].removeSurrounding("<", ">"))?.let {
                    body.append("<img src=\"").append(esc(it)).append("\" alt=\"").append(esc(image.groupValues[1])).append("\">\n")
                }
            }
            bullet != null || ordered != null -> {
                flushPara()
                val kind = if (bullet != null) "ul" else "ol"
                if (list != kind) { closeList(); body.append("<$kind>\n"); list = kind }
                val box = bullet?.groupValues?.get(1)?.trim().orEmpty()
                val mark = when {
                    box.isEmpty() -> ""
                    box == "[ ]" -> "&#9744; "
                    else -> "&#9745; "
                }
                body.append("<li>").append(mark).append(inlineHtml(bullet?.groupValues?.get(2) ?: ordered!!.groupValues[1])).append("</li>\n")
            }
            t.startsWith(">") -> {
                flushPara(); closeList()
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) quote += lines[i++].trim().removePrefix(">").trim()
                i--
                val callout = Regex("^\\[!([\\w-]+)][+-]?\\s*(.*)$").matchEntire(quote.first())
                body.append("<blockquote>")
                if (callout != null) {
                    body.append("<strong>").append(esc(callout.groupValues[2].ifBlank { callout.groupValues[1] })).append("</strong><br>")
                    quote.removeAt(0)
                }
                body.append(quote.joinToString("<br>") { inlineHtml(it) }).append("</blockquote>\n")
            }
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> {
                flushPara(); closeList()
                body.append("<hr>\n")
            }
            t.isEmpty() -> {
                flushPara(); closeList()
            }
            else -> {
                closeList()
                para += t
            }
        }
        i++
    }
    flushPara(); closeList()
    return """<!doctype html><html><head><meta charset="utf-8"><title>${esc(title)}</title><style>
body{font-family:sans-serif;font-size:11pt;line-height:1.5;color:#111;margin:0}
h1{font-size:20pt}h2{font-size:16pt}h3{font-size:13pt}
pre{background:#f3f3f3;padding:8px;white-space:pre-wrap;font-size:9pt}
code{font-family:monospace}
blockquote{border-left:3px solid #bbb;margin:0;padding-left:12px;color:#444}
img{max-width:100%}
</style></head><body>
$body</body></html>"""
}

// Held until the print job has its adapter, or the WebView can be
// collected mid-load (the Android printing guide does the same).
private var printView: WebView? = null

/** Opens the system print dialog for [html]. Needs an activity context. */
fun printHtml(context: Context, jobName: String, html: String) {
    val web = WebView(context)
    printView = web
    web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            if (printView !== view) return
            val manager = context.getSystemService(PrintManager::class.java)
            manager.print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
            printView = null
        }
    }
    web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}
