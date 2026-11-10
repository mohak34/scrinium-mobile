package dev.mohak.scrinium.ui

enum class DiffKind { Same, Add, Del }

data class DiffLine(val kind: DiffKind, val text: String)

/**
 * Line diff of [a] into [b], a port of the web's lineDiff: an LCS over the
 * lines between the common head and tail. A middle too big for the table
 * shows as one removed block and one added block.
 */
fun lineDiff(a: String, b: String, maxCells: Long = 4_000_000): List<DiffLine> {
    val x = a.split('\n')
    val y = b.split('\n')
    var head = 0
    while (head < x.size && head < y.size && x[head] == y[head]) head++
    var tail = 0
    while (tail < x.size - head && tail < y.size - head && x[x.size - 1 - tail] == y[y.size - 1 - tail]) tail++
    val xs = x.subList(head, x.size - tail)
    val ys = y.subList(head, y.size - tail)
    val out = x.subList(0, head).mapTo(ArrayList()) { DiffLine(DiffKind.Same, it) }
    val n = xs.size
    val m = ys.size
    if (n.toLong() * m > maxCells) {
        xs.mapTo(out) { DiffLine(DiffKind.Del, it) }
        ys.mapTo(out) { DiffLine(DiffKind.Add, it) }
    } else {
        // lcs[i * (m + 1) + j]: LCS length of xs[i..] and ys[j..].
        val w = m + 1
        val lcs = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i * w + j] = if (xs[i] == ys[j]) lcs[(i + 1) * w + j + 1] + 1 else maxOf(lcs[(i + 1) * w + j], lcs[i * w + j + 1])
        }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && xs[i] == ys[j] -> { out += DiffLine(DiffKind.Same, xs[i++]); j++ }
                i < n && (j == m || lcs[(i + 1) * w + j] >= lcs[i * w + j + 1]) -> out += DiffLine(DiffKind.Del, xs[i++])
                else -> out += DiffLine(DiffKind.Add, ys[j++])
            }
        }
    }
    x.subList(x.size - tail, x.size).mapTo(out) { DiffLine(DiffKind.Same, it) }
    return out
}

/** Diff lines with unchanged runs folded to [context] lines each side; null marks a fold of that many lines. */
fun foldDiff(diff: List<DiffLine>, context: Int = 3): List<Pair<DiffLine?, Int>> {
    val out = ArrayList<Pair<DiffLine?, Int>>()
    var i = 0
    while (i < diff.size) {
        if (diff[i].kind != DiffKind.Same) { out += diff[i++] to 0; continue }
        var j = i
        while (j < diff.size && diff[j].kind == DiffKind.Same) j++
        val keepHead = if (i == 0) 0 else context
        val keepTail = if (j == diff.size) 0 else context
        if (j - i > keepHead + keepTail + 1) {
            diff.subList(i, i + keepHead).forEach { out += it to 0 }
            out += null to (j - i - keepHead - keepTail)
            diff.subList(j - keepTail, j).forEach { out += it to 0 }
        } else diff.subList(i, j).forEach { out += it to 0 }
        i = j
    }
    return out
}
