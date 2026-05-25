package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.GaugeIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token
import com.hrm.diagram.parser.common.ParserSession

/**
 * Streaming parser for Mermaid `gauge` (Phase 2).
 *
 * Supported subset (project-defined, aligned with existing GaugeIR):
 * - `gauge` header
 * - optional `title <text...>` line
 * - `min <number>`, `max <number>`, `value <number>` in any order
 *
 * Notes:
 * - Uses hex-only color policy from style parsers; this parser does not parse style.
 * - Never throws on user input; emits diagnostics and keeps parsing.
 */
class MermaidGaugeParser {
    private val session = ParserSession()
    private var headerSeen: Boolean = false
    private var title: String? = null
    private var min: Double = 0.0
    private var max: Double = 100.0
    private var value: Double = 0.0

    fun acceptLine(line: List<Token>): IrPatchBatch {
        session.beginLine()
        if (line.isEmpty()) return session.emptyBatch()
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return session.emptyBatch()

        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.GAUGE_HEADER) {
                headerSeen = true
                return session.emptyBatch()
            }
            return errorBatch("Expected 'gauge' header")
        }

        // title <rest...>
        if (toks.first().kind == MermaidTokenKind.IDENT && toks.first().text.toString() == "title") {
            val rest = toks.drop(1).joinToString(" ") { it.text.toString() }.trim()
            if (rest.isNotEmpty()) title = rest
            return session.emptyBatch()
        }

        if (toks.first().kind == MermaidTokenKind.IDENT) {
            val kw = toks.first().text.toString()
            val next = toks.getOrNull(1)
            when (kw) {
                "min" -> next?.let { parseNumber(it) }?.let { min = it } ?: return errorBatch("Invalid gauge min")
                "max" -> next?.let { parseNumber(it) }?.let { max = it } ?: return errorBatch("Invalid gauge max")
                "value" -> next?.let { parseNumber(it) }?.let { value = it } ?: return errorBatch("Invalid gauge value")
            }
        }
        return session.emptyBatch()
    }

    fun snapshot(): GaugeIR = GaugeIR(
        value = value,
        min = min,
        max = max,
        title = title,
        sourceLanguage = SourceLanguage.MERMAID,
        styleHints = StyleHints(),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = session.diagnosticsSnapshot()

    private fun parseNumber(tok: Token): Double? {
        val s = tok.text.toString()
        return when (tok.kind) {
            MermaidTokenKind.NUMBER, MermaidTokenKind.IDENT -> s.toDoubleOrNull()
            else -> null
        }
    }

    private fun errorBatch(message: String): IrPatchBatch {
        return session.errorBatch(message, "MERMAID-E201")
    }
}
