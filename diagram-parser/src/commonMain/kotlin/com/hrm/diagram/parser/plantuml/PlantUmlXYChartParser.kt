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
    private val explicitSeries: MutableList<ParsedSeries> = ArrayList()
    private val rowCategories: MutableList<String> = ArrayList()
    private val rowValues: MutableList<Double> = ArrayList()
    private val styleExtras: LinkedHashMap<String, String> = LinkedHashMap()
    private val skinparamSupport = PlantUmlSkinparamSupport(
        styleExtras = styleExtras,
        supportedScopes = setOf("chart", "xychart"),
        scopeKeys = mapOf(
            "chart" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_BACKGROUND_KEY,
                strokeKey = STYLE_AXIS_COLOR_KEY,
                textKey = STYLE_TEXT_KEY,
                fontSizeKey = STYLE_FONT_SIZE_KEY,
                fontNameKey = STYLE_FONT_NAME_KEY,
                lineThicknessKey = STYLE_LINE_THICKNESS_KEY,
                shadowingKey = STYLE_SHADOWING_KEY,
            ),
            "xychart" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_BACKGROUND_KEY,
                strokeKey = STYLE_AXIS_COLOR_KEY,
                textKey = STYLE_TEXT_KEY,
                fontSizeKey = STYLE_FONT_SIZE_KEY,
                fontNameKey = STYLE_FONT_NAME_KEY,
                lineThicknessKey = STYLE_LINE_THICKNESS_KEY,
                shadowingKey = STYLE_SHADOWING_KEY,
            ),
        ),
        directKeys = mapOf(
            "backgroundcolor" to STYLE_BACKGROUND_KEY,
            "fontcolor" to STYLE_TEXT_KEY,
            "defaultfontcolor" to STYLE_TEXT_KEY,
            "linecolor" to STYLE_AXIS_COLOR_KEY,
            "axiscolor" to STYLE_AXIS_COLOR_KEY,
            "bordercolor" to STYLE_AXIS_COLOR_KEY,
        ),
        warnUnsupported = { warnUnsupportedSkinparam(it) },
        emptyBatch = { IrPatchBatch(seq, emptyList()) },
    )
    private var title: String? = null
    private var xAxisTitle: String? = null
    private var xMin: Double? = null
    private var xMax: Double? = null
    private var xCategories: List<String> = emptyList()
    private var yAxisTitle: String? = null
    private var yMin: Double? = null
    private var yMax: Double? = null
    private var seq: Long = 0L

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) return IrPatchBatch(seq, emptyList())
        if (skinparamSupport.pendingScope != null) {
            if (trimmed == "}") {
                skinparamSupport.pendingScope = null
                return IrPatchBatch(seq, emptyList())
            }
            return skinparamSupport.acceptScopedEntry(skinparamSupport.pendingScope!!, trimmed)
        }
        if (trimmed.startsWith("skinparam", ignoreCase = true)) return skinparamSupport.acceptDirective(trimmed)
        if (
            trimmed.equals("chart", ignoreCase = true) ||
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
        if (trimmed.startsWith("h-axis ", ignoreCase = true)) {
            parseXAxis(trimmed.substringAfter(' ').trim())
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.startsWith("y-axis ", ignoreCase = true)) {
            parseYAxis(trimmed.substringAfter(' ').trim())
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.startsWith("v-axis ", ignoreCase = true)) {
            parseYAxis(trimmed.substringAfter(' ').trim())
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.startsWith("legend ", ignoreCase = true)) {
            val position = trimmed.substringAfter(' ').trim().lowercase()
            if (position in setOf("left", "right", "top", "bottom")) {
                styleExtras[STYLE_LEGEND_KEY] = position
                return IrPatchBatch(seq, emptyList())
            }
        }
        if (trimmed.equals("hide legend", ignoreCase = true) || trimmed.equals("legend off", ignoreCase = true)) {
            styleExtras[STYLE_LEGEND_KEY] = "none"
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.startsWith("stackMode ", ignoreCase = true)) {
            val mode = trimmed.substringAfter(' ').trim().lowercase()
            if (mode in setOf("grouped", "stacked")) {
                styleExtras[STYLE_STACK_MODE_KEY] = mode
                return IrPatchBatch(seq, emptyList())
            }
        }
        if (trimmed.startsWith("orientation ", ignoreCase = true)) {
            val orientation = trimmed.substringAfter(' ').trim().lowercase()
            if (orientation in setOf("vertical", "horizontal")) {
                styleExtras[STYLE_ORIENTATION_KEY] = orientation
                return IrPatchBatch(seq, emptyList())
            }
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
                val size = explicitSeries.maxOfOrNull { it.ys.size } ?: 0
                List(size) { (it + 1).toString() }
            }
        }
        val series = buildSeries(categories)
        val allYs = series.flatMap { it.ys }
        return XYChartIR(
            xAxis = Axis(
                title = xAxisTitle?.let { RichLabel.Plain(it) },
                kind = if (xMin != null || xMax != null) AxisKind.Linear else AxisKind.Category,
                min = xMin,
                max = xMax,
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
            styleHints = StyleHints(extras = styleExtras.toMap()),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun buildSeries(categories: List<String>): List<Series> {
        val xs = List(categories.size) { it.toDouble() }
        if (explicitSeries.isEmpty()) {
            return listOf(Series("series0", defaultKind, xs.take(rowValues.size), rowValues.toList()))
        }
        return explicitSeries.mapIndexed { index, parsed ->
            val seriesName = parsed.name ?: "series$index"
            if (parsed.xs != null) {
                Series(seriesName, parsed.kind, parsed.xs, parsed.ys)
            } else {
                Series(seriesName, parsed.kind, xs.take(parsed.ys.size), parsed.ys)
            }
        }
    }

    private fun parseXAxis(spec: String) {
        val bracket = spec.indexOf('[')
        if (bracket >= 0 && spec.endsWith("]")) {
            xAxisTitle = stripQuotes(spec.substring(0, bracket).trim()).ifBlank { null }
            xCategories = parseCategories(spec.substring(bracket))
            return
        }
        val parts = spec.split("-->").map { it.trim() }
        if (parts.size == 2) {
            val maxToken = parts[1].substringBefore(" ").trim()
            xMax = maxToken.toDoubleOrNull()
            val left = parts[0]
            val min = NUMBER_AT_END.find(left)
            xMin = min?.value?.toDoubleOrNull()
            xAxisTitle = stripQuotes(left.removeSuffix(min?.value ?: "").trim()).ifBlank { null }
            return
        }
        xAxisTitle = stripQuotes(spec).ifBlank { null }
    }

    private fun parseYAxis(spec: String) {
        val parts = spec.split("-->").map { it.trim() }
        if (parts.size == 2) {
            yMax = parts[1].substringBefore(" ").trim().toDoubleOrNull()
            val left = parts[0]
            val min = NUMBER_AT_END.find(left)
            yMin = min?.value?.toDoubleOrNull()
            yAxisTitle = stripQuotes(left.removeSuffix(min?.value ?: "").trim()).ifBlank { null }
            SPACING.find(spec)?.groupValues?.getOrNull(1)?.let { styleExtras[STYLE_Y_SPACING_KEY] = it }
            return
        }
        yAxisTitle = stripQuotes(spec).ifBlank { null }
    }

    private fun parseSeriesLine(kind: SeriesKind, spec: String): IrPatchBatch {
        val parsed = parseSeriesSpec(kind, spec) ?: return errorBatch("Invalid ${kind.name.lowercase()} series syntax")
        explicitSeries += parsed
        val index = explicitSeries.lastIndex
        parsed.color?.let { styleExtras["$STYLE_SERIES_COLOR_PREFIX$index"] = it }
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseSeriesSpec(kind: SeriesKind, spec: String): ParsedSeries? {
        val bracket = spec.indexOf('[')
        if (bracket < 0 || !spec.contains(']')) {
            return null
        }
        val name = stripQuotes(spec.substring(0, bracket).trim()).ifBlank { null }
        val endBracket = spec.lastIndexOf(']')
        val data = spec.substring(bracket, endBracket + 1)
        val tail = spec.substring(endBracket + 1).trim()
        val color = tail.split(Regex("""\s+""")).firstOrNull { isColorToken(it) }
        if (data.startsWith("[(")) {
            val pairs = parsePairs(data)
            if (pairs.isEmpty()) return null
            return ParsedSeries(kind, name, pairs.map { it.first }, pairs.map { it.second }, color)
        }
        if (!data.startsWith("[") || !data.endsWith("]")) return null
        return ParsedSeries(kind, name, null, parseNumbers(data), color)
    }

    private fun parseCategoryValue(line: String): Pair<String, Double>? {
        val colon = line.indexOf(':')
        if (colon <= 0 || colon == line.lastIndex) return null
        val category = stripQuotes(line.substring(0, colon).trim()).ifBlank { return null }
        val value = line.substring(colon + 1).trim().removeSuffix("%").toDoubleOrNull() ?: return null
        return category to value
    }

    private fun parseCategories(spec: String): List<String> =
        splitCsv(spec.removePrefix("[").removeSuffix("]")).map { stripQuotes(it.trim()) }.filter { it.isNotEmpty() }

    private fun parseNumbers(spec: String): List<Double> =
        splitCsv(spec.removePrefix("[").removeSuffix("]")).mapNotNull { it.trim().removeSuffix("%").toDoubleOrNull() }

    private fun parsePairs(spec: String): List<Pair<Double, Double>> =
        Regex("""\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)""")
            .findAll(spec)
            .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
            .toList()

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

    private fun isColorToken(text: String): Boolean =
        text.startsWith("#") || text.all { it.isLetter() }

    private fun warnUnsupportedSkinparam(line: String): IrPatchBatch {
        val d = Diagnostic(
            severity = Severity.WARNING,
            message = "Unsupported PlantUML chart skinparam '$line'",
            code = "PLANTUML-W001",
        )
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(
            severity = Severity.ERROR,
            message = message,
            code = "PLANTUML-E022",
        )
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }

    private data class ParsedSeries(
        val kind: SeriesKind,
        val name: String?,
        val xs: List<Double>?,
        val ys: List<Double>,
        val color: String?,
    )

    companion object {
        const val STYLE_BACKGROUND_KEY = "plantuml.chart.background"
        const val STYLE_AXIS_COLOR_KEY = "plantuml.chart.axisColor"
        const val STYLE_TEXT_KEY = "plantuml.chart.text"
        const val STYLE_FONT_SIZE_KEY = "plantuml.chart.fontSize"
        const val STYLE_FONT_NAME_KEY = "plantuml.chart.fontName"
        const val STYLE_LINE_THICKNESS_KEY = "plantuml.chart.lineThickness"
        const val STYLE_SHADOWING_KEY = "plantuml.chart.shadowing"
        const val STYLE_LEGEND_KEY = "plantuml.chart.legend"
        const val STYLE_STACK_MODE_KEY = "plantuml.chart.stackMode"
        const val STYLE_ORIENTATION_KEY = "plantuml.chart.orientation"
        const val STYLE_Y_SPACING_KEY = "plantuml.chart.ySpacing"
        const val STYLE_SERIES_COLOR_PREFIX = "plantuml.chart.seriesColor."
        private val NUMBER_AT_END = Regex("""-?\d+(?:\.\d+)?$""")
        private val SPACING = Regex("""\bspacing\s+(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    }
}
