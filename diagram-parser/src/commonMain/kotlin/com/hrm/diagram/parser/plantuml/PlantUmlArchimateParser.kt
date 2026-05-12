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
 * Streaming parser for the minimal PlantUML ArchiMate slice.
 *
 * Supported syntax:
 * - `archimate #LightBlue "Service" as S <<business-service>>`
 * - `archimate "Application" as App`
 * - `group "Layer" as L { ... }`
 * - `A --> B : label`
 * - `Rel(A, B, "label")` and `Rel_*` variants
 *
 * Example:
 * ```kotlin
 * val parser = PlantUmlArchimateParser()
 * parser.acceptLine("archimate #LightBlue \"Service\" as S <<business-service>>")
 * val ir = parser.snapshot()
 * ```
 */
@DiagramApi
class PlantUmlArchimateParser {
    companion object {
        const val KIND_KEY = "plantuml.archimate.kind"
        const val STEREOTYPE_KEY = "plantuml.archimate.stereotype"
        const val PARENT_KEY = "plantuml.archimate.parent"
        private val ELEMENT = Regex(
            """^archimate(?:\s+(#[A-Za-z0-9_]+))?\s+"([^"]+)"(?:\s+as\s+([A-Za-z0-9_.:-]+))?(?:\s+<<([^>]+)>>)?\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val GROUP = Regex(
            """^group\s+(?:"([^"]+)"|([A-Za-z0-9_.:-]+))(?:\s+as\s+([A-Za-z0-9_.:-]+))?\s*\{\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val EDGE = Regex("""^([A-Za-z0-9_.:-]+)\s+([-.]+>)\s+([A-Za-z0-9_.:-]+)(?:\s*:\s*(.+))?$""")
        private val REL = Regex("""^Rel(?:_[A-Za-z0-9_]+)?\(\s*([A-Za-z0-9_.:-]+)\s*,\s*([A-Za-z0-9_.:-]+)(?:\s*,\s*"([^"]*)")?.*\)\s*$""")
    }

    private data class GroupDef(
        val id: NodeId,
        val label: String,
        val parent: NodeId?,
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val edges: MutableList<Edge> = ArrayList()
    private val groups: LinkedHashMap<NodeId, GroupDef> = LinkedHashMap()
    private val groupStack: MutableList<NodeId> = ArrayList()
    private var seq: Long = 0L

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) return IrPatchBatch(seq, emptyList())
        if (trimmed.startsWith("!")) return IrPatchBatch(seq, emptyList())
        if (trimmed == "}") {
            if (groupStack.isEmpty()) return errorBatch("Unexpected '}' in PlantUML archimate diagram")
            groupStack.removeAt(groupStack.lastIndex)
            return IrPatchBatch(seq, emptyList())
        }

        GROUP.matchEntire(trimmed)?.let { m ->
            val label = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
            val id = NodeId(m.groupValues[3].ifBlank { "arch_group_${PlantUmlTemporalSupport.slug(label)}" })
            groups[id] = GroupDef(id = id, label = label, parent = groupStack.lastOrNull())
            groupStack += id
            return IrPatchBatch(seq, emptyList())
        }

        ELEMENT.matchEntire(trimmed)?.let { m ->
            val colorToken = m.groupValues[1].ifBlank { null }
            val label = m.groupValues[2].trim()
            val id = NodeId(m.groupValues[3].ifBlank { PlantUmlTemporalSupport.slug(label) })
            val stereotype = m.groupValues[4].ifBlank { "archimate" }
            val fill = colorToken?.let(::parseColor) ?: colorForStereotype(stereotype)
            nodes[id] = Node(
                id = id,
                label = RichLabel.Plain(label),
                shape = NodeShape.RoundedBox,
                style = NodeStyle(
                    fill = fill,
                    stroke = ArgbColor(0xFF455A64.toInt()),
                    strokeWidth = 1.4f,
                    textColor = ArgbColor(0xFF263238.toInt()),
                ),
                payload = mapOf(
                    KIND_KEY to "element",
                    STEREOTYPE_KEY to stereotype,
                ) + buildMap {
                    groupStack.lastOrNull()?.let { put(PARENT_KEY, it.value) }
                },
            )
            return IrPatchBatch(seq, emptyList())
        }

        REL.matchEntire(trimmed)?.let { m ->
            addEdge(m.groupValues[1], m.groupValues[2], m.groupValues[3].ifBlank { null }, dashed = false)
            return IrPatchBatch(seq, emptyList())
        }

        EDGE.matchEntire(trimmed)?.let { m ->
            addEdge(m.groupValues[1], m.groupValues[3], m.groupValues[4].ifBlank { null }, dashed = m.groupValues[2].contains('.'))
            return IrPatchBatch(seq, emptyList())
        }

        return errorBatch("Invalid PlantUML archimate line: $trimmed")
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (groupStack.isNotEmpty()) {
            val d = Diagnostic(Severity.ERROR, "Unclosed PlantUML archimate group block", "PLANTUML-E018")
            diagnostics += d
            out += IrPatch.AddDiagnostic(d)
            groupStack.clear()
        }
        if (!blockClosed) {
            val d = Diagnostic(Severity.ERROR, "Missing @enduml closing delimiter for archimate diagram", "PLANTUML-E018")
            diagnostics += d
            out += IrPatch.AddDiagnostic(d)
        }
        return IrPatchBatch(seq, out)
    }

    fun snapshot(): GraphIR =
        GraphIR(
            nodes = nodes.values.toList(),
            edges = edges.toList(),
            clusters = buildClusters(parent = null),
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = StyleHints(extras = mapOf("plantuml.graph.kind" to "archimate")),
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun addEdge(from: String, to: String, label: String?, dashed: Boolean) {
        val fromId = NodeId(from)
        val toId = NodeId(to)
        ensurePlaceholder(fromId)
        ensurePlaceholder(toId)
        edges += Edge(
            from = fromId,
            to = toId,
            label = label?.let { RichLabel.Plain(it.trim()) },
            kind = if (dashed) EdgeKind.Dashed else EdgeKind.Solid,
            arrow = ArrowEnds.ToOnly,
            style = EdgeStyle(color = ArgbColor(0xFF607D8B.toInt()), dash = if (dashed) listOf(6f, 4f) else null),
        )
    }

    private fun ensurePlaceholder(id: NodeId) {
        if (id in nodes) return
        nodes[id] = Node(
            id = id,
            label = RichLabel.Plain(id.value),
            shape = NodeShape.RoundedBox,
            style = NodeStyle(
                fill = ArgbColor(0xFFECEFF1.toInt()),
                stroke = ArgbColor(0xFF78909C.toInt()),
                textColor = ArgbColor(0xFF263238.toInt()),
            ),
            payload = mapOf(KIND_KEY to "placeholder") + buildMap {
                groupStack.lastOrNull()?.let { put(PARENT_KEY, it.value) }
            },
        )
    }

    private fun buildClusters(parent: NodeId?): List<Cluster> =
        groups.values.filter { it.parent == parent }.map { group ->
            val nested = buildClusters(group.id)
            val childNodes = nodes.values.filter { it.payload[PARENT_KEY] == group.id.value }.map { it.id }
            Cluster(
                id = group.id,
                label = RichLabel.Plain("group\n${group.label}"),
                children = childNodes,
                nestedClusters = nested,
                style = ClusterStyle(
                    fill = ArgbColor(0xFFF8FAFC.toInt()),
                    stroke = ArgbColor(0xFF78909C.toInt()),
                    strokeWidth = 1.2f,
                ),
            )
        }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "PLANTUML-E018")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }

    private fun colorForStereotype(stereotype: String): ArgbColor =
        when {
            stereotype.contains("business", ignoreCase = true) -> ArgbColor(0xFFFFF3E0.toInt())
            stereotype.contains("application", ignoreCase = true) -> ArgbColor(0xFFE3F2FD.toInt())
            stereotype.contains("technology", ignoreCase = true) -> ArgbColor(0xFFE8F5E9.toInt())
            stereotype.contains("motivation", ignoreCase = true) -> ArgbColor(0xFFF3E5F5.toInt())
            else -> ArgbColor(0xFFECEFF1.toInt())
        }

    private fun parseColor(token: String): ArgbColor? {
        val raw = token.removePrefix("#")
        namedColor(raw)?.let { return it }
        if (raw.length == 6 && raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return ArgbColor((0xFF000000 or raw.toLong(16)).toInt())
        }
        return null
    }

    private fun namedColor(name: String): ArgbColor? =
        when (name.lowercase()) {
            "lightblue" -> ArgbColor(0xFFADD8E6.toInt())
            "lightgreen" -> ArgbColor(0xFF90EE90.toInt())
            "lightyellow" -> ArgbColor(0xFFFFFFE0.toInt())
            "lightgrey", "lightgray" -> ArgbColor(0xFFD3D3D3.toInt())
            "orange" -> ArgbColor(0xFFFFA500.toInt())
            "wheat" -> ArgbColor(0xFFF5DEB3.toInt())
            else -> null
        }
}
