package com.hrm.diagram.parser.dot

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Cluster
import com.hrm.diagram.core.ir.ClusterStyle
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.EdgeKind
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

/**
 * Parser for the Phase 6 Graphviz DOT subset.
 *
 * Supported today: `strict`, `graph` / `digraph`, node and edge statements, edge chains,
 * graph/node/edge attributes, `subgraph cluster_*`, comments, quoted IDs, and HTML-like labels
 * as text-preserving labels. Unsupported attributes are preserved in payload maps.
 */
@DiagramApi
class DotParser {
    data class Result(
        val ir: GraphIR,
        val diagnostics: List<Diagnostic>,
    )

    private data class Token(val kind: Kind, val text: String, val offset: Int)
    private enum class Kind { Id, LBrace, RBrace, LBracket, RBracket, Equal, Semi, Comma, Colon, EdgeDirected, EdgeUndirected }

    private data class ParseContext(
        val clusterId: NodeId?,
        val children: MutableList<NodeId> = ArrayList(),
        val clusters: MutableList<Cluster> = ArrayList(),
        val graphAttrs: MutableMap<String, String> = LinkedHashMap(),
        val nodeAttrs: MutableMap<String, String> = LinkedHashMap(),
        val edgeAttrs: MutableMap<String, String> = LinkedHashMap(),
        var label: String? = null,
    )

    private data class NodeRef(val id: String, val port: String? = null, val compass: String? = null)
    private data class HtmlLabel(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val fontName: String? = null,
        val fontSize: String? = null,
        val fontColor: String? = null,
    )

    private class ParserState(
        var tokens: List<Token>,
        val diagnostics: MutableList<Diagnostic>,
    ) {
        var index: Int = 0
        val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
        val edges: MutableList<Edge> = ArrayList()
        val rankGroups: MutableList<Pair<String, List<NodeId>>> = ArrayList()
        val rootGraphAttrs: MutableMap<String, String> = LinkedHashMap()
        val rootNodeAttrs: MutableMap<String, String> = LinkedHashMap()
        val rootEdgeAttrs: MutableMap<String, String> = LinkedHashMap()
        var directed: Boolean = true
        var title: String? = null
        var seq: Int = 0

        fun peek(): Token? = tokens.getOrNull(index)
        fun take(): Token? = tokens.getOrNull(index++)
        fun match(kind: Kind): Boolean {
            if (peek()?.kind != kind) return false
            index++
            return true
        }
    }

    fun parse(source: String): Result {
        val diagnostics = ArrayList<Diagnostic>()
        val state = ParserState(tokenize(source, diagnostics), diagnostics)
        val clusters = parseGraph(state)
        val warnings = unsupportedLayoutWarnings(state.rootGraphAttrs)
        return buildResult(state, clusters, state.diagnostics + warnings)
    }

    fun incrementalSession(): IncrementalSession = IncrementalSession()

    inner class IncrementalSession {
        private val diagnostics = ArrayList<Diagnostic>()
        private val state = ParserState(emptyList(), diagnostics)
        private val root = ParseContext(
            clusterId = null,
            graphAttrs = state.rootGraphAttrs,
            nodeAttrs = state.rootNodeAttrs,
            edgeAttrs = state.rootEdgeAttrs,
        )
        private val splitter = DotStatementSplitter()
        private var headerParsed: Boolean = false
        private var closed: Boolean = false

        fun feed(chunk: CharSequence, eos: Boolean = false): Result {
            if (!closed) {
                for (unit in splitter.feed(chunk, eos)) {
                    if (!headerParsed) {
                        parseHeader(unit)
                    } else {
                        parseIncrementalStatement(unit)
                    }
                }
                if (eos && splitter.pendingIsNotBlank()) {
                    parseIncrementalStatement(splitter.drainPending())
                }
            }
            val warnings = unsupportedLayoutWarnings(state.rootGraphAttrs)
            return buildResult(state, root.clusters.toList(), diagnostics + warnings)
        }

        fun reset() {
            diagnostics.clear()
            state.tokens = emptyList()
            state.index = 0
            state.nodes.clear()
            state.edges.clear()
            state.rankGroups.clear()
            state.rootGraphAttrs.clear()
            state.rootNodeAttrs.clear()
            state.rootEdgeAttrs.clear()
            state.directed = true
            state.title = null
            state.seq = 0
            root.children.clear()
            root.clusters.clear()
            root.graphAttrs.clear()
            root.nodeAttrs.clear()
            root.edgeAttrs.clear()
            splitter.reset()
            headerParsed = false
            closed = false
        }

        private fun parseHeader(unit: String) {
            setTokens(unit)
            if (state.peek()?.text.equals("strict", ignoreCase = true)) state.take()
            val graphKeyword = state.peek()?.text?.lowercase()
            if (graphKeyword == "digraph" || graphKeyword == "graph") {
                state.directed = graphKeyword == "digraph"
                state.take()
            } else {
                state.error("DOT source must start with graph or digraph")
                return
            }
            if (state.peek()?.kind == Kind.Id && state.tokens.getOrNull(state.index + 1)?.kind == Kind.LBrace) {
                state.title = decodeLabel(state.take()?.text.orEmpty())
            }
            if (!state.match(Kind.LBrace)) {
                state.error("DOT graph is missing opening brace")
                return
            }
            headerParsed = true
        }

        private fun parseIncrementalStatement(unit: String) {
            val trimmed = unit.trim()
            if (trimmed.isEmpty()) return
            if (trimmed == "}") {
                closed = true
                return
            }
            setTokens(trimmed)
            while (state.peek() != null) {
                if (state.peek()?.kind == Kind.RBrace) {
                    state.take()
                    closed = true
                    continue
                }
                parseStatement(state, root)
                state.match(Kind.Semi)
                state.match(Kind.Comma)
            }
        }

        private fun setTokens(source: String) {
            state.tokens = tokenize(source, diagnostics)
            state.index = 0
        }
    }

