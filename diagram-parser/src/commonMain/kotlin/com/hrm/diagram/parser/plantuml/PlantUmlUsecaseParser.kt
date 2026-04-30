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
 * Streaming parser for the Phase-4 PlantUML `usecase` MVP.
 *
 * Example:
 * ```kotlin
 * val parser = PlantUmlUsecaseParser()
 * parser.acceptLine("actor User")
 * parser.acceptLine("(Login) as LoginUsecase")
 * parser.acceptLine("User --> LoginUsecase : starts")
 * val ir = parser.snapshot()
 * ```
 */
@DiagramApi
class PlantUmlUsecaseParser {
    companion object {
        const val KIND_KEY = "plantuml.usecase.kind"
        const val PARENT_KEY = "plantuml.usecase.parent"
        val RELATION_OPERATORS = listOf("<|--", "-->", "<--", "..>", "<..", "--", "..")

        private const val ACTOR_KIND = "actor"
        private const val USECASE_KIND = "usecase"
    }

    private data class ClusterDef(
        val id: NodeId,
        val title: String,
        val kind: String,
        val parent: NodeId?,
    )

    private data class AliasSpec(
        val id: String,
        val label: String,
    )

    private data class EndpointSpec(
        val id: NodeId,
        val explicitKind: String?,
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
            if (clusterStack.isEmpty()) return errorBatch("Unmatched '}' in PlantUML usecase body")
            clusterStack.removeLast()
            return IrPatchBatch(seq, emptyList())
        }

