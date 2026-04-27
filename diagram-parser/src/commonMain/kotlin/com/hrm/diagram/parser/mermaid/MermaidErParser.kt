package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `erDiagram` (Phase 1 subset).
 *
 * Supported forms (line-based):
 * - Header: `erDiagram`
 * - Entity block:
 *   - `ENTITY {`
 *   - `<type> <name> [PK|FK|UK]...`
 *   - `}`
 * - Relationship:
 *   - `A ||--o{ B : label`
 *
 * Design note: [IrPatch] does not allow updating node labels after creation. To preserve
 * streaming correctness we keep entity nodes as just the entity name; attributes become
 * their own nodes connected to the entity.
 */
class MermaidErParser {
    private val knownNodes: HashSet<NodeId> = HashSet()
    private var headerSeen: Boolean = false

    private val nodes: MutableList<Node> = ArrayList()
    private val edges: MutableList<Edge> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0

    private var currentEntity: NodeId? = null

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())

        val lexErr = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (lexErr != null) return errorBatch("Lex error at ${lexErr.start}: ${lexErr.text}", code = "MERMAID-E001")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.ER_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'erDiagram' header", code = "MERMAID-E001")
        }

        // Inside entity block: either attribute line or closing brace.
        val entity = currentEntity
        if (entity != null) {
            if (toks.size == 1 && toks[0].kind == MermaidTokenKind.RBRACE) {
                currentEntity = null
                return IrPatchBatch(seq, emptyList())
            }
            return parseAttributeLine(entity, toks)
        }

        // Entity declaration: `ENTITY {` or just `ENTITY`.
        if (toks.first().kind == MermaidTokenKind.IDENT && toks.size >= 2 && toks[1].kind == MermaidTokenKind.LBRACE) {
            val id = NodeId(toks.first().text.toString())
            val patches = ArrayList<IrPatch>()
            registerEntity(id, patches)
            currentEntity = id
            return IrPatchBatch(seq, patches)
        }
        if (toks.size == 1 && toks[0].kind == MermaidTokenKind.IDENT) {
            val patches = ArrayList<IrPatch>()
            registerEntity(NodeId(toks[0].text.toString()), patches)
            return IrPatchBatch(seq, patches)
        }

        // Relationship: `A <rel> B [: label]`
        return parseRelationshipLine(toks)
    }

    fun snapshot(): GraphIR = GraphIR(
        nodes = nodes.toList(),
        edges = edges.toList(),
        sourceLanguage = SourceLanguage.MERMAID,
        styleHints = StyleHints(),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    // --- internals ---

    private fun registerEntity(id: NodeId, out: MutableList<IrPatch>) {
        if (id in knownNodes) return
        knownNodes += id
        val n = Node(
            id = id,
            label = RichLabel.Plain(id.value),
            shape = NodeShape.Box,
        )
        nodes += n
        out += IrPatch.AddNode(n)
    }

    private fun registerAttribute(entity: NodeId, type: String, name: String, flags: List<String>, out: MutableList<IrPatch>) {
        val attrId = NodeId("${entity.value}::${name}")
        if (attrId !in knownNodes) {
            knownNodes += attrId
            val flagSuffix = if (flags.isEmpty()) "" else " " + flags.joinToString(" ")
            val label = "$name: $type$flagSuffix"
            val n = Node(
                id = attrId,
                label = RichLabel.Plain(label),
                shape = NodeShape.RoundedBox,
            )
            nodes += n
            out += IrPatch.AddNode(n)
        }
        val e = Edge(from = entity, to = attrId, label = null, arrow = ArrowEnds.None)
        edges += e
        out += IrPatch.AddEdge(e)
    }

    private fun parseAttributeLine(entity: NodeId, toks: List<Token>): IrPatchBatch {
        // Minimal form: `<type> <name> [flags...]`
        if (toks.size < 2 || toks[0].kind != MermaidTokenKind.IDENT || toks[1].kind != MermaidTokenKind.IDENT) {
            return errorBatch("Invalid attribute line (expected: <type> <name> [PK|FK|UK])", code = "MERMAID-E004")
        }
        val type = toks[0].text.toString()
        val name = toks[1].text.toString()
        val flags = toks.drop(2).filter { it.kind == MermaidTokenKind.IDENT }.map { it.text.toString() }
        val patches = ArrayList<IrPatch>()
        registerAttribute(entity, type, name, flags, patches)
        return IrPatchBatch(seq, patches)
    }

    private fun parseRelationshipLine(toks: List<Token>): IrPatchBatch {
        if (toks.size < 3) return errorBatch("Invalid relationship line", code = "MERMAID-E003")
        if (toks[0].kind != MermaidTokenKind.IDENT || toks[1].kind != MermaidTokenKind.ER_REL || toks[2].kind != MermaidTokenKind.IDENT) {
            return errorBatch("Invalid relationship line (expected: A <card><link><card> B)", code = "MERMAID-E003")
        }

        val a = NodeId(toks[0].text.toString())
        val op = toks[1].text.toString()
        val b = NodeId(toks[2].text.toString())

        var label: String? = null
        if (toks.size >= 5 && toks[3].kind == MermaidTokenKind.COLON && toks[4].kind == MermaidTokenKind.LABEL) {
            label = toks[4].text.toString()
        }
        val patches = ArrayList<IrPatch>()
        registerEntity(a, patches)
        registerEntity(b, patches)

        val fullLabel = buildString {
            append(op)
            if (!label.isNullOrBlank()) {
                append(" ")
                append(label)
            }
        }
        val e = Edge(from = a, to = b, label = RichLabel.Plain(fullLabel), arrow = ArrowEnds.None)
        edges += e
        patches += IrPatch.AddEdge(e)
        return IrPatchBatch(seq, patches)
    }

    private fun errorBatch(message: String, code: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, code)
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