    private fun buildResult(state: ParserState, clusters: List<Cluster>, diagnostics: List<Diagnostic>): Result {
        val graphAttrs = state.rootGraphAttrs
        val title = graphAttrs["label"] ?: state.title
        val direction = when (graphAttrs["rankdir"]?.uppercase()) {
            "LR" -> Direction.LR
            "RL" -> Direction.RL
            "BT" -> Direction.BT
            else -> Direction.TB
        }
        return Result(
            ir = GraphIR(
                nodes = state.nodes.values.toList(),
                edges = state.edges.toList(),
                clusters = clusters,
                title = title,
                sourceLanguage = SourceLanguage.DOT,
                styleHints = StyleHints(
                    direction = direction,
                    extras = buildMap {
                        putAll(graphAttrs.mapKeys { "dot.graph.${it.key}" })
                        state.rankGroups.forEachIndexed { index, group ->
                            put("dot.rank.$index.kind", group.first)
                            put("dot.rank.$index.nodes", group.second.joinToString(",") { it.value })
                        }
                    },
                ),
            ),
            diagnostics = diagnostics,
        )
    }

    private fun parseGraph(state: ParserState): List<Cluster> {
        if (state.peek()?.text.equals("strict", ignoreCase = true)) state.take()
        val graphKeyword = state.peek()?.text?.lowercase()
        if (graphKeyword == "digraph" || graphKeyword == "graph") {
            state.directed = graphKeyword == "digraph"
            state.take()
        } else {
            state.error("DOT source must start with graph or digraph")
            return emptyList()
        }
        if (state.peek()?.kind == Kind.Id && state.tokens.getOrNull(state.index + 1)?.kind == Kind.LBrace) {
            state.title = decodeLabel(state.take()?.text.orEmpty())
        }
        if (!state.match(Kind.LBrace)) {
            state.error("DOT graph is missing opening brace")
            return emptyList()
        }
        val root = ParseContext(
            clusterId = null,
            graphAttrs = state.rootGraphAttrs,
            nodeAttrs = state.rootNodeAttrs,
            edgeAttrs = state.rootEdgeAttrs,
        )
        parseStatementList(state, root)
        if (!state.match(Kind.RBrace)) state.error("DOT graph is missing closing brace")
        return root.clusters.toList()
    }

    private fun parseStatementList(state: ParserState, context: ParseContext) {
        while (true) {
            val token = state.peek() ?: return
            if (token.kind == Kind.RBrace) return
            if (token.kind == Kind.Semi || token.kind == Kind.Comma) {
                state.take()
                continue
            }
            parseStatement(state, context)
            state.match(Kind.Semi)
        }
    }

