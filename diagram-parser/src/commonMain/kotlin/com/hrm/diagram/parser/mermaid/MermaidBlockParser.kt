package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Cluster
import com.hrm.diagram.core.ir.ClusterStyle
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.EdgeStyle
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NodeShape
import com.hrm.diagram.core.ir.NodeStyle
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

data class BlockCellPlacement(
    val id: NodeId,
    val row: Int,
    val col: Int,
    val span: Int = 1,
    val parent: NodeId? = null,
)

/**
 * Streaming parser for Mermaid `block-beta`.
 *
 * Supported subset:
 * - `block-beta`
 * - `columns N`
 * - flat rows (`A B C`)
 * - span syntax (`b:2`, `space:3`, `block:group:2`)
 * - nested block / `end`
 * - shapes (`[]`, `()`, `[()]`, `(())`, `{{}}`, `{}`, `[[]]`, `[//]`, `[\\]`)
 * - block arrows (`id<["txt"]>(right|left|up|down|x|y)`)
 * - edges with optional labels (`A --> B`, `A -- "x" --> B`)
 */
class MermaidBlockParser {
    companion object {
        const val KIND_KEY = "mermaid.block.kind"
        const val SPAN_KEY = "mermaid.block.span"
        const val ROW_KEY = "mermaid.block.row"
        const val COL_KEY = "mermaid.block.col"
        const val PARENT_KEY = "mermaid.block.parent"
        const val ARROW_DIR_KEY = "mermaid.block.arrowDir"
    }

    private data class OpenBlock(
        val id: NodeId,
        val title: String,
        val span: Int,
        val parent: NodeId?,
        var columns: Int = 0,
        var row: Int = 0,
        var col: Int = 0,
        val childNodeIds: MutableList<NodeId> = ArrayList(),
        val childBlockIds: MutableList<NodeId> = ArrayList(),
    )

    private data class PendingEdge(
        val from: NodeId,
        val to: NodeId,
        val label: String?,
        val arrow: ArrowEnds,
    )

    private data class ParsedItem(
        val id: String,
        val label: String,
        val shape: NodeShape,
        val span: Int,
        val kind: String = "node",
        val extras: Map<String, String> = emptyMap(),
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val placements: LinkedHashMap<NodeId, BlockCellPlacement> = LinkedHashMap()
    private val edges: MutableList<Edge> = ArrayList()
    private val blocks: LinkedHashMap<NodeId, OpenBlock> = LinkedHashMap()
    private val blockStack: MutableList<OpenBlock> = ArrayList()
    private val pendingEdges: MutableList<PendingEdge> = ArrayList()
    private var headerSeen = false
    private var seq: Long = 0
    private var autoBlockSeq = 0
    private var autoNodeSeq = 0
    private var title: String? = null

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())
        val lexErr = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (lexErr != null) return errorBatch("Lex error at ${lexErr.start}: ${lexErr.text}")

        if (!headerSeen) {
            val first = toks.first()
            if (first.kind == MermaidTokenKind.BLOCK_HEADER) {
                headerSeen = true
                ensureRootBlock()
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'block-beta' header")
        }

        val text = toks.joinToString(" ") { it.text.toString() }.trim()
        if (text.isBlank()) return IrPatchBatch(seq, emptyList())

        val patches = ArrayList<IrPatch>()
        when {
            text == "end" -> closeBlock(patches)
            text.startsWith("title ") -> title = unquote(text.removePrefix("title").trim())
            text.startsWith("columns ") -> parseColumns(text.removePrefix("columns").trim(), patches)
            text.startsWith("block") && text.endsWith("{").not() && isBlockStart(text) -> openBlock(text, patches)
            looksLikeEdge(text) -> parseEdge(text, patches)
            else -> parseRow(text, patches)
        }
        flushPendingEdges(patches)
        return IrPatchBatch(seq, patches)
    }