        val patches = ArrayList<IrPatch>()
        when {
            trimmed.equals("left to right direction", ignoreCase = true) -> direction = Direction.LR
            trimmed.equals("right to left direction", ignoreCase = true) -> direction = Direction.RL
            trimmed.equals("top to bottom direction", ignoreCase = true) -> direction = Direction.TB
            trimmed.equals("bottom to top direction", ignoreCase = true) -> direction = Direction.BT
            trimmed.startsWith("actor ", ignoreCase = true) -> parseActorDecl(trimmed, patches)
            trimmed.startsWith("usecase ", ignoreCase = true) -> parseUsecaseDecl(trimmed, patches)
            trimmed.startsWith("(") && !hasRelationOperator(trimmed) -> parseParenUsecaseDecl(trimmed, patches)
            trimmed.startsWith(":") && trimmed.endsWith(":") -> parseActorColon(trimmed, patches)
            trimmed.startsWith("rectangle ", ignoreCase = true) -> parseClusterDecl(trimmed, "rectangle")
            trimmed.startsWith("package ", ignoreCase = true) -> parseClusterDecl(trimmed, "package")
            findRelationOperator(trimmed) != null -> parseEdge(trimmed, patches)
            else -> return errorBatch("Unsupported PlantUML usecase statement: $trimmed")
        }
        return IrPatchBatch(seq, patches)
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (clusterStack.isNotEmpty()) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed usecase cluster body before end of PlantUML block",
                    code = "PLANTUML-E006",
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
        clusters = buildClusters(parent = null),
        sourceLanguage = SourceLanguage.PLANTUML,
        styleHints = StyleHints(direction = direction),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseActorDecl(line: String, out: MutableList<IrPatch>) {
        val spec = parseQuotedOrSimple(line.removePrefix("actor").trim()) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML actor declaration", "PLANTUML-E006")
            return
        }
        ensureNode(spec.id, spec.label, ACTOR_KIND, out)
    }

    private fun parseActorColon(line: String, out: MutableList<IrPatch>) {
        val label = line.removePrefix(":").removeSuffix(":").trim()
        if (label.isEmpty()) {
            diagnostics += Diagnostic(Severity.ERROR, "Empty PlantUML actor shorthand", "PLANTUML-E006")
            return
        }
        ensureNode(sanitizeId(label), label, ACTOR_KIND, out)
    }

    private fun parseUsecaseDecl(line: String, out: MutableList<IrPatch>) {
        val spec = parseUsecaseSpec(line.removePrefix("usecase").trim()) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML usecase declaration", "PLANTUML-E006")
            return
        }
        ensureNode(spec.id, spec.label, USECASE_KIND, out)
    }

    private fun parseParenUsecaseDecl(line: String, out: MutableList<IrPatch>) {
        val spec = parseParenUsecaseSpec(line) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML usecase shorthand", "PLANTUML-E006")
            return
        }
        ensureNode(spec.id, spec.label, USECASE_KIND, out)
    }

    private fun parseClusterDecl(line: String, keyword: String) {
        var body = line.substring(keyword.length).trim()
        val opens = body.endsWith("{")
        if (opens) body = body.removeSuffix("{").trim()
        val spec = parseQuotedOrSimple(body) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML $keyword declaration", "PLANTUML-E006")
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
        val op = findRelationOperator(line) ?: return
        val parts = line.split(":", limit = 2)
        val relText = parts[0].trim()
        val label = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(RichLabel::Plain)
        val regex = Regex("^(.*?)\\s*" + Regex.escape(op) + "\\s*(.*?)$")
        val m = regex.matchEntire(relText) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML usecase relation", "PLANTUML-E006")
            return
        }
        val left = parseEndpoint(m.groupValues[1].trim()) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid left endpoint in usecase relation", "PLANTUML-E006")
            return
        }
        val right = parseEndpoint(m.groupValues[2].trim()) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid right endpoint in usecase relation", "PLANTUML-E006")
            return
        }
        ensureEndpointNode(left, fallbackKind = if (right.explicitKind == USECASE_KIND) ACTOR_KIND else USECASE_KIND, out = out)
        ensureEndpointNode(right, fallbackKind = if (left.explicitKind == ACTOR_KIND) USECASE_KIND else USECASE_KIND, out = out)
        val edge = Edge(
            from = left.id,
            to = right.id,
            label = label,
            kind = when (op) {
                "..>", "<..", ".." -> EdgeKind.Dashed
                else -> EdgeKind.Solid
            },
            arrow = when (op) {
                "-->", "..>", "<|--" -> ArrowEnds.ToOnly
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

    private fun parseEndpoint(raw: String): EndpointSpec? {
        parseParenUsecaseSpec(raw)?.let { return EndpointSpec(NodeId(it.id), USECASE_KIND) }
        if (raw.startsWith(":") && raw.endsWith(":")) {
            val label = raw.removePrefix(":").removeSuffix(":").trim()
            if (label.isEmpty()) return null
            return EndpointSpec(NodeId(sanitizeId(label)), ACTOR_KIND)
        }
        if (raw.matches(Regex("[A-Za-z0-9_.:-]+"))) {
            return EndpointSpec(NodeId(raw), explicitKind = null)
        }
        return null
    }

    private fun ensureEndpointNode(endpoint: EndpointSpec, fallbackKind: String, out: MutableList<IrPatch>) {
        val existing = nodes[endpoint.id]
        val existingKind = existing?.payload?.get(KIND_KEY)
        val kind = endpoint.explicitKind ?: existingKind ?: fallbackKind
        if (existing == null) {
            val label = when (kind) {
                ACTOR_KIND -> endpoint.id.value
                else -> endpoint.id.value
            }
            ensureNode(endpoint.id.value, label, kind, out)
        } else if (endpoint.explicitKind != null && existingKind != endpoint.explicitKind) {
            nodes[endpoint.id] = existing.copy(
                shape = shapeFor(endpoint.explicitKind),
                style = styleFor(endpoint.explicitKind),
                payload = existing.payload + mapOf(KIND_KEY to endpoint.explicitKind),
            )
        }
    }

    private fun ensureNode(id: String, label: String, kind: String, out: MutableList<IrPatch>) {
        val nodeId = NodeId(id)
        val parent = clusterStack.lastOrNull()
        val existing = nodes[nodeId]
        val node = Node(
            id = nodeId,
            label = RichLabel.Plain(label),
            shape = shapeFor(kind),
            style = styleFor(kind),
            payload = buildMap {
                put(KIND_KEY, kind)
                parent?.let { put(PARENT_KEY, it.value) }
            },
        )
        nodes[nodeId] = if (existing == null) node else node.copy(
            payload = existing.payload + node.payload,
            label = if (label.isNotEmpty()) node.label else existing.label,
        )
        if (existing == null) out += IrPatch.AddNode(nodes[nodeId]!!)
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

    private fun parseQuotedOrSimple(body: String): AliasSpec? {
        val quotedAs = Regex("^\"([^\"]+)\"\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (quotedAs != null) return AliasSpec(id = quotedAs.groupValues[2], label = quotedAs.groupValues[1])
        val aliasQuoted = Regex("^([A-Za-z0-9_.:-]+)\\s+as\\s+\"([^\"]+)\"$").matchEntire(body)
        if (aliasQuoted != null) return AliasSpec(id = aliasQuoted.groupValues[1], label = aliasQuoted.groupValues[2])
        val simple = Regex("^([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (simple != null) return AliasSpec(id = simple.groupValues[1], label = simple.groupValues[1])
        return null
    }

    private fun parseUsecaseSpec(body: String): AliasSpec? {
        parseParenUsecaseSpec(body)?.let { return it }
        return parseQuotedOrSimple(body)
    }

    private fun parseParenUsecaseSpec(body: String): AliasSpec? {
        val parenAs = Regex("^\\(([^)]+)\\)\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (parenAs != null) return AliasSpec(id = parenAs.groupValues[2], label = parenAs.groupValues[1].trim())
        val plainParen = Regex("^\\(([^)]+)\\)$").matchEntire(body)
        if (plainParen != null) {
            val label = plainParen.groupValues[1].trim()
            return AliasSpec(id = sanitizeId(label), label = label)
        }
        return null
    }

    private fun sanitizeId(text: String): String =
        text.replace(Regex("[^A-Za-z0-9_.:-]+"), "_").trim('_').ifEmpty { "node_$seq" }

    private fun shapeFor(kind: String): NodeShape = when (kind) {
        ACTOR_KIND -> NodeShape.RoundedBox
        else -> NodeShape.Stadium
    }

    private fun styleFor(kind: String): NodeStyle = when (kind) {
        ACTOR_KIND -> NodeStyle(
            fill = null,
            stroke = ArgbColor(0xFF455A64.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF263238.toInt()),
        )
        else -> NodeStyle(
            fill = ArgbColor(0xFFE3F2FD.toInt()),
            stroke = ArgbColor(0xFF1565C0.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF0D47A1.toInt()),
        )
    }

    private fun clusterStyleFor(kind: String): ClusterStyle = when (kind) {
        "rectangle" -> ClusterStyle(
            fill = ArgbColor(0xFFF9FBE7.toInt()),
            stroke = ArgbColor(0xFF7CB342.toInt()),
            strokeWidth = 1.5f,
        )
        else -> ClusterStyle(
            fill = ArgbColor(0xFFF5F5F5.toInt()),
            stroke = ArgbColor(0xFF78909C.toInt()),
            strokeWidth = 1.5f,
        )
    }

    private fun hasRelationOperator(line: String): Boolean = findRelationOperator(line) != null

    private fun findRelationOperator(line: String): String? {
        for (candidate in RELATION_OPERATORS) {
            if (line.contains(candidate)) return candidate
        }
        return null
    }

    private fun errorBatch(message: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.ERROR, message, "PLANTUML-E006"))))

    private fun addDiagnostic(diagnostic: Diagnostic): IrPatch {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }
}
