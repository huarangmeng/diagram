package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.PieSlice
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
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
    private var title: String? = null
    private var seq: Long = 0L

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) return IrPatchBatch(seq, emptyList())
        if (trimmed.equals("pie", ignoreCase = true)) return IrPatchBatch(seq, emptyList())
        if (trimmed.startsWith("title ", ignoreCase = true)) {
            title = trimmed.substringAfter(' ').trim().takeIf { it.isNotEmpty() }
            return IrPatchBatch(seq, emptyList())
        }

        val parsed = parseSlice(trimmed)
        if (parsed != null) {
            slices += PieSlice(label = RichLabel.Plain(parsed.first), value = parsed.second)
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
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseSlice(line: String): Pair<String, Double>? {
        val colon = line.indexOf(':')
        if (colon <= 0 || colon == line.lastIndex) return null
        val label = line.substring(0, colon).trim().trim('"').trim()
        val value = line.substring(colon + 1).trim().toDoubleOrNull()
        if (label.isEmpty() || value == null) return null
        return label to value
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
}
