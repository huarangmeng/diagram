package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for the Mermaid flowchart Phase 1 subset.
 *
 * Grammar (informal EBNF):
 * ```
 * file       := header NEWLINE+ ( statement (NEWLINE+ statement)* NEWLINE* )?
 * header     := KEYWORD_HEADER DIRECTION
 * statement  := nodeDecl | edge
 * nodeDecl   := IDENT LABEL?
 * edge       := IDENT LABEL? ARROW_SOLID IDENT LABEL?
 * ```
 *
 * Streaming model: callers feed *complete logical lines* of tokens via [acceptLine]; the parser
 * only emits IR patches for fully-parsed statements. The corresponding chunk-driver is in
 * [com.hrm.diagram.render.streaming.mermaid.MermaidSessionPipeline].
 *
 * Patch invariants enforced here:
 * - First reference to a [NodeId] emits exactly one [IrPatch.AddNode]; later mentions skip it.
 * - Edges always emit [IrPatch.AddEdge] (forward references are legal — see `IrPatch.kt`).
 * - On parse error the bad line is dropped and a [Diagnostic] is appended via
 *   [IrPatch.AddDiagnostic]; the parser stays alive for subsequent lines.
 */
public class MermaidFlowchartParser {
    private val knownNodes: HashSet<NodeId> = HashSet()
    public var direction: Direction = Direction.TB
        private set
    public var headerSeen: Boolean = false
        private set

    /** Accumulated IR view (rebuilt from patches on each [snapshot] call for tests / consumers). */
    private val nodes: MutableList<Node> = ArrayList()
    private val edges: MutableList<Edge> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0

    /**
     * Feed one logical line of tokens (already terminated; no NEWLINE/COMMENT/ERROR included
     * unless they form the line's content). Returns the patch produced this round.
     *
     * Implementations of [com.hrm.diagram.render.streaming.mermaid.MermaidSessionPipeline] split
     * the lexer's token stream on NEWLINE and feed each segment here; comments are dropped at
     * the splitter, ERROR tokens are surfaced as diagnostics.
     */
    public fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val patches = ArrayList<IrPatch>()

        if (!headerSeen) {
            parseHeader(line, patches)
            return IrPatchBatch(seq, patches)
        }

        // Body line.
        when (val parsed = parseStatement(line)) {
            is StmtParse.NodeDecl -> registerNode(parsed.id, parsed.label, patches)
            is StmtParse.EdgeDecl -> {
                registerNode(parsed.from, parsed.fromLabel, patches)
                registerNode(parsed.to, parsed.toLabel, patches)
                val e = Edge(from = parsed.from, to = parsed.to)
                edges += e
                patches += IrPatch.AddEdge(e)
            }
            is StmtParse.Error -> {
                val diag = Diagnostic(Severity.ERROR, parsed.message, "MMD-E001")
                diagnostics += diag
                patches += IrPatch.AddDiagnostic(diag)
            }
        }
        return IrPatchBatch(seq, patches)
    }

    /** Build a fresh [GraphIR] from accumulated state. */
    public fun snapshot(): GraphIR = GraphIR(
        nodes = nodes.toList(),
        edges = edges.toList(),
        sourceLanguage = SourceLanguage.MERMAID,
        styleHints = StyleHints(direction = direction),
    )

    public fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    // --- internals ---

    private fun parseHeader(line: List<Token>, out: MutableList<IrPatch>) {
        val errs = mutableListOf<String>()
        val first = line.firstOrNull { it.kind != MermaidTokenKind.COMMENT }
        if (first == null || first.kind != MermaidTokenKind.KEYWORD_HEADER) {
            errs += "Expected 'flowchart' or 'graph' header"
        } else {
            val rest = line.drop(line.indexOf(first) + 1).filter { it.kind != MermaidTokenKind.COMMENT }
            val dirTok = rest.firstOrNull()
            if (dirTok == null || dirTok.kind != MermaidTokenKind.DIRECTION) {
                errs += "Expected direction (TD/TB/LR/RL/BT) after '${first.text}'"
            } else {
                direction = when (dirTok.text.toString()) {
                    "TD", "TB" -> Direction.TB
                    "LR" -> Direction.LR
                    "RL" -> Direction.RL
                    "BT" -> Direction.BT
                    else -> Direction.TB
                }
                headerSeen = true
            }
        }
        for (msg in errs) {
            val d = Diagnostic(Severity.ERROR, msg, "MMD-E000")
            diagnostics += d
            out += IrPatch.AddDiagnostic(d)
        }
    }

    private fun registerNode(id: NodeId, label: String?, out: MutableList<IrPatch>) {
        if (id in knownNodes) {
            // If the second mention provides a label we didn't have, an UpdateAttr could be
            // added later; current subset treats labels as fixed-on-first-mention.
            return
        }
        knownNodes += id
        val node = Node(
            id = id,
            label = if (label != null) RichLabel.Plain(label) else RichLabel.Empty,
        )
        nodes += node
        out += IrPatch.AddNode(node)
    }

    private fun parseStatement(line: List<Token>): StmtParse {
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return StmtParse.Error("Empty statement")
        val errors = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errors != null) {
            return StmtParse.Error("Lex error at ${errors.start}: ${errors.text}")
        }

        val first = toks[0]
        if (first.kind != MermaidTokenKind.IDENT) {
            return StmtParse.Error("Statement must start with an identifier (got ${MermaidTokenKind.nameOf(first.kind)} '${first.text}')")
        }
        val fromId = NodeId(first.text.toString())
        var i = 1
        var fromLabel: String? = null
        if (i < toks.size && toks[i].kind == MermaidTokenKind.LABEL) {
            fromLabel = toks[i].text.toString()
            i++
        }
        if (i >= toks.size) {
            return StmtParse.NodeDecl(fromId, fromLabel)
        }
        if (toks[i].kind != MermaidTokenKind.ARROW_SOLID) {
            return StmtParse.Error("Expected '-->' after '${first.text}', got '${toks[i].text}'")
        }
        i++
        if (i >= toks.size || toks[i].kind != MermaidTokenKind.IDENT) {
            return StmtParse.Error("Expected target identifier after '-->'")
        }
        val toId = NodeId(toks[i].text.toString())
        i++
        var toLabel: String? = null
        if (i < toks.size && toks[i].kind == MermaidTokenKind.LABEL) {
            toLabel = toks[i].text.toString()
            i++
        }
        if (i < toks.size) {
            return StmtParse.Error("Trailing token '${toks[i].text}' after edge")
        }
        return StmtParse.EdgeDecl(fromId, fromLabel, toId, toLabel)
    }

    private sealed interface StmtParse {
        data class NodeDecl(val id: NodeId, val label: String?) : StmtParse
        data class EdgeDecl(
            val from: NodeId,
            val fromLabel: String?,
            val to: NodeId,
            val toLabel: String?,
        ) : StmtParse
        data class Error(val message: String) : StmtParse
    }
}
