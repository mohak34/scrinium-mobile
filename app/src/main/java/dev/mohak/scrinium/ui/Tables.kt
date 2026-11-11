package dev.mohak.scrinium.ui

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

// GFM pipe tables, shared by the reading view and PDF export. A table is a
// header row, a delimiter row (`| --- | :-: |`), then body rows until a
// blank line or the start of another block. The web may put a settings line
// (`<!-- scrinium-table: {json} -->`, its tableMeta.ts) right above it.

enum class ColumnAlign { Start, Center, End }

/** [rows] starts with the header; every row has one cell per column. */
class MdTable(val align: List<ColumnAlign>, val rows: List<List<String>>)

private val delimiterCell = Regex("""^\s*(:?)-+(:?)\s*$""")
private val blockStart = Regex("""^\s*(#{1,6}\s|```|~~~|>|\$\$|[-*+]\s|\d+[.)]\s|(-{3,}|\*{3,}|_{3,})\s*$)""")

/**
 * The table starting at [start] and the index after it, or null if none
 * starts there. A settings line at [start] is consumed, and its sort and
 * filters decide which body rows show and in what order.
 */
fun parseTable(lines: List<String>, start: Int, today: String? = null): Pair<MdTable, Int>? {
    val meta = lines.getOrNull(start)?.let(::parseTableMeta)
    if (meta != null) {
        val (table, end) = parseTable(lines, start + 1) ?: return null
        val body = table.rows.drop(1)
        return MdTable(table.align, listOf(table.rows[0]) + tableView(body, meta, today ?: LocalDate.now().toString()).map { body[it] }) to end
    }
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

class ColFilter(val hide: List<String>, val date: String?, val filled: Boolean, val text: String?)

/** The web's TableMeta, only what reading needs: column types, status order, sort, filters. */
class TableMeta(
    val types: Map<Int, String>,
    val options: Map<Int, List<String>>,
    val sort: Pair<Int, Int>?,
    val filter: Map<Int, ColFilter>
)

private val metaLine = Regex("""^<!-- scrinium-table: (\{.*\}) -->$""")

/** The settings on a line, null when it is not a settings comment. */
fun parseTableMeta(line: String): TableMeta? {
    val json = metaLine.matchEntire(line.trim())?.groupValues?.get(1) ?: return null
    val raw = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    if ((raw["v"] as? JsonPrimitive)?.intOrNull != 1) return null
    fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun JsonObject.strings(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    fun JsonObject.cols() = entries.mapNotNull { (k, v) -> k.toIntOrNull()?.let { i -> (v as? JsonObject)?.let { i to it } } }
    val cols = (raw["cols"] as? JsonObject)?.cols().orEmpty()
    val sort = (raw["sort"] as? JsonObject)?.let { s ->
        val col = (s["col"] as? JsonPrimitive)?.intOrNull
        val dir = (s["dir"] as? JsonPrimitive)?.intOrNull
        if (col != null && (dir == 1 || dir == -1)) col to dir else null
    }
    return TableMeta(
        types = cols.mapNotNull { (i, c) -> c.str("type")?.let { i to it } }.toMap(),
        options = cols.associate { (i, c) -> i to c.strings("options") },
        sort = sort,
        filter = (raw["filter"] as? JsonObject)?.cols().orEmpty().associate { (i, f) ->
            i to ColFilter(
                hide = f.strings("hide"),
                date = f.str("date"),
                filled = (f["filled"] as? JsonPrimitive)?.booleanOrNull == true,
                text = f.str("text")?.ifEmpty { null }
            )
        }
    )
}

private val isoDate = Regex("""^\d{4}-\d{2}-\d{2}$""")

private fun inRange(v: String, range: String, today: String): Boolean {
    if (!isoDate.matches(v)) return false
    if (range == "overdue") return v < today
    if (v < today) return false
    val days = when (range) {
        "week" -> 7L
        "month" -> 30L
        else -> return range == "upcoming"
    }
    return v <= LocalDate.parse(today).plusDays(days).toString()
}

private fun asNumber(v: String): Double? = if (v.isBlank()) null else v.replace(Regex("""[,$%\s]"""), "").toDoubleOrNull()

// localeCompare(numeric, base): case-insensitive, digit runs compare as numbers.
private fun naturalCompare(a: String, b: String): Int {
    val chunk = Regex("""\d+|\D+""")
    val xs = chunk.findAll(a.lowercase()).map { it.value }.toList()
    val ys = chunk.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(xs.size, ys.size)) {
        val x = xs[i]
        val y = ys[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
        if (c != 0) return c
    }
    return xs.size - ys.size
}

/** Body rows (indexes into [body]) in display order, as the web's tableView. */
fun tableView(body: List<List<String>>, meta: TableMeta, today: String): List<Int> {
    var rows = body.indices.toList()
    for ((col, f) in meta.filter) {
        val text = f.text?.lowercase()
        rows = rows.filter { i ->
            val v = body[i].getOrElse(col) { "" }
            !(f.filled && v.isEmpty()) && v !in f.hide &&
                (f.date == null || inRange(v, f.date, today)) &&
                (text == null || text in v.lowercase())
        }
    }
    val (col, dir) = meta.sort ?: return rows
    val type = meta.types[col] ?: "text"
    val values = body.map { it.getOrElse(col) { "" } }
    val options = (meta.options[col].orEmpty() + values.filter { it.isNotEmpty() }).distinct()
    // Blank or unreadable values sort last either way.
    val keys: List<Comparable<*>?> = values.map { v ->
        when {
            v.isEmpty() -> null
            type == "date" || type == "deadline" -> v.takeIf { isoDate.matches(it) }
            type == "number" -> asNumber(v)
            type == "status" -> options.indexOf(v)
            else -> v
        }
    }
    return rows.sortedWith { a, b ->
        val x = keys[a]
        val y = keys[b]
        when {
            x == null || y == null -> if (x == y) a - b else if (x == null) 1 else -1
            else -> {
                val c = if (x is Number && y is Number) x.toDouble().compareTo(y.toDouble()) else naturalCompare(x.toString(), y.toString())
                (dir * c).takeIf { it != 0 } ?: (a - b)
            }
        }
    }
}
