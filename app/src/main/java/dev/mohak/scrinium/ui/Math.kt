package dev.mohak.scrinium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import io.ratex.DisplayList
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Math is typeset natively by RaTeX (a Rust port of KaTeX), no WebView.
// Formulas are drawn at KaTeX's default 1.21em of the surrounding text,
// matching the web app.
private const val MATH_SCALE = 1.21f

data class Formula(val latex: String, val display: Boolean)

// Parses a note's formulas off the main thread. A formula missing from the
// map is still parsing or is invalid LaTeX; callers show its source instead.
@Composable
fun rememberMath(formulas: Set<Formula>): Map<Formula, DisplayList> {
    val context = LocalContext.current.applicationContext
    val parsed by produceState(emptyMap(), formulas) {
        if (formulas.isEmpty()) return@produceState
        value = withContext(Dispatchers.Default) {
            RaTeXFontLoader.ensureLoaded(context)
            formulas.mapNotNull { f ->
                runCatching { f to RaTeXEngine.parseBlocking(f.latex, f.display, Sc.text.toArgb()) }.getOrNull()
            }.toMap()
        }
    }
    return parsed
}

private fun renderer(list: DisplayList, fontSize: TextUnit, density: Density) =
    RaTeXRenderer(list, with(density) { fontSize.toPx() } * MATH_SCALE) { RaTeXFontLoader.getTypeface(it) }

private fun DrawScope.draw(r: RaTeXRenderer) = drawIntoCanvas { r.draw(it.nativeCanvas) }

@Composable
fun MathFormula(list: DisplayList, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val r = remember(list, fontSize, density) { renderer(list, fontSize, density) }
    with(density) {
        Canvas(modifier.size(r.widthPx.toDp(), r.totalHeightPx.toDp())) { draw(r) }
    }
}

// Inline formulas keyed by LaTeX source, for appendInlineContent ids. The
// placeholder ends at the text baseline and the formula's depth (fraction
// denominators, descenders) draws below it, so baselines line up.
@Composable
fun inlineMath(math: Map<Formula, DisplayList>, fontSize: TextUnit): Map<String, InlineTextContent> {
    val density = LocalDensity.current
    return remember(math, fontSize, density) {
        math.filterKeys { !it.display }.entries.associate { (formula, list) ->
            val r = renderer(list, fontSize, density)
            val placeholder = with(density) {
                Placeholder(r.widthPx.toSp(), r.heightPx.toSp(), PlaceholderVerticalAlign.AboveBaseline)
            }
            formula.latex to InlineTextContent(placeholder) {
                Canvas(Modifier.fillMaxSize()) { draw(r) }
            }
        }
    }
}