    private fun parseStatement(state: ParserState, context: ParseContext) {
        val token = state.peek() ?: return
        if (token.text.equals("subgraph", ignoreCase = true) || token.kind == Kind.LBrace) {
            val firstGroup = parseOperandGroup(state, context)
            if (state.peek()?.kind == Kind.EdgeDirected || state.peek()?.kind == Kind.EdgeUndirected) {
                parseEdgeStatement(state, context, firstGroup)
            } else {
                ensureNodes(state, context, firstGroup, context.nodeAttrs)
            }
            return
        }
        if (token.kind != Kind.Id) {
            state.error("Unexpected DOT token: ${token.text}")
            state.take()
            return
        }
        val first = state.take() ?: return
        val keyword = first.text.lowercase()
        if (keyword in setOf("graph", "node", "edge") && state.peek()?.kind == Kind.LBracket) {
            val attrs = parseAttrLists(state)
            when (keyword) {
                "graph" -> context.graphAttrs.putAll(attrs)
                "node" -> context.nodeAttrs.putAll(attrs)
                "edge" -> context.edgeAttrs.putAll(attrs)
            }
            return
        }
        if (state.peek()?.kind == Kind.Equal) {
            state.take()
            val value = expectId(state) ?: return
            context.graphAttrs[first.text.lowercase()] = decodeLabel(value)
            return
        }
        val firstRef = parseNodeRefTail(state, first.text)
        when (state.peek()?.kind) {
            Kind.EdgeDirected, Kind.EdgeUndirected -> parseEdgeStatement(state, context, listOf(firstRef))
            else -> parseNodeStatement(state, context, firstRef)
        }
    }

    private fun parseSubgraph(state: ParserState, parent: ParseContext): List<NodeRef> {
        var name: String? = null
        if (state.peek()?.text.equals("subgraph", ignoreCase = true)) {
            state.take()
            if (state.peek()?.kind == Kind.Id) name = decodeLabel(state.take()?.text.orEmpty())
        }
        if (!state.match(Kind.LBrace)) {
            state.error("DOT subgraph is missing opening brace")
            return emptyList()
        }
        val rawName = name ?: "subgraph_${++state.seq}"
        val isCluster = rawName.startsWith("cluster", ignoreCase = true)
        val clusterId = if (isCluster) NodeId("dot_${slug(rawName)}") else null
        val child = ParseContext(
            clusterId = clusterId,
            graphAttrs = LinkedHashMap(parent.graphAttrs),
            nodeAttrs = LinkedHashMap(parent.nodeAttrs),
            edgeAttrs = LinkedHashMap(parent.edgeAttrs),
            label = rawName,
        )
        parseStatementList(state, child)
        if (!state.match(Kind.RBrace)) state.error("DOT subgraph is missing closing brace")
        parent.graphAttrs.putAll(child.graphAttrs.filterKeys { it in setOf("rank", "rankdir") })
        if (clusterId != null) {
            parent.clusters += Cluster(
                id = clusterId,
                label = RichLabel.Plain(child.graphAttrs["label"] ?: rawName.removePrefix("cluster_")),
                children = child.children.distinct(),
                nestedClusters = child.clusters.toList(),
                style = clusterStyleOf(child.graphAttrs),
            )
        } else {
            parent.children += child.children
            parent.clusters += child.clusters
        }
        return child.children.map { NodeRef(it.value.removePrefix("dot_")) }
    }

    private fun parseNodeStatement(state: ParserState, context: ParseContext, ref: NodeRef) {
        val attrs = LinkedHashMap(context.nodeAttrs)
        attrs.putAll(parseAttrLists(state))
        val node = buildNode(ref.id, attrs)
        state.nodes[node.id] = node
        if (node.id !in context.children) context.children += node.id
    }

    private fun parseEdgeStatement(state: ParserState, context: ParseContext, firstGroup: List<NodeRef>) {
        val groups = ArrayList<List<NodeRef>>()
        groups += firstGroup
        val operators = ArrayList<Kind>()
        while (state.peek()?.kind == Kind.EdgeDirected || state.peek()?.kind == Kind.EdgeUndirected) {
            val op = state.take() ?: break
            operators += op.kind
            groups += parseEdgeOperand(state, context)
        }
        val attrs = LinkedHashMap(context.edgeAttrs)
        attrs.putAll(parseAttrLists(state))
        for (group in groups) ensureNodes(state, context, group, context.nodeAttrs)
        for (i in 0 until groups.lastIndex) {
            val fromGroup = groups[i]
            val toGroup = groups[i + 1]
            for (from in fromGroup) {
                for (to in toGroup) {
                    state.edges += buildEdge(from, to, attrs, operators.getOrNull(i), state.directed)
                }
            }
        }
    }

