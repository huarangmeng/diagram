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
import com.hrm.diagram.core.ir.Port
import com.hrm.diagram.core.ir.PortId
import com.hrm.diagram.core.ir.PortSide
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `architecture-beta`.
 *
 * Supported subset:
 * - `group id(icon)[label] (in parent)?`
 * - `service id(icon)[label] (in parent)?`
 * - `junction id (in parent)?`
 * - edges with port sides and optional `{group}` endpoint modifier
 */
class MermaidArchitectureParser {
    companion object {
        const val KIND_KEY = "mermaid.architecture.kind"
        const val ICON_KEY = "mermaid.architecture.icon"
        const val PARENT_KEY = "mermaid.architecture.parent"
    }

    private data class GroupDef(
        val id: NodeId,
        val icon: String,
        val title: String,
        val parent: NodeId?,
    )

    private data class Endpoint(
        val nodeId: NodeId,
        val useGroupBoundary: Boolean,
        val side: PortSide,
    )

    private data class PendingEdge(
        val left: Endpoint,
        val right: Endpoint,
        val arrow: ArrowEnds,
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val edges: MutableList<Edge> = ArrayList()
    private val groups: LinkedHashMap<NodeId, GroupDef> = LinkedHashMap()
    private val pendingEdges: MutableList<PendingEdge> = ArrayList()
    private var seq: Long = 0
    private var headerSeen = false
    private var direction: Direction = Direction.LR

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())
        val lexErr = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (lexErr != null) return errorBatch("Lex error at ${lexErr.start}: ${lexErr.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.ARCHITECTURE_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'architecture-beta' header")
        }

        val text = toks.joinToString(" ") { it.text.toString() }.trim()
        if (text.isBlank()) return IrPatchBatch(seq, emptyList())

        val patches = ArrayList<IrPatch>()
        when {
            text.startsWith("group ") -> parseGroup(text.removePrefix("group ").trim(), patches)
            text.startsWith("service ") -> parseService(text.removePrefix("service ").trim(), patches)
            text.startsWith("junction ") -> parseJunction(text.removePrefix("junction ").trim(), patches)
            else -> parseEdge(text, patches)
        }
        flushPendingEdges(patches)
        return IrPatchBatch(seq, patches)
    }

    fun snapshot(): GraphIR = GraphIR(
        nodes = nodes.values.toList(),
        edges = edges.toList(),
        clusters = buildClusters(parent = null),
        sourceLanguage = SourceLanguage.MERMAID,
        styleHints = StyleHints(direction = direction),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseGroup(spec: String, out: MutableList<IrPatch>) {
        val parsed = parseNamedIconLabel(spec) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid architecture group syntax", "MERMAID-E212")
            return
        }
        val parent = parseOptionalParent(parsed.rest)
        if (parent != null && parent !in groups) {
            diagnostics += Diagnostic(Severity.ERROR, "Unknown parent group '${parent.value}'", "MERMAID-E212")
            return
        }
        val def = GroupDef(
            id = NodeId(parsed.id),
            icon = parsed.icon,
            title = parsed.label.ifBlank { parsed.id },
            parent = parent,
        )
        groups[def.id] = def
    }

    private fun parseService(spec: String, out: MutableList<IrPatch>) {
        val parsed = parseNamedIconLabel(spec) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid architecture service syntax", "MERMAID-E212")
            return
        }
        val parent = parseOptionalParent(parsed.rest)
        if (parent != null && parent !in groups) {
            diagnostics += Diagnostic(Severity.ERROR, "Unknown parent group '${parent.value}'", "MERMAID-E212")
            return
        }
        val id = NodeId(parsed.id)
        val node = Node(
            id = id,
            label = RichLabel.Plain(parsed.label.ifBlank { parsed.id }),
            shape = NodeShape.RoundedBox,
            style = NodeStyle(
                fill = ArgbColor(0xFFE3F2FD.toInt()),
                stroke = ArgbColor(0xFF1E88E5.toInt()),
                strokeWidth = 1.5f,
                textColor = ArgbColor(0xFF0D47A1.toInt()),
            ),
            ports = defaultPorts(),
            payload = buildMap {
                put(KIND_KEY, "service")
                put(ICON_KEY, parsed.icon)
                parent?.let { put(PARENT_KEY, it.value) }
            },
        )
        nodes[id] = node
        out += IrPatch.AddNode(node)
    }

    private fun parseJunction(spec: String, out: MutableList<IrPatch>) {
        val idText = spec.substringBefore(" in ").trim()
        if (idText.isBlank()) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid architecture junction syntax", "MERMAID-E212")
            return
        }
        val parent = parseOptionalParent(spec.removePrefix(idText).trim())
        if (parent != null && parent !in groups) {
            diagnostics += Diagnostic(Severity.ERROR, "Unknown parent group '${parent.value}'", "MERMAID-E212")
            return
        }
        val id = NodeId(idText)
        val node = Node(
            id = id,
            label = RichLabel.Plain(""),
            shape = NodeShape.Circle,
            style = NodeStyle(
                fill = ArgbColor(0xFF455A64.toInt()),
                stroke = ArgbColor(0xFF263238.toInt()),
                strokeWidth = 1.5f,
                textColor = ArgbColor(0xFFFFFFFF.toInt()),
            ),
            ports = defaultPorts(),
            payload = buildMap {
                put(KIND_KEY, "junction")
                parent?.let { put(PARENT_KEY, it.value) }
            },
        )
        nodes[id] = node
        out += IrPatch.AddNode(node)
    }

    private fun parseEdge(text: String, out: MutableList<IrPatch>) {
        val opIndex = text.indexOf("--")
        if (opIndex <= 0) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid architecture edge syntax", "MERMAID-E212")
            return
        }
        val leftText = text.substring(0, opIndex).trim()
        val after = text.substring(opIndex + 2)
        val rightArrow = after.startsWith(">")
        val leftArrow = leftText.endsWith("<")
        val leftSpec = if (leftArrow) leftText.dropLast(1).trimEnd() else leftText
        val rightSpec = if (rightArrow) after.removePrefix(">").trimStart() else after.trimStart()
        val left = parseLeftEndpoint(leftSpec)
        val right = parseRightEndpoint(rightSpec)
        if (left == null || right == null) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid architecture edge syntax", "MERMAID-E212")
            return
        }
        val arrow = when {
            leftArrow && rightArrow -> ArrowEnds.Both
            leftArrow -> ArrowEnds.FromOnly
            rightArrow -> ArrowEnds.ToOnly
            else -> ArrowEnds.None
        }
        val pending = PendingEdge(left = left, right = right, arrow = arrow)
        if (left.nodeId in nodes && right.nodeId in nodes) {
            registerEdge(pending, out)
        } else {
            pendingEdges += pending
        }
    }

    private fun registerEdge(pending: PendingEdge, out: MutableList<IrPatch>) {
        val edge = Edge(
            from = pending.left.nodeId,
            to = pending.right.nodeId,
            arrow = pending.arrow,
            fromPort = endpointPortId(pending.left),
            toPort = endpointPortId(pending.right),
            style = EdgeStyle(
                color = ArgbColor(0xFF546E7A.toInt()),
                width = 1.5f,
            ),
        )
        if (edges.any { it.from == edge.from && it.to == edge.to && it.fromPort == edge.fromPort && it.toPort == edge.toPort }) return
        edges += edge
        out += IrPatch.AddEdge(edge)
    }

    private fun flushPendingEdges(out: MutableList<IrPatch>) {
        val ready = pendingEdges.filter { it.left.nodeId in nodes && it.right.nodeId in nodes }
        if (ready.isEmpty()) return
        pendingEdges.removeAll(ready)
        ready.forEach { registerEdge(it, out) }
    }

    private fun buildClusters(parent: NodeId?): List<Cluster> {
        return groups.values
            .filter { it.parent == parent }
            .map { group ->
                Cluster(
                    id = group.id,
                    label = encodeGroupLabel(group.icon, group.title),
                    children = nodes.values
                        .filter { it.payload[PARENT_KEY] == group.id.value }
                        .map { it.id },
                    nestedClusters = buildClusters(group.id),
                    style = ClusterStyle(
                        fill = ArgbColor(0xFFF8FBFF.toInt()),
                        stroke = ArgbColor(0xFF90A4AE.toInt()),
                        strokeWidth = 1.5f,
                    ),
                )
            }
    }

    private fun parseLeftEndpoint(spec: String): Endpoint? {
        val colon = spec.lastIndexOf(':')
        if (colon <= 0) return null
        val nodeSpec = spec.substring(0, colon).trim()
        val side = parseSide(spec.substring(colon + 1).trim()) ?: return null
        val node = parseEndpointNode(nodeSpec) ?: return null
        return Endpoint(nodeId = node.first, useGroupBoundary = node.second, side = side)
    }

    private fun parseRightEndpoint(spec: String): Endpoint? {
        val colon = spec.indexOf(':')
        if (colon <= 0) return null
        val side = parseSide(spec.substring(0, colon).trim()) ?: return null
        val node = parseEndpointNode(spec.substring(colon + 1).trim()) ?: return null
        return Endpoint(nodeId = node.first, useGroupBoundary = node.second, side = side)
    }

    private fun parseEndpointNode(spec: String): Pair<NodeId, Boolean>? {
        val trimmed = spec.trim()
        return if (trimmed.endsWith("{group}")) {
            val idText = trimmed.removeSuffix("{group}").trim()
            if (idText.isEmpty()) null else NodeId(idText) to true
        } else {
            if (trimmed.isEmpty()) null else NodeId(trimmed) to false
        }
    }

    private fun parseSide(raw: String): PortSide? = when (raw.uppercase()) {
        "T" -> PortSide.TOP
        "R" -> PortSide.RIGHT
        "B" -> PortSide.BOTTOM
        "L" -> PortSide.LEFT
        else -> null
    }

    private fun endpointPortId(endpoint: Endpoint): PortId {
        val prefix = if (endpoint.useGroupBoundary) {
            val parentId = nodes[endpoint.nodeId]?.payload?.get(PARENT_KEY).orEmpty()
            "GROUP@$parentId@"
        } else {
            "NODE@"
        }
        val side = when (endpoint.side) {
            PortSide.TOP -> "T"
            PortSide.RIGHT -> "R"
            PortSide.BOTTOM -> "B"
            PortSide.LEFT -> "L"
        }
        return PortId(prefix + side)
    }

    private fun parseOptionalParent(spec: String): NodeId? {
        if (spec.isBlank()) return null
        val trimmed = spec.trim()
        if (!trimmed.startsWith("in ")) return null
        val parent = trimmed.removePrefix("in ").trim()
        return if (parent.isBlank()) null else NodeId(parent)
    }

    private data class ParsedNamedIconLabel(
        val id: String,
        val icon: String,
        val label: String,
        val rest: String,
    )

    private fun parseNamedIconLabel(spec: String): ParsedNamedIconLabel? {
        val labelStart = spec.indexOf('[')
        val labelEnd = spec.lastIndexOf(']')
        if (labelStart <= 0 || labelEnd <= labelStart) return null
        val head = spec.substring(0, labelStart).trim()
        val label = spec.substring(labelStart + 1, labelEnd)
        val rest = spec.substring(labelEnd + 1).trim()

        val iconOpen = head.indexOf('(')
        val id: String
        val icon: String
        if (iconOpen > 0) {
            val iconEnd = head.indexOf(')', iconOpen + 1)
            if (iconEnd <= iconOpen) return null
            id = head.substring(0, iconOpen).trim()
            icon = head.substring(iconOpen + 1, iconEnd).trim()
        } else {
            id = head
            icon = ""
        }
        if (id.isBlank()) return null
        return ParsedNamedIconLabel(id = id, icon = icon, label = label, rest = rest)
    }

    private fun encodeGroupLabel(icon: String, title: String): RichLabel =
        RichLabel.Plain("__icon:$icon\n$title")

    private fun defaultPorts(): List<Port> = listOf(
        Port(PortId("T"), side = PortSide.TOP),
        Port(PortId("R"), side = PortSide.RIGHT),
        Port(PortId("B"), side = PortSide.BOTTOM),
        Port(PortId("L"), side = PortSide.LEFT),
    )

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E212")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