    fun snapshot(): GraphIR =
        GraphIR(
            nodes = nodes.values.toList(),
            edges = edges.toList(),
            clusters = buildClusters(parent = null),
            title = title,
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(direction = Direction.LR, extras = buildExtras()),
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    fun placementSnapshot(): Map<NodeId, BlockCellPlacement> = placements.toMap()

    private fun ensureRootBlock(): OpenBlock {
        val existing = blockStack.firstOrNull()
        if (existing != null) return existing
        val root = OpenBlock(id = NodeId("__block_root__"), title = "", span = 1, parent = null, columns = 1)
        blockStack += root
        return root
    }

    private fun currentBlock(): OpenBlock = blockStack.lastOrNull() ?: ensureRootBlock()

    private fun parseColumns(spec: String, out: MutableList<IrPatch>) {
        val value = spec.toIntOrNull()
        if (value == null || value <= 0) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid block columns value", "MERMAID-E214")
            out += IrPatch.AddDiagnostic(diagnostics.last())
            return
        }
        currentBlock().columns = value
    }

    private fun openBlock(text: String, out: MutableList<IrPatch>) {
        val item = parseBlockStart(text) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid block start syntax", "MERMAID-E214")
            out += IrPatch.AddDiagnostic(diagnostics.last())
            return
        }
        val parent = currentBlock()
        val id = NodeId(item.id)
        val block = OpenBlock(
            id = id,
            title = item.label.ifBlank { item.id },
            span = item.span.coerceAtLeast(1),
            parent = parent.id.takeUnless { it == NodeId("__block_root__") },
            columns = 0,
        )
        placeInCurrent(block.id, block.span, block.parent)
        parent.childBlockIds += block.id
        blocks[id] = block
        blockStack += block
    }

    private fun closeBlock(out: MutableList<IrPatch>) {
        if (blockStack.size <= 1) {
            diagnostics += Diagnostic(Severity.ERROR, "Unexpected 'end' in block diagram", "MERMAID-E214")
            out += IrPatch.AddDiagnostic(diagnostics.last())
            return
        }
        blockStack.removeAt(blockStack.lastIndex)
    }

    private fun parseRow(text: String, out: MutableList<IrPatch>) {
        val items = splitRowItems(text).mapNotNull { parseItem(it, out) }
        items.forEach { item ->
            if (item.kind == "space") {
                placeInCurrent(NodeId("__space_${autoNodeSeq++}"), item.span, currentBlock().id.takeUnless { it.value == "__block_root__" }, visible = false)
            } else {
                val id = NodeId(item.id)
                val node = Node(
                    id = id,
                    label = RichLabel.Plain(item.label.ifBlank { item.id }),
                    shape = item.shape,
                    style = defaultNodeStyle(item.kind),
                    payload = buildMap {
                        put(KIND_KEY, item.kind)
                        put(SPAN_KEY, item.span.toString())
                        currentBlock().id.takeUnless { it.value == "__block_root__" }?.let { put(PARENT_KEY, it.value) }
                        putAll(item.extras)
                    },
                )
                nodes[id] = node
                currentBlock().childNodeIds += id
                val placement = placeInCurrent(id, item.span, currentBlock().id.takeUnless { it.value == "__block_root__" })
                node.payload.plus(ROW_KEY to placement.row.toString())
                out += IrPatch.AddNode(node)
            }
        }
    }

    private fun parseItem(raw: String, out: MutableList<IrPatch>): ParsedItem? {
        val token = raw.trim()
        if (token.isBlank()) return null
        if (token == "space" || token.startsWith("space:")) {
            val span = token.substringAfter(':', "1").toIntOrNull()?.coerceAtLeast(1) ?: 1
            return ParsedItem(id = "__space_$autoNodeSeq", label = "", shape = NodeShape.Box, span = span, kind = "space")
        }
        parseArrowItem(token)?.let { return it }
        return parseSimpleNodeItem(token)
    }

    private fun parseSimpleNodeItem(token: String): ParsedItem? {
        val (head, span) = parseSpan(token)
        val trimmed = head.trim()
        if (trimmed.startsWith("block:")) {
            return parseBlockStart(trimmed)
        }
        val id = leadingIdentifier(trimmed)
        val shapePart = trimmed.removePrefix(id).trim()
        val (label, shape) = parseLabelAndShape(id, shapePart)
        return ParsedItem(id = id, label = label, shape = shape, span = span)
    }

    private fun parseBlockStart(token: String): ParsedItem? {
        val body = token.removePrefix("block").removePrefix(":").trim()
        if (body.isBlank()) {
            val id = "block_${autoBlockSeq++}"
            return ParsedItem(id = id, label = id, shape = NodeShape.Package, span = 1, kind = "block")
        }
        val parts = body.split(':')
        val id = parts.getOrNull(0)?.trim().takeUnless { it.isNullOrEmpty() } ?: "block_${autoBlockSeq++}"
        val maybeSpan = parts.getOrNull(1)?.trim()
        val span = maybeSpan?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return ParsedItem(id = id, label = id, shape = NodeShape.Package, span = span, kind = "block")
    }

    private fun parseArrowItem(token: String): ParsedItem? {
        val open = token.indexOf('<')
        val dirStart = token.lastIndexOf('(')
        val dirEnd = token.lastIndexOf(')')
        if (open <= 0 || dirStart <= open || dirEnd <= dirStart) return null
        val id = token.substring(0, open).trim().ifBlank { "arrow_${autoNodeSeq++}" }
        val labelPart = token.substring(open + 1, dirStart).trim()
        val dirPart = token.substring(dirStart + 1, dirEnd).trim()
        val label = parseArrowLabel(labelPart)
        return ParsedItem(
            id = id,
            label = label,
            shape = NodeShape.Custom("block-arrow"),
            span = 1,
            kind = "arrow",
            extras = mapOf(ARROW_DIR_KEY to dirPart),
        )
    }

    private fun parseArrowLabel(text: String): String {
        val content = text.removePrefix("[").removeSuffix("]").trim()
        return unquote(content).replace("&nbsp;", " ").trim()
    }

    private fun parseLabelAndShape(id: String, shapePart: String): Pair<String, NodeShape> {
        val raw = shapePart.trim()
        return when {
            raw.startsWith("[[") && raw.endsWith("]]") -> unquote(raw.substring(2, raw.length - 2)) to NodeShape.Subroutine
            raw.startsWith("(((") && raw.endsWith(")))") -> unquote(raw.substring(3, raw.length - 3)) to NodeShape.EndCircle
            raw.startsWith("[(") && raw.endsWith(")]") -> unquote(raw.substring(2, raw.length - 2)) to NodeShape.Cylinder
            raw.startsWith("((") && raw.endsWith("))") -> unquote(raw.substring(2, raw.length - 2)) to NodeShape.Circle
            raw.startsWith("{{") && raw.endsWith("}}") -> unquote(raw.substring(2, raw.length - 2)) to NodeShape.Hexagon
            raw.startsWith("{") && raw.endsWith("}") -> unquote(raw.substring(1, raw.length - 1)) to NodeShape.Diamond
            raw.startsWith(">") && raw.endsWith("]") -> unquote(raw.substring(1, raw.length - 1)) to NodeShape.Custom("asymmetric")
            raw.startsWith("([") && raw.endsWith("])") -> unquote(raw.substring(2, raw.length - 2)) to NodeShape.Stadium
            raw.startsWith("(") && raw.endsWith(")") -> unquote(raw.substring(1, raw.length - 1)) to NodeShape.RoundedBox
            raw.startsWith("[/") && raw.endsWith("/]") -> unquote(raw.substring(2, raw.length - 2)) to NodeShape.Parallelogram
            raw.startsWith("[\\") && raw.endsWith("\\]") -> unquote(raw.substring(2, raw.length - 2)) to NodeShape.Parallelogram
            raw.startsWith("[\\") && raw.endsWith("/]") -> unquote(raw.substring(2, raw.length - 2)) to NodeShape.Trapezoid
            raw.startsWith("[/") && raw.endsWith("\\]") -> unquote(raw.substring(2, raw.length - 2)) to NodeShape.Trapezoid
            raw.startsWith("[\"") && raw.endsWith("\"]") -> unquote(raw.substring(2, raw.length - 2)) to NodeShape.Box
            raw.startsWith("[") && raw.endsWith("]") -> unquote(raw.substring(1, raw.length - 1)) to NodeShape.Box
            raw.isNotBlank() -> unquote(raw) to NodeShape.Box
            else -> id to NodeShape.Box
        }
    }

    private fun parseEdge(text: String, out: MutableList<IrPatch>) {
        val targetSplit = when {
            text.contains("-->") -> Triple("-->", ArrowEnds.ToOnly, text.lastIndexOf("-->"))
            text.contains("---") -> Triple("---", ArrowEnds.None, text.lastIndexOf("---"))
            else -> null
        }
        if (targetSplit == null || targetSplit.third <= 0) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid block edge syntax", "MERMAID-E214")
            out += IrPatch.AddDiagnostic(diagnostics.last())
            return
        }
        val (operator, arrow, splitIndex) = targetSplit
        val left = text.substring(0, splitIndex).trim()
        val right = text.substring(splitIndex + operator.length).trim()
        val to = right.takeIf { it.matches(Regex("""[A-Za-z0-9_:-]+""")) }?.let(::NodeId)
        if (to == null) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid block edge target", "MERMAID-E214")
            out += IrPatch.AddDiagnostic(diagnostics.last())
            return
        }
        val labelRegex = Regex("""^([A-Za-z0-9_:-]+)\s*--\s*"([^"]*)"$""")
        val plainLabelRegex = Regex("""^([A-Za-z0-9_:-]+)\s*--\s*(.+)$""")
        val plainRegex = Regex("""^([A-Za-z0-9_:-]+)$""")
        val from: NodeId
        val label: String?
        val labeled = labelRegex.matchEntire(left)
        if (labeled != null) {
            from = NodeId(labeled.groupValues[1])
            label = labeled.groupValues[2].takeIf { it.isNotBlank() }
        } else {
            val plainLabeled = plainLabelRegex.matchEntire(left)
            if (plainLabeled != null) {
                from = NodeId(plainLabeled.groupValues[1])
                label = plainLabeled.groupValues[2].trim().takeIf { it.isNotBlank() }
            } else {
                val plain = plainRegex.matchEntire(left.removeSuffix("--").trim())
                if (plain == null) {
                    diagnostics += Diagnostic(Severity.ERROR, "Invalid block edge syntax", "MERMAID-E214")
                    out += IrPatch.AddDiagnostic(diagnostics.last())
                    return
                }
                from = NodeId(plain.groupValues[1])
                label = null
            }
        }
        val pending = PendingEdge(from = from, to = to, label = label, arrow = arrow)
        if (from in nodes && to in nodes) {
            registerEdge(pending, out)
        } else {
            pendingEdges += pending
        }
    }

    private fun registerEdge(pending: PendingEdge, out: MutableList<IrPatch>) {
        val edge = Edge(
            from = pending.from,
            to = pending.to,
            label = pending.label?.let { RichLabel.Plain(it) },
            arrow = pending.arrow,
            style = EdgeStyle(color = ArgbColor(0xFF546E7A.toInt()), width = 1.5f),
        )
        if (edges.any { it.from == edge.from && it.to == edge.to && (it.label as? RichLabel.Plain)?.text == pending.label }) return
        edges += edge
        out += IrPatch.AddEdge(edge)
    }

    private fun flushPendingEdges(out: MutableList<IrPatch>) {
        val ready = pendingEdges.filter { it.from in nodes && it.to in nodes }
        if (ready.isEmpty()) return
        pendingEdges.removeAll(ready)
        ready.forEach { registerEdge(it, out) }
    }

    private fun placeInCurrent(id: NodeId, span: Int, parent: NodeId?, visible: Boolean = true): BlockCellPlacement {
        val block = currentBlock()
        if (block.columns <= 0) block.columns = 1
        if (block.col + span > block.columns) {
            block.row++
            block.col = 0
        }
        val placement = BlockCellPlacement(id = id, row = block.row, col = block.col, span = span, parent = parent)
        if (visible) placements[id] = placement
        block.col += span
        if (block.col >= block.columns) {
            block.row++
            block.col = 0
        }
        return placement
    }

    private fun buildClusters(parent: NodeId?): List<Cluster> {
        return blocks.values
            .filter { it.parent == parent && it.id.value != "__block_root__" }
            .map { block ->
                Cluster(
                    id = block.id,
                    label = RichLabel.Plain(block.title),
                    children = block.childNodeIds.toList(),
                    nestedClusters = buildClusters(block.id),
                    style = ClusterStyle(
                        fill = ArgbColor(0xFFF8FBFF.toInt()),
                        stroke = ArgbColor(0xFF90A4AE.toInt()),
                        strokeWidth = 1.5f,
                    ),
                )
            }
    }

    private fun buildExtras(): Map<String, String> = buildMap {
        put("block.columns.root", ensureRootBlock().columns.toString())
        for ((id, placement) in placements) {
            put("block.place.${id.value}", "${placement.row},${placement.col},${placement.span}")
        }
        for ((id, block) in blocks) {
            put("block.columns.${id.value}", block.columns.toString())
        }
    }

    private fun defaultNodeStyle(kind: String): NodeStyle = when (kind) {
        "arrow" -> NodeStyle(
            fill = ArgbColor(0xFFE8F5E9.toInt()),
            stroke = ArgbColor(0xFF43A047.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF1B5E20.toInt()),
        )
        else -> NodeStyle(
            fill = ArgbColor(0xFFE3F2FD.toInt()),
            stroke = ArgbColor(0xFF1E88E5.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF0D47A1.toInt()),
        )
    }

    private fun parseSpan(token: String): Pair<String, Int> {
        val lastColon = token.lastIndexOf(':')
        if (lastColon <= 0 || lastColon == token.lastIndex) return token to 1
        val span = token.substring(lastColon + 1).trim().toIntOrNull()
        return if (span != null) token.substring(0, lastColon) to span.coerceAtLeast(1) else token to 1
    }

    private fun leadingIdentifier(text: String): String {
        val idx = text.indexOfAny(charArrayOf('[', '(', '{', '<', '>', ' '))
        return if (idx <= 0) text else text.substring(0, idx)
    }

    private fun splitRowItems(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var depthSquare = 0
        var depthRound = 0
        var depthBrace = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"' && (i == 0 || text[i - 1] != '\\') && shouldTrackQuote(cur, depthSquare, depthRound, depthBrace)) {
                inString = !inString
                cur.append(ch)
            } else if (!inString && ch == '[') {
                depthSquare++; cur.append(ch)
            } else if (!inString && ch == ']') {
                depthSquare = (depthSquare - 1).coerceAtLeast(0); cur.append(ch)
            } else if (!inString && ch == '(') {
                depthRound++; cur.append(ch)
            } else if (!inString && ch == ')') {
                depthRound = (depthRound - 1).coerceAtLeast(0); cur.append(ch)
            } else if (!inString && ch == '{') {
                depthBrace++; cur.append(ch)
            } else if (!inString && ch == '}') {
                depthBrace = (depthBrace - 1).coerceAtLeast(0); cur.append(ch)
            } else if (!inString && depthSquare == 0 && depthRound == 0 && depthBrace == 0 && ch.isWhitespace()) {
                if (cur.isNotEmpty()) {
                    out += cur.toString()
                    cur.clear()
                }
            } else {
                cur.append(ch)
            }
            i++
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun shouldTrackQuote(cur: CharSequence, depthSquare: Int, depthRound: Int, depthBrace: Int): Boolean {
        val current = cur.toString()
        return depthSquare == 0 && depthRound == 0 && depthBrace == 0 && current.contains('>') && !current.contains(']')
    }

    private fun isBlockStart(text: String): Boolean = text == "block" || text.startsWith("block:")

    private fun looksLikeEdge(text: String): Boolean =
        text.contains("-->") || text.contains("---")

    private fun unquote(text: String): String {
        val trimmed = text.trim()
        val unwrapped = if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.lastIndex)
        } else {
            trimmed
        }
        return unwrapped
            .replace("\\\"", "\"")
            .replace("&nbsp;", " ")
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val diagnostic = Diagnostic(Severity.ERROR, message, "MERMAID-E214")
        diagnostics += diagnostic
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(diagnostic)))
    }
}
