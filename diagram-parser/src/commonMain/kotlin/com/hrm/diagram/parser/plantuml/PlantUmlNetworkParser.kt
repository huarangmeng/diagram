package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Cluster
import com.hrm.diagram.core.ir.ClusterStyle
import com.hrm.diagram.core.ir.Diagnostic
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
 * Streaming parser for the PlantUML `nwdiag` network diagram slice.
 *
 * Supported syntax:
 * - `nwdiag { ... }`
 * - `network NAME { ... }`
 * - `inet { ... }` / `inet NAME { ... }`
 * - `group NAME { ... }` inside a network
 * - `address = "..."` / `address = ...`
 * - node declarations: `web;`, `web [address = "...", description = "...", shape = cloud];`
 * - repeated node names across networks are connected as multi-homed endpoints.
 */
@DiagramApi
class PlantUmlNetworkParser {
    companion object {
        const val KIND_KEY = "plantuml.network.kind"
        const val NETWORK_ID_KEY = "plantuml.network.id"
        const val ADDRESS_KEY = "plantuml.network.address"
        const val DESCRIPTION_KEY = "plantuml.network.description"
        const val SHAPE_KEY = "plantuml.network.shape"
        const val LABEL_KEY = "plantuml.network.label"
        const val NETWORK_KIND = "network"
        const val INET_KIND = "inet"
        const val GROUP_KIND = "group"
        const val NODE_KIND = "node"
        private val NETWORK_START = Regex("""^network\s+("[^"]+"|[A-Za-z0-9_.:-]+)\s*\{\s*$""", RegexOption.IGNORE_CASE)
        private val INET_START = Regex("""^inet(?:\s+("[^"]+"|[A-Za-z0-9_.:-]+))?\s*\{\s*$""", RegexOption.IGNORE_CASE)
        private val GROUP_START = Regex("""^group\s+("[^"]+"|[A-Za-z0-9_.:-]+)\s*\{\s*$""", RegexOption.IGNORE_CASE)
        private val ADDRESS = Regex("""^address\s*=\s*(.+)$""", RegexOption.IGNORE_CASE)
        private val EDGE_LINE = Regex("""^("[^"]+"|[A-Za-z0-9_.:-]+)\s*(--|<-->|<->|->)\s*("[^"]+"|[A-Za-z0-9_.:-]+)(?:\s*:\s*(.+?))?\s*;?$""")
        private val NODE_LINE = Regex("""^("[^"]+"|[A-Za-z0-9_.:-]+)(?:\s*\[(.*)\])?\s*;?$""")
    }

    private data class NetworkDef(
        val id: NodeId,
        val name: String,
        val kind: String = NETWORK_KIND,
        var address: String? = null,
        val children: MutableList<NodeId> = ArrayList(),
        val groups: MutableList<GroupDef> = ArrayList(),
    )

    private data class GroupDef(
        val id: NodeId,
        val name: String,
        val children: MutableList<NodeId> = ArrayList(),
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val networks: LinkedHashMap<NodeId, NetworkDef> = LinkedHashMap()
    private val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val edges: MutableList<Edge> = ArrayList()
    private val firstNodeByName: MutableMap<String, NodeId> = LinkedHashMap()
    private var seq: Long = 0
    private var inNwdiag = false
    private var currentNetwork: NetworkDef? = null
    private var currentGroup: GroupDef? = null

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            return IrPatchBatch(seq, emptyList())
        }
        if (!inNwdiag) {
            if (trimmed.equals("nwdiag {", ignoreCase = true) || trimmed.equals("nwdiag{", ignoreCase = true)) {
                inNwdiag = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Invalid PlantUML nwdiag line before nwdiag block: $trimmed")
        }
        if (trimmed == "}") {
            if (currentGroup != null) {
                currentGroup = null
            } else if (currentNetwork != null) {
                currentNetwork = null
            } else {
                inNwdiag = false
            }
            return IrPatchBatch(seq, emptyList())
        }

        val networkMatch = NETWORK_START.matchEntire(trimmed)
        val inetMatch = INET_START.matchEntire(trimmed)
        if (inetMatch != null || networkMatch != null) {
            val isInet = inetMatch != null
            val name = inetMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::unquote)
                ?: networkMatch?.groupValues?.getOrNull(1)?.let(::unquote)
                ?: "inet"
            val id = NodeId(if (isInet) "nw_inet_${slug(name)}" else "nw_${slug(name)}")
            val network = networks.getOrPut(id) { NetworkDef(id, name, if (isInet) INET_KIND else NETWORK_KIND) }
            currentNetwork = network
            return IrPatchBatch(seq, emptyList())
        }

        val activeNetwork = currentNetwork ?: return errorBatch("PlantUML nwdiag node must be inside a network block: $trimmed")
        val groupMatch = GROUP_START.matchEntire(trimmed)
        if (groupMatch != null) {
            val name = unquote(groupMatch.groupValues[1])
            val id = NodeId("nw_${slug(activeNetwork.name)}_group_${slug(name)}")
            val group = activeNetwork.groups.firstOrNull { it.id == id } ?: GroupDef(id, name).also { activeNetwork.groups += it }
            currentGroup = group
            return IrPatchBatch(seq, emptyList())
        }
        ADDRESS.matchEntire(trimmed)?.let {
            activeNetwork.address = unquote(it.groupValues[1].trim().removeSuffix(";"))
            return IrPatchBatch(seq, emptyList())
        }

