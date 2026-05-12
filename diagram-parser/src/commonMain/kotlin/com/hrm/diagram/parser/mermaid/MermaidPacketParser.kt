package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.StructNode
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `packet-beta`.
 *
 * Supported syntax:
 * - `packet-beta` header
 * - optional `title ...`
 * - byte ranges: `0-15: "Source Port"` / `16-31: Destination Port`
 * - standalone fields: `Flags`
 */
class MermaidPacketParser {
    private data class Field(val range: String?, val label: String)

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val fields: MutableList<Field> = ArrayList()
    private var headerSeen = false
    private var title: String? = null
    private var seq: Long = 0L

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())
        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")
        val s = toks.joinToString(" ") { it.text.toString() }.trim()
        if (s.isBlank()) return IrPatchBatch(seq, emptyList())

        if (!headerSeen) {
            if (s == "packet-beta") {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'packet-beta' header")
        }

        if (s.startsWith("title ", ignoreCase = true)) {
            title = stripQuotes(s.substringAfter(' ').trim()).ifBlank { title }
            return IrPatchBatch(seq, emptyList())
        }

        val rangeMatch = Regex("""^([0-9]+(?:\s*-\s*[0-9]+)?)\s*:\s*(.+)$""").matchEntire(s)
        if (rangeMatch != null) {
            fields += Field(
                range = rangeMatch.groupValues[1].replace(Regex("\\s+"), ""),
                label = stripQuotes(rangeMatch.groupValues[2].trim()),
            )
            return IrPatchBatch(seq, emptyList())
        }

        fields += Field(range = null, label = stripQuotes(s))
        return IrPatchBatch(seq, emptyList())
    }

    fun snapshot(): StructIR =
        StructIR(
            root = StructNode.ObjectNode(
                key = title ?: "packet-beta",
                entries = fields.mapIndexed { index, field ->
                    StructNode.Scalar(
                        key = field.range ?: "field${index + 1}",
                        value = field.label,
                    )
                },
            ),
            title = title,
            sourceLanguage = SourceLanguage.MERMAID,
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun stripQuotes(raw: String): String =
        raw.removeSurrounding("\"").removeSurrounding("'")

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E215")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
