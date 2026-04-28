package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.ir.TreeIR
import com.hrm.diagram.core.ir.TreeNode
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `mindmap`.
 *
 * Supported subset:
 * - `mindmap` header
 * - indentation-based hierarchy
 * - node forms:
 *   - plain text
 *   - `id[label]`
 *   - `id(label)`
 *   - `id((label))`
 *   - `id))label((`
 *   - `id)label(`
 *   - `id{{label}}`
 * - `::icon(...)` lines are parsed as icon nodes
 * - `:::class ...` lines are ignored with `MERMAID-W010`
 *
 * Notes:
 * - Mermaid class-based styling is not representable in current `TreeIR`; we warn and ignore.
 * - Per-node shapes are stored alongside the parser state for render use; `TreeIR` remains shape-less.
 */
class MermaidMindmapParser {
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var seq: Long = 0
    private var headerSeen = false
    private var autoId = 0

    private data class MutableMindNode(
        val id: NodeId,
        var label: String,
        var shape: NodeShape = NodeShape.Box,
        var iconName: String? = null,
        val children: MutableList<MutableMindNode> = ArrayList(),
    )

    private var root: MutableMindNode? = null
    private val stack: MutableList<Pair<Int, MutableMindNode>> = ArrayList()
    private var lastNode: MutableMindNode? = null

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())
        val errTok = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errTok != null) return errorBatch("Lex error at ${errTok.start}: ${errTok.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.MINDMAP_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'mindmap' header")
        }

        val indent = if (toks.firstOrNull()?.kind == MermaidTokenKind.INDENT) toks.first().text.toString().toIntOrNull() ?: 0 else 0
        val content = if (indent > 0) toks.drop(1) else toks
        if (content.isEmpty()) return IrPatchBatch(seq, emptyList())

        // :::class1 class2
        if (content.size >= 3 &&
            content[0].kind == MermaidTokenKind.COLON &&
            content[1].kind == MermaidTokenKind.COLON &&
            content[2].kind == MermaidTokenKind.COLON
        ) {
            diagnostics += Diagnostic(Severity.WARNING, "mindmap ::: classes are ignored in current renderer", "MERMAID-W010")
            return IrPatchBatch(seq, emptyList())
        }

        val parsed = parseNodeContent(content) ?: return errorBatch("Invalid mindmap node line")
        attachNode(indent, parsed)
        lastNode = parsed
        return IrPatchBatch(seq, emptyList())
    }

    fun snapshot(): TreeIR {
        val r = root ?: MutableMindNode(NodeId("mindmap_root"), "mindmap")
        return TreeIR(
            root = freeze(r),
            title = null,
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(),
        )
    }

    fun snapshotNodeShapes(): Map<NodeId, NodeShape> {
        val out = LinkedHashMap<NodeId, NodeShape>()
        fun walk(n: MutableMindNode) {
            out[n.id] = n.shape
            n.children.forEach(::walk)
        }
        root?.let(::walk)
        return out
    }

    fun snapshotNodeIcons(): Map<NodeId, String> {
        val out = LinkedHashMap<NodeId, String>()
        fun walk(n: MutableMindNode) {
            n.iconName?.let { out[n.id] = it }
            n.children.forEach(::walk)
        }
        root?.let(::walk)
        return out
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun attachNode(indent: Int, node: MutableMindNode) {
        if (root == null) {
            root = node
            stack.clear()
            stack += indent to node
            return
        }
        while (stack.isNotEmpty() && indent <= stack.last().first) stack.removeAt(stack.lastIndex)
        val parent = stack.lastOrNull()?.second ?: root!!
        parent.children += node
        stack += indent to node
    }

    private fun parseNodeContent(toks: List<Token>): MutableMindNode? {
        // ::icon(...)
        if (toks.size >= 4 &&
            toks[0].kind == MermaidTokenKind.COLON &&
            toks[1].kind == MermaidTokenKind.COLON &&
            toks[2].kind == MermaidTokenKind.IDENT &&
            toks[2].text.toString() == "icon" &&
            toks[3].kind == MermaidTokenKind.LABEL_PAREN
        ) {
            return MutableMindNode(
                id = NodeId(nextAutoId("icon")),
                label = summarizeIconLabel(toks[3].text.toString()),
                shape = NodeShape.Circle,
                iconName = normalizeLabel(toks[3].text.toString()),
            )
        }

        // id[label] / id(label) / id((label)) / id))label(( / id)label( / id{{label}}
        if (toks.size >= 2 && toks[0].kind == MermaidTokenKind.IDENT) {
            val id = NodeId(toks[0].text.toString())
            val second = toks[1]
            when (second.kind) {
                MermaidTokenKind.LABEL -> return MutableMindNode(id, normalizeLabel(second.text.toString()), NodeShape.Box)
                MermaidTokenKind.LABEL_PAREN -> return MutableMindNode(id, normalizeLabel(second.text.toString()), NodeShape.RoundedBox)
                MermaidTokenKind.LABEL_DOUBLE_PAREN -> return MutableMindNode(id, normalizeLabel(second.text.toString()), NodeShape.Circle)
                MermaidTokenKind.LABEL_BANG -> return MutableMindNode(id, normalizeLabel(second.text.toString()), NodeShape.Custom("bang"))
                MermaidTokenKind.LABEL_CLOUD -> return MutableMindNode(id, normalizeLabel(second.text.toString()), NodeShape.Cloud)
                MermaidTokenKind.LABEL_BRACE -> {
                    val raw = second.text.toString()
                    val inner = raw.removePrefix("{").removeSuffix("}")
                    return MutableMindNode(id, normalizeLabel(inner), if (raw.startsWith("{") && raw.endsWith("}")) NodeShape.Hexagon else NodeShape.Diamond)
                }
            }
        }

        // Plain text node.
        val label = normalizeLabel(joinText(toks))
        if (label.isBlank()) return null
        return MutableMindNode(
            id = NodeId(nextAutoId(label)),
            label = label,
            shape = NodeShape.Box,
        )
    }

    private fun joinText(toks: List<Token>): String =
        buildString {
            var first = true
            for (t in toks) {
                val needsSpace = when (t.kind) {
                    MermaidTokenKind.COLON, MermaidTokenKind.COMMA, MermaidTokenKind.RPAREN, MermaidTokenKind.RBRACE -> false
                    else -> !first
                }
                if (needsSpace) append(' ')
                append(t.text.toString())
                first = false
            }
        }.trim()

    private fun normalizeLabel(raw: String): String {
        return raw
            .trim()
            .removeSurrounding("`")
            .replace("<br>", "\n", ignoreCase = true)
            .replace("**", "")
            .replace("*", "")
    }

    private fun summarizeIconLabel(raw: String): String {
        val normalized = normalizeLabel(raw)
        val parts = normalized.split(' ').map { it.trim() }.filter { it.isNotEmpty() }
        val tail = parts.lastOrNull().orEmpty()
        return tail.removePrefix("fa-").removePrefix("mdi-").ifBlank { "icon" }
    }

    private fun freeze(n: MutableMindNode): TreeNode =
        TreeNode(
            id = n.id,
            label = RichLabel.Plain(n.label),
            children = n.children.map(::freeze),
        )

    private fun nextAutoId(seed: String): String {
        autoId++
        val base = seed.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (base.isBlank()) "mind_$autoId" else "${base}_$autoId"
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E204")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