        EDGE_LINE.matchEntire(trimmed)?.let { m ->
            addExplicitEdge(unquote(m.groupValues[1]), unquote(m.groupValues[3]), m.groupValues[2], m.groupValues[4].ifBlank { null }?.trim()?.removeSuffix(";"))
            return IrPatchBatch(seq, emptyList())
        }

        val nodeMatch = NODE_LINE.matchEntire(trimmed)
            ?: return errorBatch("Invalid PlantUML nwdiag node line: $trimmed")
        val name = unquote(nodeMatch.groupValues[1])
        val attrs = parseAttrs(nodeMatch.groupValues.getOrNull(2).orEmpty())
        val nodeId = NodeId("nw_${slug(activeNetwork.name)}_${slug(name)}")
        val label = buildLabel(name, attrs, activeNetwork)
        val node = Node(
            id = nodeId,
            label = RichLabel.Plain(label),
            shape = shapeOf(attrs["shape"], activeNetwork),
            style = styleOf(attrs, activeNetwork),
            payload = buildMap {
                put(KIND_KEY, NODE_KIND)
                put(NETWORK_ID_KEY, activeNetwork.id.value)
                attrs["address"]?.let { put(ADDRESS_KEY, it) }
                attrs["description"]?.let { put(DESCRIPTION_KEY, it) }
                attrs["shape"]?.let { put(SHAPE_KEY, it.lowercase()) }
                attrs["label"]?.let { put(LABEL_KEY, it) }
            },
        )
        nodes[nodeId] = node
        val activeChildren = currentGroup?.children ?: activeNetwork.children
        if (nodeId !in activeChildren) activeChildren += nodeId
        firstNodeByName[name]?.let { first ->
            if (first != nodeId && edges.none { it.from == first && it.to == nodeId }) {
                edges += Edge(
                    from = first,
                    to = nodeId,
                    kind = EdgeKind.Dashed,
                    arrow = ArrowEnds.None,
                    style = EdgeStyle(color = ArgbColor(0xFF78909C.toInt()), dash = listOf(6f, 4f)),
                )
            }
        } ?: run {
            firstNodeByName[name] = nodeId
        }
        return IrPatchBatch(seq, emptyList())
    }

    private fun addExplicitEdge(fromName: String, toName: String, op: String, label: String?) {
        val from = resolveNodeId(fromName)
        val to = resolveNodeId(toName)
        edges += Edge(
            from = from,
            to = to,
            label = label?.let { RichLabel.Plain(unquote(it.trim())) },
            kind = EdgeKind.Solid,
            arrow = when (op) {
                "->" -> ArrowEnds.ToOnly
                "<->", "<-->" -> ArrowEnds.Both
                else -> ArrowEnds.None
            },
            style = EdgeStyle(color = ArgbColor(0xFF546E7A.toInt()), width = 1.4f),
        )
    }

    private fun resolveNodeId(name: String): NodeId {
        val existing = firstNodeByName[name]
        if (existing != null) return existing
        val active = currentNetwork
        val id = if (active != null) NodeId("nw_${slug(active.name)}_${slug(name)}") else NodeId("nw_${slug(name)}")
        if (id !in nodes) {
            nodes[id] = Node(
                id = id,
                label = RichLabel.Plain(name),
                shape = NodeShape.RoundedBox,
                style = NodeStyle(fill = ArgbColor(0xFFECEFF1.toInt()), stroke = ArgbColor(0xFF78909C.toInt()), textColor = ArgbColor(0xFF263238.toInt())),
                payload = mapOf(KIND_KEY to NODE_KIND),
            )
        }
        firstNodeByName[name] = id
        return id
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (!blockClosed || inNwdiag || currentNetwork != null) {
            val d = Diagnostic(
                severity = Severity.ERROR,
                message = "Missing closing delimiter for PlantUML nwdiag block",
                code = "PLANTUML-E014",
            )
            diagnostics += d
            out += IrPatch.AddDiagnostic(d)
            inNwdiag = false
            currentNetwork = null
            currentGroup = null
        }
        return IrPatchBatch(seq, out)
    }

    fun snapshot(): GraphIR =
        GraphIR(
            nodes = nodes.values.toList(),
            edges = edges.toList(),
            clusters = networks.values.map { network ->
                Cluster(
                    id = network.id,
                    label = RichLabel.Plain(
                        listOfNotNull(network.kind, network.name, network.address?.let { "address: $it" })
                            .joinToString("\n"),
                    ),
                    children = network.children.toList(),
                    nestedClusters = network.groups.map { group ->
                        Cluster(
                            id = group.id,
                            label = RichLabel.Plain(
                                listOf(GROUP_KIND, group.name).joinToString("\n"),
                            ),
                            children = group.children.toList(),
                            style = ClusterStyle(
                                fill = ArgbColor(0xFFF8FAFC.toInt()),
                                stroke = ArgbColor(0xFF94A3B8.toInt()),
                                strokeWidth = 1.2f,
                            ),
                        )
                    },
                    style = ClusterStyle(
                        fill = if (network.kind == INET_KIND) ArgbColor(0xFFE0F7FA.toInt()) else ArgbColor(0xFFF6F8FA.toInt()),
                        stroke = if (network.kind == INET_KIND) ArgbColor(0xFF0097A7.toInt()) else ArgbColor(0xFF607D8B.toInt()),
                        strokeWidth = 1.4f,
                    ),
                )
            },
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = StyleHints(),
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun buildLabel(name: String, attrs: Map<String, String>, network: NetworkDef): String =
        listOfNotNull(
            name,
            attrs["address"]?.let { "addr: $it" },
            attrs["description"],
            attrs["label"]?.takeIf { it != name },
            if (!attrs.containsKey("address")) network.address?.let { "net: $it" } else null,
        ).joinToString("\n")

    private fun shapeOf(raw: String?, network: NetworkDef): NodeShape {
        val shape = raw?.lowercase()?.replace("-", "_").orEmpty()
        return when {
            shape in setOf("cloud", "internet", "inet") || network.kind == INET_KIND -> NodeShape.Cloud
            shape in setOf("database", "db", "storage") -> NodeShape.Cylinder
            shape in setOf("queue", "mq") -> NodeShape.Hexagon
            shape in setOf("actor", "person", "client", "user") -> NodeShape.Actor
            shape in setOf("component", "server", "node") -> NodeShape.Component
            shape in setOf("box", "rectangle") -> NodeShape.Box
            else -> NodeShape.RoundedBox
        }
    }

    private fun styleOf(attrs: Map<String, String>, network: NetworkDef): NodeStyle {
        val fill = parseColor(attrs["color"] ?: attrs["fill"] ?: attrs["bgcolor"])
            ?: when {
                network.kind == INET_KIND -> ArgbColor(0xFFE0F7FA.toInt())
                attrs["shape"]?.contains("database", ignoreCase = true) == true || attrs["shape"]?.equals("db", ignoreCase = true) == true -> ArgbColor(0xFFE8F5E9.toInt())
                attrs["shape"]?.contains("queue", ignoreCase = true) == true -> ArgbColor(0xFFF3E5F5.toInt())
                else -> ArgbColor(0xFFE3F2FD.toInt())
            }
        val stroke = parseColor(attrs["bordercolor"] ?: attrs["linecolor"])
            ?: when {
                network.kind == INET_KIND -> ArgbColor(0xFF0097A7.toInt())
                attrs["shape"]?.contains("database", ignoreCase = true) == true || attrs["shape"]?.equals("db", ignoreCase = true) == true -> ArgbColor(0xFF43A047.toInt())
                attrs["shape"]?.contains("queue", ignoreCase = true) == true -> ArgbColor(0xFF8E24AA.toInt())
                else -> ArgbColor(0xFF1976D2.toInt())
            }
        return NodeStyle(
            fill = fill,
            stroke = stroke,
            strokeWidth = 1.5f,
            textColor = parseColor(attrs["textcolor"] ?: attrs["fontcolor"]) ?: ArgbColor(0xFF0D47A1.toInt()),
        )
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in splitCommaAware(raw)) {
            val split = part.indexOf('=')
            if (split <= 0) continue
            val key = part.substring(0, split).trim().lowercase()
            val value = unquote(part.substring(split + 1).trim())
            out[key] = value
        }
        return out
    }

    private fun splitCommaAware(raw: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in raw) {
            when {
                c == '"' -> {
                    quoted = !quoted
                    cur.append(c)
                }
                c == ',' && !quoted -> {
                    out += cur.toString().trim()
                    cur.clear()
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString().trim()
        return out
    }

    private fun unquote(raw: String): String =
        raw.trim().removeSurrounding("\"").removeSurrounding("'")

    private fun parseColor(raw: String?): ArgbColor? {
        val s = raw?.trim()?.removeSurrounding("\"")?.removePrefix("#") ?: return null
        if (s.length == 6 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return ArgbColor((0xFF000000 or s.toLong(16)).toInt())
        }
        return when (s.lowercase()) {
            "blue" -> ArgbColor(0xFF2196F3.toInt())
            "green" -> ArgbColor(0xFF4CAF50.toInt())
            "red" -> ArgbColor(0xFFF44336.toInt())
            "orange" -> ArgbColor(0xFFFF9800.toInt())
            "purple" -> ArgbColor(0xFF9C27B0.toInt())
            "gray", "grey" -> ArgbColor(0xFF9E9E9E.toInt())
            else -> null
        }
    }

    private fun slug(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9_.:-]+"), "_").trim('_').ifBlank { "node" }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(severity = Severity.ERROR, message = message, code = "PLANTUML-E014")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
