package dev.mohak.scrinium.ui

// GFM pipe tables, shared by the reading view and PDF export. A table is a
// header row, a delimiter row (`| --- | :-: |`), then body rows until a
// blank line or the start of another block.

enum class ColumnAlign { Start, Center, End }

/** [rows] starts with the header; every row has one cell per column. */
class MdTable(val align: List<ColumnAlign>, val rows: List<List<String>>)

private val delimiterCell = Regex("""^\s*(:?)-+(:?)\s*$""")
private val blockStart = Regex("""^\s*(#{1,6}\s|```|~~~|>|\$\$|[-*+]\s|\d+[.)]\s|(-{3,}|\*{3,}|_{3,})\s*$)""")

/** The table starting at [start] and the index after it, or null if none starts there. */
fun parseTable(lines: List<String>, start: Int): Pair<MdTable, Int>? {
    val header = lines.getOrNull(start)?.takeIf { '|' in it } ?: return null
    val delimiter = lines.getOrNull(start + 1)?.takeIf { '|' in it } ?: return null
    val specs = splitCells(delimiter)
    if (specs.isEmpty()) return null
    val align = specs.map { cell ->
        val m = delimiterCell.matchEntire(cell) ?: return null
        val left = m.groupValues[1].isNotEmpty()
        val right = m.groupValues[2].isNotEmpty()
        when {
            left && right -> ColumnAlign.Center
            right -> ColumnAlign.End
            else -> ColumnAlign.Start
        }
    }
    val head = splitCells(header)
    if (head.size != align.size) return null
    val rows = mutableListOf(head)
    var i = start + 2
    while (i < lines.size && lines[i].isNotBlank() && !blockStart.containsMatchIn(lines[i])) {
        val cells = splitCells(lines[i])
        rows += List(align.size) { cells.getOrElse(it) { "" } }
        i++
    }
    return MdTable(align, rows) to i
}

// Cells of one row: outer pipes dropped, split on pipes that are not
// escaped (`\|`) and not inside inline code or a `[[link|alias]]`.
private fun splitCells(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.drop(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var code = false
    var link = false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && s.getOrNull(i + 1) == '|' -> {
                cur.append('|')
                i++
            }
            !code && !link && s.startsWith("[[", i) && s.indexOf("]]", i + 2) >= 0 -> {
                link = true
                cur.append("[[")
                i++
            }
            !code && link && s.startsWith("]]", i) -> {
                link = false
                cur.append("]]")
                i++
            }
            c == '`' -> {
                code = !code
                cur.append(c)
            }
            c == '|' && !code && !link -> {
                cells += cur.toString().trim()
                cur.clear()
            }
            else -> cur.append(c)
        }
        i++
    }
    cells += cur.toString().trim()
    return cells
}
