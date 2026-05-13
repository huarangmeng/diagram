package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.PieSlice
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Streaming parser for PlantUML pie chart blocks.
 *
 * Supported syntax:
 * - `@startpie ... @endpie` dedicated blocks
 * - optional `pie` header when embedded in `@startuml`
 * - `title <text>`
 * - slice rows such as `"Dogs" : 42` or `Cats : 28`
 *
 * Example:
 * ```kotlin
 * val parser = PlantUmlPieParser()
 * parser.acceptLine("title Pets")
 * parser.acceptLine("\"Dogs\" : 42")
 * val ir = parser.snapshot()
 * ```
 */
@DiagramApi
class PlantUmlPieParser {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val slices: MutableList<PieSlice> = ArrayList()
    private val styleExtras: LinkedHashMap<String, String> = LinkedHashMap()
    private val skinparamSupport = PlantUmlSkinparamSupport(
        styleExtras = styleExtras,
        supportedScopes = setOf("pie"),
        scopeKeys = mapOf(
            "pie" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_BACKGROUND_KEY,
                strokeKey = STYLE_BORDER_KEY,
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
            "linecolor" to STYLE_BORDER_KEY,
            "bordercolor" to STYLE_BORDER_KEY,
        ),
        warnUnsupported = { warnUnsupportedSkinparam(it) },
        emptyBatch = { IrPatchBatch(seq, emptyList()) },
    )
    private var title: String? = null
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
        if (trimmed.equals("pie", ignoreCase = true)) return IrPatchBatch(seq, emptyList())
        if (trimmed.startsWith("title ", ignoreCase = true)) {
            title = trimmed.substringAfter(' ').trim().takeIf { it.isNotEmpty() }
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

        val parsed = parseSlice(trimmed)
        if (parsed != null) {
            val index = slices.size
            slices += PieSlice(label = RichLabel.Plain(parsed.label), value = parsed.value)
            parsed.color?.let { styleExtras["$STYLE_SLICE_COLOR_PREFIX$index"] = it }
            return IrPatchBatch(seq, emptyList())
        }
        return errorBatch("Invalid PlantUML pie slice line '$trimmed'")
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        if (blockClosed) return IrPatchBatch(seq, emptyList())
        return errorBatch("Missing @endpie closing delimiter")
    }

    fun snapshot(): PieIR =
        PieIR(
            slices = slices.toList(),
            title = title,
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = StyleHints(extras = styleExtras.toMap()),
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseSlice(line: String): ParsedSlice? {
        val colon = line.indexOf(':')
        if (colon <= 0 || colon == line.lastIndex) return null
        val left = line.substring(0, colon).trim()
        val right = line.substring(colon + 1).trim()
        val colorAndLabel = parseLeadingColor(left)
        val valueAndColor = parseValueAndTrailingColor(right)
        val label = stripQuotes(colorAndLabel.second).trim()
        val value = valueAndColor.first
        if (label.isEmpty() || value == null) return null
        return ParsedSlice(label = label, value = value, color = colorAndLabel.first ?: valueAndColor.second)
    }

    private fun parseLeadingColor(text: String): Pair<String?, String> {
        val match = Regex("""^(#[A-Za-z0-9_]+|[A-Za-z]+)\s*;\s*(.+)$""").matchEntire(text)
        return if (match != null) match.groupValues[1] to match.groupValues[2] else null to text
    }

    private fun parseValueAndTrailingColor(text: String): Pair<Double?, String?> {
        val parts = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val value = parts.firstOrNull()?.removeSuffix("%")?.toDoubleOrNull()
        val color = parts.drop(1).firstOrNull { isColorToken(it) }
        return value to color
    }

    private fun isColorToken(text: String): Boolean =
        text.startsWith("#") || text.all { it.isLetter() }

    private fun stripQuotes(s: String): String = s.removeSurrounding("\"").removeSurrounding("'")

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
            code = "PLANTUML-E021",
        )
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }

    private data class ParsedSlice(val label: String, val value: Double, val color: String?)

    companion object {
        const val STYLE_BACKGROUND_KEY = "plantuml.chart.background"
        const val STYLE_BORDER_KEY = "plantuml.chart.border"
        const val STYLE_TEXT_KEY = "plantuml.chart.text"
        const val STYLE_FONT_SIZE_KEY = "plantuml.chart.fontSize"
        const val STYLE_FONT_NAME_KEY = "plantuml.chart.fontName"
        const val STYLE_LINE_THICKNESS_KEY = "plantuml.chart.lineThickness"
        const val STYLE_SHADOWING_KEY = "plantuml.chart.shadowing"
        const val STYLE_LEGEND_KEY = "plantuml.chart.legend"
        const val STYLE_SLICE_COLOR_PREFIX = "plantuml.chart.sliceColor."
    }
}
