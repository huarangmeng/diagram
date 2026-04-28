package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Axis
import com.hrm.diagram.core.ir.AxisKind
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Series
import com.hrm.diagram.core.ir.SeriesKind
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.ir.XYChartIR
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `xychart` / `xychart-beta`.
 *
 * Supported subset:
 * - header: `xychart`, `xychart-beta`, optional `horizontal`
 * - `title "..."` or `title word`
 * - `x-axis [a,b,c]` or `x-axis "title" min --> max`
 * - `y-axis "title" min --> max` or `y-axis "title"`
 * - `bar [..]`, `line [..]`, `scatter [..]`
 *
 * Mapping:
 * - categorical x-axis => xs are 0..n-1
 * - numeric x-axis => xs are evenly interpolated across declared min..max
 */
class MermaidXYChartParser {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0
    private var headerSeen = false

    private var title: String? = null
    private var orientation: String = "vertical"

    private var xAxisTitle: String? = null
    private var xAxisKind: AxisKind = AxisKind.Category
    private var xCategories: List<String> = emptyList()
    private var xMin: Double? = null
    private var xMax: Double? = null

    private var yAxisTitle: String? = null
    private var yMin: Double? = null
    private var yMax: Double? = null

    private val series: MutableList<Pair<SeriesKind, List<Double>>> = ArrayList()

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())
        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")

        if (!headerSeen) {
            val first = toks.firstOrNull() ?: return IrPatchBatch(seq, emptyList())
            if (first.kind == MermaidTokenKind.XYCHART_HEADER) {
                headerSeen = true
                if (toks.any { it.kind == MermaidTokenKind.IDENT && it.text.toString() == "horizontal" }) {
                    orientation = "horizontal"
                }
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'xychart' header")
        }

        val s = normalize(toks)
        when {
            s.startsWith("title ") -> {
                title = stripQuotes(s.removePrefix("title ").trim()).ifBlank { title }
            }
            s.startsWith("x-axis ") -> parseXAxis(s.removePrefix("x-axis ").trim())
            s.startsWith("y-axis ") -> parseYAxis(s.removePrefix("y-axis ").trim())
            s.startsWith("bar ") -> parseSeries(SeriesKind.Bar, s.removePrefix("bar ").trim())
            s.startsWith("line ") -> parseSeries(SeriesKind.Line, s.removePrefix("line ").trim())
            s.startsWith("scatter ") -> parseSeries(SeriesKind.Scatter, s.removePrefix("scatter ").trim())
            s.startsWith("area ") -> parseSeries(SeriesKind.Area, s.removePrefix("area ").trim())
            else -> diagnostics += Diagnostic(Severity.WARNING, "Unsupported xyChart line ignored: $s", "MERMAID-W012")
        }
        return IrPatchBatch(seq, emptyList())
    }

    fun snapshot(): XYChartIR {
        val maxLen = series.maxOfOrNull { it.second.size } ?: xCategories.size
        val xs: List<Double> = when {
            xAxisKind == AxisKind.Category -> List(maxOf(maxLen, xCategories.size)) { it.toDouble() }
            else -> numericXs(maxLen)
        }
        val builtSeries = series.mapIndexed { idx, (kind, ys) ->
            Series(name = "series$idx", kind = kind, xs = xs.take(ys.size), ys = ys)
        }
        val yRangeAuto = builtSeries.flatMap { it.ys }.takeIf { it.isNotEmpty() }
        val autoYMin = yRangeAuto?.minOrNull()
        val autoYMax = yRangeAuto?.maxOrNull()
        return XYChartIR(
            xAxis = Axis(
                title = xAxisTitle?.let { RichLabel.Plain(it) },
                kind = xAxisKind,
                min = xMin,
                max = xMax,
                categories = xCategories,
            ),
            yAxis = Axis(
                title = yAxisTitle?.let { RichLabel.Plain(it) },
                kind = AxisKind.Linear,
                min = yMin ?: autoYMin,
                max = yMax ?: autoYMax,
            ),
            series = builtSeries,
            title = title,
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(
                direction = if (orientation == "horizontal") Direction.LR else Direction.TB,
                extras = mapOf(
                    "xyChart.orientation" to orientation,
                    "xyChart.showDataLabel" to "false",
                    "xyChart.showDataLabelOutsideBar" to "false",
                ),
            ),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseXAxis(spec: String) {
        // categorical: "title" [a,b,c]  OR [a,b,c]
        val bracketIdx = spec.indexOf('[')
        if (bracketIdx >= 0 && spec.endsWith("]")) {
            val titlePart = spec.substring(0, bracketIdx).trim()
            xAxisTitle = stripQuotes(titlePart).ifBlank { null }
            xAxisKind = AxisKind.Category
            xCategories = parseCategories(spec.substring(bracketIdx))
            return
        }
        // numeric: "title" min --> max
        val parts = spec.split("-->").map { it.trim() }
        if (parts.size == 2) {
            xAxisKind = AxisKind.Linear
            xMax = parts[1].toDoubleOrNull()
            val left = parts[0]
            val minMatch = Regex("""^(?:"([^"]+)"|(\S+))?\s*(-?\d+(?:\.\d+)?)$""").matchEntire(left)
            if (minMatch != null) {
                xAxisTitle = minMatch.groupValues[1].ifBlank { minMatch.groupValues[2] }.ifBlank { null }
                xMin = minMatch.groupValues[3].toDoubleOrNull()
            } else {
                val m = Regex("""(-?\d+(?:\.\d+)?)$""").find(left)
                xMin = m?.value?.toDoubleOrNull()
                xAxisTitle = stripQuotes(left.removeSuffix(m?.value ?: "").trim()).ifBlank { null }
            }
            return
        }
        diagnostics += Diagnostic(Severity.WARNING, "Unsupported x-axis spec ignored: $spec", "MERMAID-W012")
    }

    private fun parseYAxis(spec: String) {
        val parts = spec.split("-->").map { it.trim() }
        if (parts.size == 2) {
            yMax = parts[1].toDoubleOrNull()
            val left = parts[0]
            val minMatch = Regex("""^(?:"([^"]+)"|(\S+))?\s*(-?\d+(?:\.\d+)?)$""").matchEntire(left)
            if (minMatch != null) {
                yAxisTitle = minMatch.groupValues[1].ifBlank { minMatch.groupValues[2] }.ifBlank { null }
                yMin = minMatch.groupValues[3].toDoubleOrNull()
            } else {
                val m = Regex("""(-?\d+(?:\.\d+)?)$""").find(left)
                yMin = m?.value?.toDoubleOrNull()
                yAxisTitle = stripQuotes(left.removeSuffix(m?.value ?: "").trim()).ifBlank { null }
            }
            return
        }
        yAxisTitle = stripQuotes(spec).ifBlank { null }
    }

    private fun parseSeries(kind: SeriesKind, spec: String) {
        if (!spec.startsWith("[") || !spec.endsWith("]")) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid ${kind.name.lowercase()} series syntax", "MERMAID-E206")
            return
        }
        val values = parseNumbers(spec)
        series += kind to values
    }

    private fun parseCategories(spec: String): List<String> {
        val inner = spec.removePrefix("[").removeSuffix("]")
        return splitCsv(inner).map { stripQuotes(it.trim()) }
    }

    private fun parseNumbers(spec: String): List<Double> {
        val inner = spec.removePrefix("[").removeSuffix("]")
        return splitCsv(inner).mapNotNull { it.trim().replace(" ", "").toDoubleOrNull() }
    }

    private fun splitCsv(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in s) {
            if (quote != null) {
                cur.append(ch)
                if (ch == quote) quote = null
                continue
            }
            if (ch == '"' || ch == '\'') {
                quote = ch
                cur.append(ch)
                continue
            }
            if (ch == ',') {
                out += cur.toString()
                cur.setLength(0)
            } else cur.append(ch)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun numericXs(n: Int): List<Double> {
        if (n <= 0) return emptyList()
        val min = xMin ?: 0.0
        val max = xMax ?: (n - 1).toDouble()
        if (n == 1) return listOf(min)
        val step = (max - min) / (n - 1)
        return List(n) { min + it * step }
    }

    private fun stripQuotes(s: String): String = s.removeSurrounding("\"").removeSurrounding("'")

    private fun normalize(toks: List<Token>): String =
        toks.joinToString(" ") {
            when (it.kind) {
                MermaidTokenKind.LABEL -> "[${it.text}]"
                MermaidTokenKind.STRING -> "\"${it.text}\""
                else -> it.text.toString()
            }
        }
            .replace(Regex("\\s+"), " ")
            .replace("x - axis", "x-axis")
            .replace("y - axis", "y-axis")
            .trim()

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E206")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
