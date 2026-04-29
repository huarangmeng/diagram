package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SankeyFlow
import com.hrm.diagram.core.ir.SankeyIR
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `sankey` / `sankey-beta`.
 *
 * Supported subset:
 * - `sankey` / `sankey-beta` header
 * - flow rows: `source,target,value`
 */
class MermaidSankeyParser {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0
    private var headerSeen = false
    private var title: String? = null
    private val nodeOrder: MutableList<NodeId> = ArrayList()
    private val nodeLabels: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val flows: MutableList<SankeyFlow> = ArrayList()

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())
        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.SANKEY_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'sankey' header")
        }

        val s = toks.joinToString(" ") { it.text.toString() }.trim()
        if (s.startsWith("title ")) {
            title = stripQuotes(s.removePrefix("title ").trim()).ifBlank { title }
            return IrPatchBatch(seq, emptyList())
        }

        val parts = splitCsv(s)
        if (parts.size != 3) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid sankey flow syntax", "MERMAID-E209")
            return IrPatchBatch(seq, emptyList())
        }
        val fromLabel = stripQuotes(parts[0].trim())
        val toLabel = stripQuotes(parts[1].trim())
        val value = parts[2].trim().toDoubleOrNull()
        if (fromLabel.isBlank() || toLabel.isBlank() || value == null) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid sankey flow syntax", "MERMAID-E209")
            return IrPatchBatch(seq, emptyList())
        }
        val from = ensureNode(fromLabel)
        val to = ensureNode(toLabel)
        flows += SankeyFlow(from = from, to = to, value = value)
        return IrPatchBatch(seq, emptyList())
    }

    fun snapshot(): SankeyIR =
        SankeyIR(
            nodes = nodeOrder.map { id ->
                Node(
                    id = id,
                    label = RichLabel.Plain(nodeLabels[id].orEmpty()),
                )
            },
            flows = flows.toList(),
            title = title,
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(),
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun ensureNode(label: String): NodeId {
        val existing = nodeLabels.entries.firstOrNull { it.value == label }?.key
        if (existing != null) return existing
        val id = NodeId(nextId(label))
        nodeOrder += id
        nodeLabels[id] = label
        return id
    }

    private fun nextId(label: String): String =
        label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "node_${nodeOrder.size + 1}" }

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
                out += cur.toString().trim()
                cur.setLength(0)
                continue
            }
            cur.append(ch)
        }
        if (cur.isNotEmpty()) out += cur.toString().trim()
        return out
    }

    private fun stripQuotes(raw: String): String =
        raw.removeSurrounding("\"").removeSurrounding("'")

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E209")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
