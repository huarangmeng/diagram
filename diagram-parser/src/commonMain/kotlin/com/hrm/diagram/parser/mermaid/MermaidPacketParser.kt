package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StructIR
import com.hrm.diagram.core.ir.StructNode
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.parser.common.ParserSession

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

    private val session = ParserSession()
    private val fields: MutableList<Field> = ArrayList()
    private var headerSeen = false
    private var title: String? = null

    fun acceptLine(line: List<Token>): IrPatchBatch {
        session.beginLine()
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return session.emptyBatch()
        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")
        val s = toks.joinToString(" ") { it.text.toString() }.trim()
        if (s.isBlank()) return session.emptyBatch()

        if (!headerSeen) {
            if (s == "packet-beta") {
                headerSeen = true
                return session.emptyBatch()
            }
            return errorBatch("Expected 'packet-beta' header")
        }

        if (s.startsWith("title ", ignoreCase = true)) {
            title = stripQuotes(s.substringAfter(' ').trim()).ifBlank { title }
            return session.emptyBatch()
        }

        val rangeMatch = Regex("""^([0-9]+(?:\s*-\s*[0-9]+)?)\s*:\s*(.+)$""").matchEntire(s)
        if (rangeMatch != null) {
            fields += Field(
                range = rangeMatch.groupValues[1].replace(Regex("\\s+"), ""),
                label = stripQuotes(rangeMatch.groupValues[2].trim()),
            )
            return session.emptyBatch()
        }

        fields += Field(range = null, label = stripQuotes(s))
        return session.emptyBatch()
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

    fun diagnosticsSnapshot(): List<com.hrm.diagram.core.ir.Diagnostic> = session.diagnosticsSnapshot()

    private fun stripQuotes(raw: String): String =
        raw.removeSurrounding("\"").removeSurrounding("'")

    private fun errorBatch(message: String): IrPatchBatch {
        return session.diagnosticBatch(session.error(message, "MERMAID-E215"))
    }
}
