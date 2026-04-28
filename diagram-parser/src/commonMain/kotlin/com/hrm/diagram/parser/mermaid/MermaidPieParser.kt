package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.PieIR
import com.hrm.diagram.core.ir.PieSlice
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `pie` (Phase 2).
 *
 * Supported subset (matches Mermaid docs basics):
 * - `pie` header
 * - optional `title <text...>` line
 * - slice lines: `<label> : <number>`
 *   label can be a STRING token ("Dogs") or IDENT token (Dogs)
 *
 * Error model: never throws on user input; emits diagnostics and keeps parsing.
 */
class MermaidPieParser {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val slices: MutableList<PieSlice> = ArrayList()
    private var title: String? = null
    private var headerSeen: Boolean = false
    private var seq: Long = 0

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())

        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.PIE_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'pie' header")
        }

        // title <rest...>
        if (toks.first().kind == MermaidTokenKind.IDENT && toks.first().text.toString() == "title") {
            val rest = toks.drop(1).joinToString(" ") { it.text.toString() }.trim()
            if (rest.isNotEmpty()) title = rest
            return IrPatchBatch(seq, emptyList())
        }

        val parsed = parseSliceLine(toks)
        return when (parsed) {
            is SliceParse.Ok -> {
                slices += PieSlice(label = RichLabel.Plain(parsed.label), value = parsed.value)
                // PieIR isn't patchable like GraphIR nodes/edges; keep patch empty for now.
                IrPatchBatch(seq, emptyList())
            }
            is SliceParse.Error -> errorBatch(parsed.message)
        }
    }

    fun snapshot(): PieIR = PieIR(
        slices = slices.toList(),
        title = title,
        sourceLanguage = SourceLanguage.MERMAID,
        styleHints = StyleHints(),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    // --- internals ---

    private sealed interface SliceParse {
        data class Ok(val label: String, val value: Double) : SliceParse
        data class Error(val message: String) : SliceParse
    }

    private fun parseSliceLine(toks: List<Token>): SliceParse {
        // <label> ':' <number>
        if (toks.size < 3) return SliceParse.Error("Invalid pie slice line (expected <label> : <number>)")

        val labelTok = toks[0]
        val colonTok = toks.firstOrNull { it.kind == MermaidTokenKind.COLON } ?: return SliceParse.Error("Expected ':' in pie slice line")
        val colonIdx = toks.indexOf(colonTok)
        if (colonIdx <= 0 || colonIdx == toks.lastIndex) return SliceParse.Error("Invalid pie slice line around ':'")

        val label = when (labelTok.kind) {
            MermaidTokenKind.STRING, MermaidTokenKind.IDENT -> labelTok.text.toString()
            else -> return SliceParse.Error("Invalid pie slice label token '${MermaidTokenKind.nameOf(labelTok.kind)}'")
        }.trim()
        if (label.isEmpty()) return SliceParse.Error("Empty pie slice label")

        // Number is expected right after ':'; ignore any trailing comment tokens (already removed).
        val valueTok = toks.getOrNull(colonIdx + 1) ?: return SliceParse.Error("Missing value after ':'")
        if (valueTok.kind != MermaidTokenKind.NUMBER && valueTok.kind != MermaidTokenKind.IDENT) {
            return SliceParse.Error("Invalid pie slice value token '${MermaidTokenKind.nameOf(valueTok.kind)}'")
        }
        val v = valueTok.text.toString().toDoubleOrNull()
            ?: return SliceParse.Error("Invalid pie slice value '${valueTok.text}'")
        return SliceParse.Ok(label = label, value = v)
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E200")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}

