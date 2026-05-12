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
 * - `group NAME { ... }` inside a network
 * - `address = "..."` / `address = ...`
 * - node declarations: `web;`, `web [address = "...", description = "..."];`
 * - repeated node names across networks are connected as multi-homed endpoints.
 */
@DiagramApi
class PlantUmlNetworkParser {
    companion object {
        const val KIND_KEY = "plantuml.network.kind"
        const val NETWORK_ID_KEY = "plantuml.network.id"
        const val ADDRESS_KEY = "plantuml.network.address"
        const val DESCRIPTION_KEY = "plantuml.network.description"
        const val NETWORK_KIND = "network"
        const val GROUP_KIND = "group"
        const val NODE_KIND = "node"
        private val NETWORK_START = Regex("""^network\s+("[^"]+"|[A-Za-z0-9_.:-]+)\s*\{\s*$""", RegexOption.IGNORE_CASE)
        private val GROUP_START = Regex("""^group\s+("[^"]+"|[A-Za-z0-9_.:-]+)\s*\{\s*$""", RegexOption.IGNORE_CASE)
        private val ADDRESS = Regex("""^address\s*=\s*(.+)$""", RegexOption.IGNORE_CASE)
        private val NODE_LINE = Regex("""^("[^"]+"|[A-Za-z0-9_.:-]+)(?:\s*\[(.*)])?\s*;?$""")
    }

    private data class NetworkDef(
        val id: NodeId,
        val name: String,
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
        if (networkMatch != null) {
            val name = unquote(networkMatch.groupValues[1])
            val id = NodeId("nw_${slug(name)}")
            val network = networks.getOrPut(id) { NetworkDef(id, name) }
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

        val nodeMatch = NODE_LINE.matchEntire(trimmed)
            ?: return errorBatch("Invalid PlantUML nwdiag node line: $trimmed")
        val name = unquote(nodeMatch.groupValues[1])
        val attrs = parseAttrs(nodeMatch.groupValues.getOrNull(2).orEmpty())
        val nodeId = NodeId("nw_${slug(activeNetwork.name)}_${slug(name)}")
        val label = buildLabel(name, attrs, activeNetwork)
        val node = Node(
            id = nodeId,
            label = RichLabel.Plain(label),
            shape = NodeShape.RoundedBox,
            style = NodeStyle(
                fill = ArgbColor(0xFFE3F2FD.toInt()),
                stroke = ArgbColor(0xFF1976D2.toInt()),
                strokeWidth = 1.5f,
                textColor = ArgbColor(0xFF0D47A1.toInt()),
            ),
            payload = buildMap {
                put(KIND_KEY, NODE_KIND)
                put(NETWORK_ID_KEY, activeNetwork.id.value)
                attrs["address"]?.let { put(ADDRESS_KEY, it) }
                attrs["description"]?.let { put(DESCRIPTION_KEY, it) }
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
                        listOfNotNull(NETWORK_KIND, network.name, network.address?.let { "address: $it" })
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
                        fill = ArgbColor(0xFFF6F8FA.toInt()),
                        stroke = ArgbColor(0xFF607D8B.toInt()),
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
            attrs["label"],
            if (!attrs.containsKey("address")) network.address?.let { "net: $it" } else null,
        ).joinToString("\n")

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

    private fun slug(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9_.:-]+"), "_").trim('_').ifBlank { "node" }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(severity = Severity.ERROR, message = message, code = "PLANTUML-E014")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
