package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.Axis
import com.hrm.diagram.core.ir.AxisKind
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Series
import com.hrm.diagram.core.ir.SeriesKind
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.ir.XYChartIR
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Streaming parser for PlantUML bar / line / scatter chart blocks.
 *
 * Supported syntax:
 * - `@startbar ... @endbar`, `@startline ... @endline`, and `@startscatter ... @endscatter`
 * - optional `bar` / `line` / `scatter` header in ordinary `@startuml` blocks
 * - `title <text>`
 * - `x-axis [Jan, Feb]`
 * - `y-axis 0 --> 100` or `y-axis "Votes" 0 --> 100`
 * - `bar [10, 20]` / `line [10, 20]` / `scatter [10, 20]`
 * - simple single-series rows such as `Jan : 10`
 *
 * Example:
 * ```kotlin
 * val parser = PlantUmlXYChartParser(SeriesKind.Bar)
 * parser.acceptLine("title Votes")
 * parser.acceptLine("Jan : 10")
 * val ir = parser.snapshot()
 * ```
 */
@DiagramApi
class PlantUmlXYChartParser(
    private val defaultKind: SeriesKind,
) {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val explicitSeries: MutableList<Pair<SeriesKind, List<Double>>> = ArrayList()
    private val rowCategories: MutableList<String> = ArrayList()
    private val rowValues: MutableList<Double> = ArrayList()
    private var title: String? = null
    private var xAxisTitle: String? = null
    private var xCategories: List<String> = emptyList()
    private var yAxisTitle: String? = null
    private var yMin: Double? = null
    private var yMax: Double? = null
    private var seq: Long = 0L

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) return IrPatchBatch(seq, emptyList())
        if (
            trimmed.equals("bar", ignoreCase = true) ||
            trimmed.equals("line", ignoreCase = true) ||
            trimmed.equals("scatter", ignoreCase = true)
        ) {
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.startsWith("title ", ignoreCase = true)) {
            title = trimmed.substringAfter(' ').trim().takeIf { it.isNotEmpty() }
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.startsWith("x-axis ", ignoreCase = true)) {
            parseXAxis(trimmed.substringAfter(' ').trim())
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.startsWith("y-axis ", ignoreCase = true)) {
            parseYAxis(trimmed.substringAfter(' ').trim())
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.startsWith("bar ", ignoreCase = true)) {
            return parseSeriesLine(SeriesKind.Bar, trimmed.substringAfter(' ').trim())
        }
        if (trimmed.startsWith("line ", ignoreCase = true)) {
            return parseSeriesLine(SeriesKind.Line, trimmed.substringAfter(' ').trim())
        }
        if (trimmed.startsWith("scatter ", ignoreCase = true)) {
            return parseSeriesLine(SeriesKind.Scatter, trimmed.substringAfter(' ').trim())
        }
        parseCategoryValue(trimmed)?.let { (category, value) ->
            rowCategories += category
            rowValues += value
            return IrPatchBatch(seq, emptyList())
        }
        return errorBatch("Invalid PlantUML chart line '$trimmed'")
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        if (blockClosed) return IrPatchBatch(seq, emptyList())
        return errorBatch("Missing chart closing delimiter")
    }

    fun snapshot(): XYChartIR {
        val categories = when {
            xCategories.isNotEmpty() -> xCategories
            rowCategories.isNotEmpty() -> rowCategories.toList()
            else -> {
                val size = explicitSeries.maxOfOrNull { it.second.size } ?: 0
                List(size) { (it + 1).toString() }
            }
        }
        val series = buildSeries(categories)
        val allYs = series.flatMap { it.ys }
        return XYChartIR(
            xAxis = Axis(
                title = xAxisTitle?.let { RichLabel.Plain(it) },
                kind = AxisKind.Category,
                categories = categories,
            ),
            yAxis = Axis(
                title = yAxisTitle?.let { RichLabel.Plain(it) },
                kind = AxisKind.Linear,
                min = yMin ?: allYs.minOrNull(),
                max = yMax ?: allYs.maxOrNull(),
            ),
            series = series,
            title = title,
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = StyleHints(),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun buildSeries(categories: List<String>): List<Series> {
        val xs = List(categories.size) { it.toDouble() }
        if (explicitSeries.isEmpty()) {
            return listOf(Series("series0", defaultKind, xs.take(rowValues.size), rowValues.toList()))
        }
        return explicitSeries.mapIndexed { index, (kind, ys) ->
            Series("series$index", kind, xs.take(ys.size), ys)
        }
    }

    private fun parseXAxis(spec: String) {
        val bracket = spec.indexOf('[')
        if (bracket >= 0 && spec.endsWith("]")) {
            xAxisTitle = stripQuotes(spec.substring(0, bracket).trim()).ifBlank { null }
            xCategories = parseCategories(spec.substring(bracket))
        }
    }

    private fun parseYAxis(spec: String) {
        val parts = spec.split("-->").map { it.trim() }
        if (parts.size == 2) {
            yMax = parts[1].toDoubleOrNull()
            val left = parts[0]
            val min = Regex("""-?\d+(?:\.\d+)?$""").find(left)
            yMin = min?.value?.toDoubleOrNull()
            yAxisTitle = stripQuotes(left.removeSuffix(min?.value ?: "").trim()).ifBlank { null }
            return
        }
        yAxisTitle = stripQuotes(spec).ifBlank { null }
    }

    private fun parseSeriesLine(kind: SeriesKind, spec: String): IrPatchBatch {
        if (!spec.startsWith("[") || !spec.endsWith("]")) {
            return errorBatch("Invalid ${kind.name.lowercase()} series syntax")
        }
        explicitSeries += kind to parseNumbers(spec)
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseCategoryValue(line: String): Pair<String, Double>? {
        val colon = line.indexOf(':')
        if (colon <= 0 || colon == line.lastIndex) return null
        val category = stripQuotes(line.substring(0, colon).trim()).ifBlank { return null }
        val value = line.substring(colon + 1).trim().toDoubleOrNull() ?: return null
        return category to value
    }

    private fun parseCategories(spec: String): List<String> =
        splitCsv(spec.removePrefix("[").removeSuffix("]")).map { stripQuotes(it.trim()) }.filter { it.isNotEmpty() }

    private fun parseNumbers(spec: String): List<Double> =
        splitCsv(spec.removePrefix("[").removeSuffix("]")).mapNotNull { it.trim().toDoubleOrNull() }

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
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun stripQuotes(s: String): String = s.removeSurrounding("\"").removeSurrounding("'")

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(
            severity = Severity.ERROR,
            message = message,
            code = "PLANTUML-E022",
        )
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
