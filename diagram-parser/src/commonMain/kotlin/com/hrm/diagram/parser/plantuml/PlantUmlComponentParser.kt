package com.hrm.diagram.parser.plantuml

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
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Streaming parser for the Phase-4 PlantUML `component` MVP.
 *
 * Example:
 * ```kotlin
 * val parser = PlantUmlComponentParser()
 * parser.acceptLine("""component "API" as Api""")
 * parser.acceptLine("""interface "HTTP" as Http""")
 * parser.acceptLine("Api --> Http")
 * val ir = parser.snapshot()
 * ```
 */
@DiagramApi
class PlantUmlComponentParser {
    companion object {
        const val KIND_KEY = "plantuml.component.kind"
        const val ICON_KEY = "plantuml.component.icon"
        const val PARENT_KEY = "plantuml.component.parent"
        const val PORT_DIR_KEY = "plantuml.component.portDir"
        val RELATION_OPERATORS = listOf("-->", "<--", "..>", "<..", "--", "..")
    }

    private data class ClusterDef(
        val id: NodeId,
        val title: String,
        val kind: String,
        val parent: NodeId?,
    )

    private data class Endpoint(
        val nodeId: NodeId,
        val portSideHint: String? = null,
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val edges: MutableList<Edge> = ArrayList()
    private val clusters: LinkedHashMap<NodeId, ClusterDef> = LinkedHashMap()
    private val clusterStack: ArrayDeque<NodeId> = ArrayDeque()

    private var seq: Long = 0
    private var direction: Direction = Direction.LR

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed == "}") {
            if (clusterStack.isEmpty()) return errorBatch("Unmatched '}' in PlantUML component body")
            clusterStack.removeLast()
            return IrPatchBatch(seq, emptyList())
        }