    private fun ensureNodes(
        state: ParserState,
        context: ParseContext,
        refs: List<NodeRef>,
        attrs: Map<String, String>,
    ) {
        for (ref in refs) {
            val nodeId = nodeId(ref.id)
            if (nodeId !in state.nodes) state.nodes[nodeId] = buildNode(ref.id, attrs)
            if (nodeId !in context.children) context.children += nodeId
        }
    }

    private fun parseEdgeOperand(state: ParserState, context: ParseContext): List<NodeRef> {
        if (state.peek()?.kind == Kind.LBrace || state.peek()?.text.equals("subgraph", ignoreCase = true)) return parseOperandGroup(state, context)
        val id = expectId(state) ?: return listOf(NodeRef("anonymous_${++state.seq}"))
        return listOf(parseNodeRefTail(state, id))
    }

    private fun parseOperandGroup(state: ParserState, context: ParseContext): List<NodeRef> {
        if (state.peek()?.text.equals("subgraph", ignoreCase = true)) return parseSubgraph(state, context)
        if (!state.match(Kind.LBrace)) return emptyList()
        val refs = ArrayList<NodeRef>()
        var rank: String? = null
        while (true) {
            val token = state.peek() ?: break
            if (token.kind == Kind.RBrace) break
            if (token.kind == Kind.Semi || token.kind == Kind.Comma) {
                state.take()
                continue
            }
            if (token.text.equals("subgraph", ignoreCase = true) || token.kind == Kind.LBrace) {
                refs += parseOperandGroup(state, context)
            } else if (token.kind == Kind.Id) {
                val id = state.take()?.text.orEmpty()
                if (state.peek()?.kind == Kind.Equal) {
                    state.take()
                    val value = expectId(state)?.let(::decodeLabel)
                    if (id.equals("rank", ignoreCase = true)) rank = value
                } else {
                    refs += parseNodeRefTail(state, id)
                }
            } else {
                state.error("Unexpected DOT token in node set: ${token.text}")
                state.take()
            }
            state.match(Kind.Semi)
            state.match(Kind.Comma)
        }
        if (!state.match(Kind.RBrace)) state.error("DOT node set is missing closing brace")
        rank?.let { state.rankGroups += it to refs.map { ref -> nodeId(ref.id) } }
        return refs
    }

    private fun parseNodeRefTail(state: ParserState, id: String): NodeRef {
        var port: String? = null
        var compass: String? = null
        if (state.match(Kind.Colon)) {
            port = expectId(state)
            if (state.match(Kind.Colon)) compass = expectId(state)
        }
        return NodeRef(decodeLabel(id), port, compass)
    }

