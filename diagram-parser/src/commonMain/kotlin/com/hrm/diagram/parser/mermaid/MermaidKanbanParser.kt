package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.KanbanCard
import com.hrm.diagram.core.ir.KanbanColumn
import com.hrm.diagram.core.ir.KanbanIR
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `kanban`.
 *
 * Supported subset:
 * - `kanban` header
 * - columns: `id[Title]` or `[Title]` or bare title
 * - cards indented under columns: `id[Task]` or `[Task]`
 * - metadata tail: `@{ key: value, assigned: 'x', ticket: MC-2037, priority: 'High' }`
 *
 * Notes:
 * - Frontmatter/config is already stripped before lexing by the session pipeline.
 * - `ticketBaseUrl` config is not consumed yet; metadata is stored in `payload`.
 */
class MermaidKanbanParser {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0
    private var headerSeen = false

    private data class MutableColumn(
        val id: NodeId,
        val label: String,
        val cards: MutableList<KanbanCard> = ArrayList(),
    )

    private val columns: MutableList<MutableColumn> = ArrayList()
    private var currentColumn: MutableColumn? = null
    private var autoColumn = 0
    private var autoCard = 0
    private var columnIndentBase: Int? = null

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())
        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.KANBAN_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'kanban' header")
        }

        val indent = if (toks.firstOrNull()?.kind == MermaidTokenKind.INDENT) toks.first().text.toString().toIntOrNull() ?: 0 else 0
        val content = if (indent > 0) toks.drop(1) else toks
        if (content.isEmpty()) return IrPatchBatch(seq, emptyList())

        val base = columnIndentBase
        if (base == null || indent <= base) {
            if (columnIndentBase == null) columnIndentBase = indent
            val col = parseColumn(content) ?: return errorBatch("Invalid kanban column line")
            columns += col
            currentColumn = col
            return IrPatchBatch(seq, emptyList())
        }

        val parent = currentColumn ?: return errorBatch("Kanban card without a column")
        val card = parseCard(content) ?: return errorBatch("Invalid kanban card line")
        parent.cards += card
        return IrPatchBatch(seq, emptyList())
    }

    fun snapshot(): KanbanIR =
        KanbanIR(
            columns = columns.map { c ->
                KanbanColumn(
                    id = c.id,
                    label = RichLabel.Plain(c.label),
                    cards = c.cards.toList(),
                )
            },
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(),
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseColumn(toks: List<Token>): MutableColumn? {
        val (id, label) = parseBracketItem(toks, isColumn = true) ?: return null
        return MutableColumn(id = id, label = label)
    }

    private fun parseCard(toks: List<Token>): KanbanCard? {
        val metaIdx = toks.indexOfFirst { it.kind == MermaidTokenKind.AT }
        val core = if (metaIdx >= 0) toks.subList(0, metaIdx) else toks
        val meta = if (metaIdx >= 0) toks.subList(metaIdx, toks.size) else emptyList()
        val (id, label) = parseBracketItem(core, isColumn = false) ?: return null
        return KanbanCard(
            id = id,
            label = RichLabel.Plain(label),
            payload = parseMetadata(meta),
        )
    }

    private fun parseBracketItem(toks: List<Token>, isColumn: Boolean): Pair<NodeId, String>? {
        // forms:
        // - id[LABEL]
        // - [LABEL]
        // - bare words
        if (toks.isEmpty()) return null
        if (toks.size >= 2 && toks[0].kind == MermaidTokenKind.IDENT && toks[1].kind == MermaidTokenKind.LABEL) {
            return NodeId(toks[0].text.toString()) to normalizeLabel(toks[1].text.toString())
        }
        if (toks[0].kind == MermaidTokenKind.LABEL) {
            val label = normalizeLabel(toks[0].text.toString())
            return NodeId(if (isColumn) nextColumnId(label) else nextCardId(label)) to label
        }
        val label = joinText(toks).trim()
        if (label.isBlank()) return null
        return NodeId(if (isColumn) nextColumnId(label) else nextCardId(label)) to label
    }

    private fun parseMetadata(toks: List<Token>): Map<String, String> {
        if (toks.isEmpty()) return emptyMap()
        // Expect `@` + `{...}` or `@` + LABEL_BRACE
        val brace = toks.firstOrNull { it.kind == MermaidTokenKind.LABEL_BRACE }
            ?: return emptyMap()
        val raw = brace.text.toString()
        val inner = raw.removePrefix("{").removeSuffix("}")
        if (inner.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (entry in splitTopLevel(inner, ',')) {
            val idx = entry.indexOf(':')
            if (idx <= 0) continue
            val k = entry.substring(0, idx).trim()
            val v = entry.substring(idx + 1).trim().removeSurrounding("'").removeSurrounding("\"")
            if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
        }
        return map
    }

    private fun splitTopLevel(s: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in s) {
            if (quote != null) {
                cur.append(ch)
                if (ch == quote) quote = null
                continue
            }
            if (ch == '\'' || ch == '"') {
                quote = ch
                cur.append(ch)
                continue
            }
            if (ch == delimiter) {
                out += cur.toString().trim()
                cur.setLength(0)
                continue
            }
            cur.append(ch)
        }
        if (cur.isNotEmpty()) out += cur.toString().trim()
        return out
    }

    private fun normalizeLabel(raw: String): String =
        raw.trim().replace("<br>", "\n", ignoreCase = true).replace("<br/>", "\n", ignoreCase = true)

    private fun joinText(toks: List<Token>): String =
        buildString {
            var first = true
            for (t in toks) {
                if (!first) append(' ')
                append(t.text.toString())
                first = false
            }
        }

    private fun nextColumnId(label: String): String {
        autoColumn++
        val base = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (base.isBlank()) "column_$autoColumn" else "${base}_$autoColumn"
    }

    private fun nextCardId(label: String): String {
        autoCard++
        val base = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (base.isBlank()) "card_$autoCard" else "${base}_$autoCard"
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E205")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