        val patches = ArrayList<IrPatch>()
        when {
            trimmed.equals("left to right direction", ignoreCase = true) -> direction = Direction.LR
            trimmed.equals("right to left direction", ignoreCase = true) -> direction = Direction.RL
            trimmed.equals("top to bottom direction", ignoreCase = true) -> direction = Direction.TB
            trimmed.equals("bottom to top direction", ignoreCase = true) -> direction = Direction.BT
            trimmed.startsWith("[") -> parseNodeDecl("component $trimmed", "component", patches)
            trimmed.startsWith("component ", ignoreCase = true) -> parseNodeDecl(trimmed, "component", patches)
            trimmed.startsWith("interface ", ignoreCase = true) -> parseNodeDecl(trimmed, "interface", patches)
            trimmed.startsWith("port ", ignoreCase = true) -> parseNodeDecl(trimmed, "port", patches)
            trimmed.startsWith("portin ", ignoreCase = true) -> parseNodeDecl(trimmed, "portin", patches)
            trimmed.startsWith("portout ", ignoreCase = true) -> parseNodeDecl(trimmed, "portout", patches)
            trimmed.startsWith("package ", ignoreCase = true) -> parseClusterDecl(trimmed, "package")
            trimmed.startsWith("cloud ", ignoreCase = true) -> parseClusterDecl(trimmed, "cloud")
            trimmed.startsWith("node ", ignoreCase = true) -> parseClusterDecl(trimmed, "node")
            findRelationOperator(trimmed) != null -> parseEdge(trimmed, patches)
            else -> return errorBatch("Unsupported PlantUML component statement: $trimmed")
        }
        return IrPatchBatch(seq, patches)
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (clusterStack.isNotEmpty()) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed component cluster body before end of PlantUML block",
                    code = "PLANTUML-E005",
                ),
            )
            clusterStack.clear()
        }
        if (!blockClosed) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Missing '@enduml' terminator",
                    code = "PLANTUML-E001",
                ),
            )
        }
        return IrPatchBatch(seq, out)
    }

    fun snapshot(): GraphIR = GraphIR(
        nodes = nodes.values.toList(),
        edges = edges.toList(),
        clusters = buildClusters(null),
        sourceLanguage = SourceLanguage.PLANTUML,
        styleHints = StyleHints(direction = direction),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseNodeDecl(line: String, keyword: String, out: MutableList<IrPatch>) {
        var body = line.substring(keyword.length).trim()
        val inlineCluster = body.endsWith("{")
        if (inlineCluster) body = body.removeSuffix("{").trim()
        if (body.isEmpty()) {
            diagnostics += Diagnostic(Severity.ERROR, "Expected identifier after '$keyword'", "PLANTUML-E005")
            return
        }
        val spec = parseAliasSpec(body) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML $keyword declaration", "PLANTUML-E005")
            return
        }
        val id = NodeId(spec.id)
        val parent = clusterStack.lastOrNull()
        val node = Node(
            id = id,
            label = RichLabel.Plain(spec.label.ifBlank { spec.id }),
            shape = shapeFor(keyword),
            style = styleFor(keyword),
            payload = buildMap {
                put(KIND_KEY, kindFor(keyword))
                parent?.let { put(PARENT_KEY, it.value) }
                if (keyword == "portin") put(PORT_DIR_KEY, "in")
                if (keyword == "portout") put(PORT_DIR_KEY, "out")
                spec.icon?.let { put(ICON_KEY, it) }
            },
        )
        nodes[id] = node
        out += IrPatch.AddNode(node)
        if (inlineCluster) {
            val clusterId = NodeId("${id.value}__cluster")
            clusters[clusterId] = ClusterDef(
                id = clusterId,
                title = spec.label.ifBlank { spec.id },
                kind = kindFor(keyword),
                parent = parent,
            )
            clusterStack.addLast(clusterId)
        }
    }

    private fun parseClusterDecl(line: String, keyword: String) {
        var body = line.substring(keyword.length).trim()
        val opens = body.endsWith("{")
        if (opens) body = body.removeSuffix("{").trim()
        val spec = parseAliasSpec(body) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML $keyword declaration", "PLANTUML-E005")
            return
        }
        val clusterId = NodeId(spec.id)
        clusters[clusterId] = ClusterDef(
            id = clusterId,
            title = spec.label.ifBlank { spec.id },
            kind = keyword.lowercase(),
            parent = clusterStack.lastOrNull(),
        )
        if (opens) clusterStack.addLast(clusterId)
    }

    private fun parseEdge(line: String, out: MutableList<IrPatch>) {
        val op = findRelationOperator(line) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML component relation", "PLANTUML-E005")
            return
        }
        val parts = line.split(":", limit = 2)
        val relText = parts[0].trim()
        val label = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(RichLabel::Plain)

        val regex = Regex("^(.*?)\\s*" + Regex.escape(op) + "\\s*(.*?)$")
        val m = regex.matchEntire(relText) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML component relation", "PLANTUML-E005")
            return
        }
        val left = parseEndpoint(m.groupValues[1].trim())
        val right = parseEndpoint(m.groupValues[2].trim())
        if (left == null || right == null) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML component relation endpoint", "PLANTUML-E005")
            return
        }
        ensureImplicitNode(left.nodeId)
        ensureImplicitNode(right.nodeId)
        val edge = Edge(
            from = left.nodeId,
            to = right.nodeId,
            label = label,
            kind = when (op) {
                "..>", "..", "<.." -> EdgeKind.Dashed
                else -> EdgeKind.Solid
            },
            arrow = when (op) {
                "-->", "..>" -> ArrowEnds.ToOnly
                "<--", "<.." -> ArrowEnds.FromOnly
                else -> ArrowEnds.None
            },
            style = EdgeStyle(
                color = ArgbColor(0xFF546E7A.toInt()),
                width = 1.5f,
                dash = if (op.contains("..")) listOf(6f, 4f) else null,
            ),
        )
        if (edges.any { it.from == edge.from && it.to == edge.to && it.kind == edge.kind && it.arrow == edge.arrow && it.label == edge.label }) return
        edges += edge
        out += IrPatch.AddEdge(edge)
    }

    private fun parseEndpoint(raw: String): Endpoint? {
        val clean = raw.removePrefix("[").removeSuffix("]").removePrefix("(").removeSuffix(")").trim()
        if (!clean.matches(Regex("[A-Za-z0-9_.:-]+"))) return null
        return Endpoint(NodeId(clean))
    }

    private fun ensureImplicitNode(id: NodeId) {
        if (id in nodes) return
        val parent = clusterStack.lastOrNull()
        nodes[id] = Node(
            id = id,
            label = RichLabel.Plain(id.value),
            shape = NodeShape.Component,
            style = styleFor("component"),
            payload = buildMap {
                put(KIND_KEY, "component")
                parent?.let { put(PARENT_KEY, it.value) }
            },
        )
    }

    private fun buildClusters(parent: NodeId?): List<Cluster> =
        clusters.values
            .filter { it.parent == parent }
            .map { def ->
                Cluster(
                    id = def.id,
                    label = RichLabel.Plain("${def.kind}\n${def.title}"),
                    children = nodes.values.filter { it.payload[PARENT_KEY] == def.id.value }.map { it.id },
                    nestedClusters = buildClusters(def.id),
                    style = clusterStyleFor(def.kind),
                )
            }

    private data class AliasSpec(
        val id: String,
        val label: String,
        val icon: String? = null,
    )

    private fun parseAliasSpec(body: String): AliasSpec? {
        val quotedAs = Regex("^\"([^\"]+)\"\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (quotedAs != null) return AliasSpec(id = quotedAs.groupValues[2], label = quotedAs.groupValues[1])
        val aliasQuoted = Regex("^([A-Za-z0-9_.:-]+)\\s+as\\s+\"([^\"]+)\"$").matchEntire(body)
        if (aliasQuoted != null) return AliasSpec(id = aliasQuoted.groupValues[1], label = aliasQuoted.groupValues[2])
        val bracket = Regex("^\\[([^\\]]+)\\](?:\\s+as\\s+([A-Za-z0-9_.:-]+))?$").matchEntire(body)
        if (bracket != null) {
            val label = bracket.groupValues[1]
            val id = bracket.groupValues[2].ifEmpty { sanitizeId(label) }
            return AliasSpec(id = id, label = label)
        }
        val simple = Regex("^([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (simple != null) return AliasSpec(id = simple.groupValues[1], label = simple.groupValues[1])
        return null
    }

    private fun sanitizeId(text: String): String =
        text.replace(Regex("[^A-Za-z0-9_.:-]+"), "_").trim('_').ifEmpty { "node_${seq}" }

    private fun kindFor(keyword: String): String = when (keyword.lowercase()) {
        "component" -> "component"
        "interface" -> "interface"
        "port", "portin", "portout" -> "port"
        "package" -> "package"
        "cloud" -> "cloud"
        "node" -> "node"
        else -> keyword.lowercase()
    }

    private fun shapeFor(keyword: String): NodeShape = when (keyword.lowercase()) {
        "component" -> NodeShape.Component
        "interface" -> NodeShape.Circle
        "port", "portin", "portout" -> NodeShape.Circle
        else -> NodeShape.RoundedBox
    }

    private fun styleFor(keyword: String): NodeStyle = when (keyword.lowercase()) {
        "interface" -> NodeStyle(
            fill = ArgbColor(0xFFFFFFFF.toInt()),
            stroke = ArgbColor(0xFF00838F.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF006064.toInt()),
        )
        "port", "portin", "portout" -> NodeStyle(
            fill = ArgbColor(0xFFE0F7FA.toInt()),
            stroke = ArgbColor(0xFF00838F.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF006064.toInt()),
        )
        else -> NodeStyle(
            fill = ArgbColor(0xFFE8EAF6.toInt()),
            stroke = ArgbColor(0xFF3949AB.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF1A237E.toInt()),
        )
    }

    private fun clusterStyleFor(kind: String): ClusterStyle = when (kind) {
        "cloud" -> ClusterStyle(
            fill = ArgbColor(0xFFF3E5F5.toInt()),
            stroke = ArgbColor(0xFF8E24AA.toInt()),
            strokeWidth = 1.5f,
        )
        "node" -> ClusterStyle(
            fill = ArgbColor(0xFFF1F8E9.toInt()),
            stroke = ArgbColor(0xFF558B2F.toInt()),
            strokeWidth = 1.5f,
        )
        else -> ClusterStyle(
            fill = ArgbColor(0xFFF5F5F5.toInt()),
            stroke = ArgbColor(0xFF78909C.toInt()),
            strokeWidth = 1.5f,
        )
    }

    private fun findRelationOperator(line: String): String? {
        for (candidate in RELATION_OPERATORS) {
            if (line.contains(candidate)) return candidate
        }
        return null
    }

    private fun errorBatch(message: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.ERROR, message, "PLANTUML-E005"))))

    private fun addDiagnostic(diagnostic: Diagnostic): IrPatch {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }

}