    private fun parseAttrLists(state: ParserState): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        while (state.match(Kind.LBracket)) {
            while (state.peek() != null && state.peek()?.kind != Kind.RBracket) {
                val key = expectId(state)?.lowercase()
                if (key == null) {
                    state.take()
                } else {
                    var value = "true"
                    if (state.match(Kind.Equal)) value = expectId(state)?.let(::decodeAttrValue).orEmpty()
                    out[key] = value
                }
                if (!state.match(Kind.Comma)) state.match(Kind.Semi)
            }
            if (!state.match(Kind.RBracket)) state.error("DOT attribute list is missing closing bracket")
        }
        return out
    }

    private fun expectId(state: ParserState): String? {
        val token = state.take()
        if (token?.kind == Kind.Id) return token.text
        state.error("Expected DOT identifier")
        return null
    }

    private class DotStatementSplitter {
        private val pending = StringBuilder()
        private var headerDone: Boolean = false

        fun feed(chunk: CharSequence, eos: Boolean): List<String> {
            pending.append(chunk)
            val out = ArrayList<String>()
            drain(out)
            if (eos && pending.isNotBlank()) {
                out += pending.toString()
                pending.clear()
            }
            return out
        }

        fun pendingIsNotBlank(): Boolean = pending.isNotBlank()

        fun drainPending(): String {
            val s = pending.toString()
            pending.clear()
            return s
        }

        fun reset() {
            pending.clear()
            headerDone = false
        }

        private fun drain(out: MutableList<String>) {
            while (pending.isNotEmpty()) {
                val end = if (!headerDone) findHeaderEnd() else findStatementEnd()
                if (end == null) return
                val unit = pending.substring(0, end + 1)
                pending.deleteRange(0, end + 1)
                if (!headerDone) headerDone = true
                if (unit.isNotBlank()) out += unit
            }
        }

        private fun findHeaderEnd(): Int? {
            var i = 0
            var inString = false
            var escaped = false
            var htmlDepth = 0
            while (i < pending.length) {
                val c = pending[i]
                if (inString) {
                    escaped = c == '\\' && !escaped
                    if (c == '"' && !escaped) inString = false
                    if (c != '\\') escaped = false
                    i++
                    continue
                }
                if (htmlDepth > 0) {
                    if (c == '<') htmlDepth++
                    if (c == '>') htmlDepth--
                    i++
                    continue
                }
                when {
                    c == '"' -> inString = true
                    c == '<' && pending.getOrNull(i + 1) != '-' -> htmlDepth = 1
                    c == '{' -> return i
                }
                i++
            }
            return null
        }

        private fun findStatementEnd(): Int? {
            var i = 0
            var braceDepth = 0
            var bracketDepth = 0
            var htmlDepth = 0
            var inString = false
            var escaped = false
            var inLineComment = false
            var inBlockComment = false
            while (i < pending.length) {
                val c = pending[i]
                val next = pending.getOrNull(i + 1)
                if (inLineComment) {
                    if (c == '\n') {
                        inLineComment = false
                        if (unitBefore(i).isBlank()) return i
                    }
                    i++
                    continue
                }
                if (inBlockComment) {
                    if (c == '*' && next == '/') {
                        inBlockComment = false
                        i += 2
                    } else {
                        i++
                    }
                    continue
                }
                if (inString) {
                    escaped = c == '\\' && !escaped
                    if (c == '"' && !escaped) inString = false
                    if (c != '\\') escaped = false
                    i++
                    continue
                }
                if (htmlDepth > 0) {
                    if (c == '<') htmlDepth++
                    if (c == '>') htmlDepth--
                    i++
                    continue
                }
                when {
                    c == '/' && next == '/' -> {
                        inLineComment = true
                        i += 2
                        continue
                    }
                    c == '/' && next == '*' -> {
                        inBlockComment = true
                        i += 2
                        continue
                    }
                    c == '"' -> inString = true
                    c == '<' && next != '-' -> htmlDepth = 1
                    c == '[' -> bracketDepth++
                    c == ']' && bracketDepth > 0 -> bracketDepth--
                    c == '{' -> braceDepth++
                    c == '}' -> {
                        if (braceDepth > 0) {
                            braceDepth--
                        } else {
                            return i
                        }
                    }
                    c == ';' && braceDepth == 0 && bracketDepth == 0 -> return i
                    c == '\n' && braceDepth == 0 && bracketDepth == 0 -> {
                        if (unitBefore(i).isNotBlank()) return i
                        return i
                    }
                }
                i++
            }
            return null
        }

        private fun unitBefore(endExclusive: Int): String =
            pending.substring(0, endExclusive)
    }

    private fun buildNode(rawId: String, attrs: Map<String, String>): Node {
        val label = attrs["label"]?.let(::decodeLabel)?.takeIf { it != "\\N" } ?: rawId
        val shape = shapeOf(attrs["shape"], attrs["style"])
        val htmlLabel = attrs["label"]?.let(::htmlLabelOf)
        return Node(
            id = nodeId(rawId),
            label = RichLabel.Plain(label),
            shape = shape,
            style = nodeStyleOf(attrs, shape),
            payload = buildMap {
                putAll(attrs.mapKeys { "dot.node.${it.key}" })
                attrs["label"]?.let { put("dot.node.label", decodeLabel(it)) }
                htmlLabel?.let { putAll(htmlPayload("dot.node.html", it)) }
            },
        )
    }

    private fun buildEdge(from: NodeRef, to: NodeRef, attrs: Map<String, String>, op: Kind?, directedGraph: Boolean): Edge {
        val edgeDirected = op == Kind.EdgeDirected || directedGraph
        val arrowTail = attrs["arrowtail"]?.takeUnless { it.equals("none", ignoreCase = true) } != null
        val arrowHead = attrs["arrowhead"]?.let { !it.equals("none", ignoreCase = true) } ?: edgeDirected
        return Edge(
            from = nodeId(from.id),
            to = nodeId(to.id),
            label = attrs["label"]?.let { RichLabel.Plain(decodeLabel(it)) },
            kind = edgeKindOf(attrs["style"]),
            arrow = when {
                arrowHead && arrowTail -> ArrowEnds.Both
                arrowTail -> ArrowEnds.FromOnly
                arrowHead -> ArrowEnds.ToOnly
                else -> ArrowEnds.None
            },
            style = edgeStyleOf(attrs),
            payload = buildMap {
                putAll(attrs.mapKeys { "dot.edge.${it.key}" })
                attrs["label"]?.let { put("dot.edge.label", decodeLabel(it)) }
                attrs["headlabel"]?.let { put("dot.edge.headlabel", decodeLabel(it)) }
                attrs["taillabel"]?.let { put("dot.edge.taillabel", decodeLabel(it)) }
                attrs["label"]?.let(::htmlLabelOf)?.let { putAll(htmlPayload("dot.edge.html", it)) }
                attrs["headlabel"]?.let(::htmlLabelOf)?.let { putAll(htmlPayload("dot.edge.head.html", it)) }
                attrs["taillabel"]?.let(::htmlLabelOf)?.let { putAll(htmlPayload("dot.edge.tail.html", it)) }
                from.port?.let { put("dot.edge.fromPort", it) }
                from.compass?.let { put("dot.edge.fromCompass", it) }
                to.port?.let { put("dot.edge.toPort", it) }
                to.compass?.let { put("dot.edge.toCompass", it) }
            },
        )
    }

    private fun shapeOf(raw: String?, style: String?): NodeShape {
        val rounded = style.orEmpty().split(',').any { it.trim().equals("rounded", ignoreCase = true) }
        return when (raw?.lowercase()) {
            null, "", "box", "rect", "rectangle", "plain", "plaintext" -> if (rounded) NodeShape.RoundedBox else NodeShape.Box
            "ellipse", "oval" -> NodeShape.Ellipse
            "circle", "doublecircle" -> NodeShape.Circle
            "diamond" -> NodeShape.Diamond
            "hexagon" -> NodeShape.Hexagon
            "parallelogram" -> NodeShape.Parallelogram
            "trapezium", "trapezoid" -> NodeShape.Trapezoid
            "cylinder" -> NodeShape.Cylinder
            "component" -> NodeShape.Component
            "note" -> NodeShape.Note
            else -> NodeShape.Custom(raw)
        }
    }

    private fun nodeStyleOf(attrs: Map<String, String>, shape: NodeShape): NodeStyle {
        val style = attrs["style"].orEmpty().split(',').map { it.trim().lowercase() }.toSet()
        val fill = parseColor(attrs["fillcolor"] ?: attrs["bgcolor"])
            ?: parseColor(attrs["color"])?.takeIf { "filled" in style }
            ?: defaultFill(shape)
        return NodeStyle(
            fill = fill,
            stroke = parseColor(attrs["color"]) ?: ArgbColor(0xFF374151.toInt()),
            strokeWidth = attrs["penwidth"]?.toFloatOrNull() ?: if ("bold" in style) 2.4f else 1.2f,
            textColor = parseColor(attrs["fontcolor"]) ?: ArgbColor(0xFF111827.toInt()),
        )
    }

    private fun edgeStyleOf(attrs: Map<String, String>): EdgeStyle =
        EdgeStyle(
            color = parseColor(attrs["color"]) ?: ArgbColor(0xFF4B5563.toInt()),
            width = attrs["penwidth"]?.toFloatOrNull() ?: if (attrs["style"]?.contains("bold", ignoreCase = true) == true) 2.2f else 1.2f,
            dash = when (edgeKindOf(attrs["style"])) {
                EdgeKind.Dashed -> listOf(6f, 4f)
                EdgeKind.Dotted -> listOf(2f, 4f)
                else -> null
            },
            labelBg = ArgbColor(0xF0FFFFFF.toInt()),
        )

    private fun edgeKindOf(style: String?): EdgeKind =
        when {
            style?.contains("invis", ignoreCase = true) == true -> EdgeKind.Invisible
            style?.contains("dotted", ignoreCase = true) == true -> EdgeKind.Dotted
            style?.contains("dashed", ignoreCase = true) == true -> EdgeKind.Dashed
            style?.contains("bold", ignoreCase = true) == true -> EdgeKind.Thick
            else -> EdgeKind.Solid
        }

    private fun clusterStyleOf(attrs: Map<String, String>): ClusterStyle =
        ClusterStyle(
            fill = parseColor(attrs["fillcolor"] ?: attrs["bgcolor"]) ?: ArgbColor(0xFFF8FAFC.toInt()),
            stroke = parseColor(attrs["color"]) ?: ArgbColor(0xFF94A3B8.toInt()),
            strokeWidth = attrs["penwidth"]?.toFloatOrNull() ?: 1.2f,
        )

    private fun defaultFill(shape: NodeShape): ArgbColor =
        when (shape) {
            NodeShape.Diamond -> ArgbColor(0xFFFFF7ED.toInt())
            NodeShape.Circle, NodeShape.Ellipse -> ArgbColor(0xFFECFEFF.toInt())
            else -> ArgbColor(0xFFF9FAFB.toInt())
        }

    private fun parseColor(raw: String?): ArgbColor? {
        val value = raw?.trim()?.removeSurrounding("\"") ?: return null
        val hex = value.removePrefix("#")
        if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return ArgbColor((0xFF000000 or hex.toLong(16)).toInt())
        }
        return when (value.lowercase()) {
            "black" -> ArgbColor(0xFF000000.toInt())
            "white" -> ArgbColor(0xFFFFFFFF.toInt())
            "red" -> ArgbColor(0xFFE53935.toInt())
            "green" -> ArgbColor(0xFF43A047.toInt())
            "blue" -> ArgbColor(0xFF1E88E5.toInt())
            "yellow" -> ArgbColor(0xFFFDD835.toInt())
            "orange" -> ArgbColor(0xFFFF9800.toInt())
            "purple" -> ArgbColor(0xFF8E24AA.toInt())
            "gray", "grey" -> ArgbColor(0xFF9E9E9E.toInt())
            else -> null
        }
    }

    private fun tokenize(source: String, diagnostics: MutableList<Diagnostic>): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++
                c == '#'
                    && (i == 0 || source.getOrNull(i - 1) == '\n') -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && source.getOrNull(i + 1) == '/' -> {
                    i += 2
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && source.getOrNull(i + 1) == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(source.length)
                }
                c == '"' -> {
                    val start = i++
                    val sb = StringBuilder()
                    while (i < source.length) {
                        val ch = source[i++]
                        if (ch == '\\' && i < source.length) {
                            sb.append('\\')
                            sb.append(source[i++])
                        } else if (ch == '"') {
                            break
                        } else {
                            sb.append(ch)
                        }
                    }
                    out += Token(Kind.Id, sb.toString(), start)
                }
                c == '<' -> {
                    if (source.getOrNull(i + 1) == '-') {
                        diagnostics += diagnostic("DOT-E001", "Unexpected reverse edge operator near offset $i")
                        i += 2
                    } else {
                        val start = i++
                        var depth = 1
                        val sb = StringBuilder()
                        while (i < source.length && depth > 0) {
                            val ch = source[i++]
                            when (ch) {
                                '<' -> {
                                    depth++
                                    sb.append(ch)
                                }
                                '>' -> {
                                    depth--
                                    if (depth > 0) sb.append(ch)
                                }
                                else -> sb.append(ch)
                            }
                        }
                        out += Token(Kind.Id, htmlLikeToValue(sb.toString()), start)
                    }
                }
                c == '-' && source.getOrNull(i + 1) == '>' -> {
                    out += Token(Kind.EdgeDirected, "->", i)
                    i += 2
                }
                c == '-' && source.getOrNull(i + 1) == '-' -> {
                    out += Token(Kind.EdgeUndirected, "--", i)
                    i += 2
                }
                c == '{' -> { out += Token(Kind.LBrace, "{", i); i++ }
                c == '}' -> { out += Token(Kind.RBrace, "}", i); i++ }
                c == '[' -> { out += Token(Kind.LBracket, "[", i); i++ }
                c == ']' -> { out += Token(Kind.RBracket, "]", i); i++ }
                c == '=' -> { out += Token(Kind.Equal, "=", i); i++ }
                c == ';' -> { out += Token(Kind.Semi, ";", i); i++ }
                c == ',' -> { out += Token(Kind.Comma, ",", i); i++ }
                c == ':' -> { out += Token(Kind.Colon, ":", i); i++ }
                else -> {
                    val start = i
                    while (i < source.length && isIdChar(source[i])) i++
                    if (i == start) {
                        diagnostics += diagnostic("DOT-E001", "Unexpected DOT character '${source[i]}' near offset $i")
                        i++
                    } else {
                        out += Token(Kind.Id, source.substring(start, i), start)
                    }
                }
            }
        }
        return out
    }

    private fun ParserState.error(message: String) {
        diagnostics += diagnostic("DOT-E001", message)
    }

    private fun unsupportedLayoutWarnings(graphAttrs: Map<String, String>): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val layout = graphAttrs["layout"]?.lowercase()
        if (layout in setOf("neato", "fdp", "twopi", "circo")) {
            out += Diagnostic(
                severity = Severity.WARNING,
                message = "DOT layout=$layout is treated as a Sugiyama layout hint; native Graphviz engines are not invoked.",
                code = "DOT-W001",
            )
        }
        val engineOnlyAttrs = listOf("overlap", "sep", "esep", "mode", "model", "pack", "packmode", "root")
        for (attr in engineOnlyAttrs) {
            if (attr in graphAttrs) {
                out += Diagnostic(
                    severity = Severity.WARNING,
                    message = "DOT graph attribute '$attr' is preserved as metadata but ignored by the internal Sugiyama renderer.",
                    code = "DOT-W001",
                )
            }
        }
        return out
    }

    @Suppress("unused")
    private fun recordUnsupportedLayoutWarnings(state: ParserState, graphAttrs: Map<String, String>) {
        state.diagnostics += unsupportedLayoutWarnings(graphAttrs)
    }

    private fun diagnostic(code: String, message: String): Diagnostic =
        Diagnostic(severity = Severity.ERROR, message = message, code = code)

    private fun isIdChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '.' || c == '-' || c >= '\u0080'

    private fun nodeId(raw: String): NodeId = NodeId("dot_${slug(raw)}")

    private fun decodeLabel(raw: String): String =
        (htmlLabelOf(raw)?.text ?: raw)
            .replace("\\n", "\n")
            .replace("\\l", "\n")
            .replace("\\r", "\n")
            .trim()

    private fun decodeAttrValue(raw: String): String =
        if (raw.startsWith(HTML_LABEL_PREFIX)) raw else decodeLabel(raw)

    private fun htmlLikeToValue(raw: String): String {
        val label = HtmlLabel(
            text = htmlLikeToText(raw),
            bold = Regex("""<\s*b\b""", RegexOption.IGNORE_CASE).containsMatchIn(raw),
            italic = Regex("""<\s*i\b""", RegexOption.IGNORE_CASE).containsMatchIn(raw),
            fontName = htmlAttr(raw, "face"),
            fontSize = htmlAttr(raw, "point-size") ?: htmlAttr(raw, "point_size"),
            fontColor = htmlAttr(raw, "color"),
        )
        return encodeHtmlLabel(label)
    }

    private fun htmlLikeToText(raw: String): String =
        raw
            .replace(Regex("""<\s*br\s*/?\s*>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<\s*/\s*(tr|table)\s*>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<\s*/?\s*(td|th)\b[^>]*>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .lines()
            .map { line -> line.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    private fun htmlAttr(raw: String, name: String): String? {
        val pattern = Regex("""\b${name}\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE)
        return pattern.find(raw)?.groupValues?.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
    }

    private fun encodeHtmlLabel(label: HtmlLabel): String =
        HTML_LABEL_PREFIX + listOf(
            label.text,
            label.bold.toString(),
            label.italic.toString(),
            label.fontName.orEmpty(),
            label.fontSize.orEmpty(),
            label.fontColor.orEmpty(),
        ).joinToString(HTML_LABEL_SEPARATOR)

    private fun htmlLabelOf(raw: String): HtmlLabel? {
        if (!raw.startsWith(HTML_LABEL_PREFIX)) return null
        val parts = raw.removePrefix(HTML_LABEL_PREFIX).split(HTML_LABEL_SEPARATOR)
        return HtmlLabel(
            text = parts.getOrNull(0).orEmpty(),
            bold = parts.getOrNull(1).toBoolean(),
            italic = parts.getOrNull(2).toBoolean(),
            fontName = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
            fontSize = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
            fontColor = parts.getOrNull(5)?.takeIf { it.isNotBlank() },
        )
    }

    private fun htmlPayload(prefix: String, label: HtmlLabel): Map<String, String> = buildMap {
        put("$prefix.kind", "html")
        put("$prefix.bold", label.bold.toString())
        put("$prefix.italic", label.italic.toString())
        label.fontName?.let { put("$prefix.fontname", it) }
        label.fontSize?.let { put("$prefix.fontsize", it) }
        label.fontColor?.let { put("$prefix.fontcolor", it) }
    }

    private fun slug(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9_.:-]+"), "_").trim('_').ifBlank { "node" }

    private companion object {
        const val HTML_LABEL_PREFIX: String = "\u0001DOTHTML\u0001"
        const val HTML_LABEL_SEPARATOR: String = "\u0002"
    }
}
