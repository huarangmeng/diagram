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
 * Streaming parser for the PlantUML ArchiMate slice.
 *
 * Supported syntax:
 * - `archimate #LightBlue "Service" as S <<business-service>>`
 * - `archimate application-component "Application" as App`
 * - `group "Layer" as L { ... }`
 * - `A --> B : label`
 * - `Rel(A, B, "label")` and relationship-specific `Rel_*` variants
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
        const val ELEMENT_TYPE_KEY = "plantuml.archimate.elementType"
        const val STEREOTYPE_KEY = "plantuml.archimate.stereotype"
        const val PARENT_KEY = "plantuml.archimate.parent"
        private val ELEMENT = Regex("""^archimate\b\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val QUOTED_LABEL = Regex(""""([^"]+)"""")
        private val ALIAS = Regex("""\bas\s+([A-Za-z0-9_.:-]+)\b""", RegexOption.IGNORE_CASE)
        private val STEREOTYPE = Regex("""<<([^>]+)>>""")
        private val GROUP = Regex(
            """^group\s+(?:"([^"]+)"|([A-Za-z0-9_.:-]+))(?:\s+as\s+([A-Za-z0-9_.:-]+))?\s*\{\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val EDGE = Regex("""^([A-Za-z0-9_.:-]+)\s+([-.]+>)\s+([A-Za-z0-9_.:-]+)(?:\s*:\s*(.+))?$""")
        private val REL = Regex("""^Rel(?:_([A-Za-z0-9_]+))?\(\s*([A-Za-z0-9_.:-]+)\s*,\s*([A-Za-z0-9_.:-]+)(?:\s*,\s*"([^"]*)")?.*\)\s*$""")
    }

    private data class GroupDef(
        val id: NodeId,
        val label: String,
        val parent: NodeId?,
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val edges: MutableList<Edge> = ArrayList()
    private val relationTypes: MutableList<String> = ArrayList()
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

        ELEMENT.matchEntire(trimmed)?.let {
            val element = parseElement(trimmed) ?: return errorBatch("Invalid PlantUML archimate element: $trimmed")
            val fill = element.colorToken?.let(::parseColor) ?: colorForElementType(element.type, element.stereotype)
            nodes[element.id] = Node(
                id = element.id,
                label = RichLabel.Plain(element.label),
                shape = NodeShape.RoundedBox,
                style = NodeStyle(
                    fill = fill,
                    stroke = ArgbColor(0xFF455A64.toInt()),
                    strokeWidth = 1.4f,
                    textColor = ArgbColor(0xFF263238.toInt()),
                ),
                payload = mapOf(
                    KIND_KEY to "element",
                    ELEMENT_TYPE_KEY to element.type,
                    STEREOTYPE_KEY to element.stereotype,
                ) + buildMap {
                    groupStack.lastOrNull()?.let { put(PARENT_KEY, it.value) }
                },
            )
            return IrPatchBatch(seq, emptyList())
        }

        REL.matchEntire(trimmed)?.let { m ->
            addEdge(
                from = m.groupValues[2],
                to = m.groupValues[3],
                label = m.groupValues[4].ifBlank { null },
                relationType = normalizeRelationType(m.groupValues[1].ifBlank { "association" }),
                dashed = false,
            )
            return IrPatchBatch(seq, emptyList())
        }

        EDGE.matchEntire(trimmed)?.let { m ->
            addEdge(m.groupValues[1], m.groupValues[3], m.groupValues[4].ifBlank { null }, relationType = "directed", dashed = m.groupValues[2].contains('.'))
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

    fun relationTypesSnapshot(): List<String> = relationTypes.toList()

    private data class ElementDef(
        val id: NodeId,
        val label: String,
        val type: String,
        val stereotype: String,
        val colorToken: String?,
    )

    private fun parseElement(line: String): ElementDef? {
        val body = ELEMENT.matchEntire(line)?.groupValues?.get(1)?.trim() ?: return null
        val labelMatch = QUOTED_LABEL.find(body) ?: return null
        val label = labelMatch.groupValues[1].trim()
        val beforeLabel = body.substring(0, labelMatch.range.first).trim()
        val colorToken = beforeLabel.split(Regex("""\s+""")).firstOrNull { it.startsWith("#") }?.takeIf { it.length > 1 }
        val typeToken = beforeLabel.split(Regex("""\s+"""))
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
        val stereotype = STEREOTYPE.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val type = normalizeElementType(typeToken ?: stereotype.ifBlank { "archimate" })
        val id = NodeId(ALIAS.find(body)?.groupValues?.get(1)?.ifBlank { null } ?: PlantUmlTemporalSupport.slug(label))
        return ElementDef(
            id = id,
            label = label,
            type = type,
            stereotype = stereotype.ifBlank { type },
            colorToken = colorToken,
        )
    }

    private fun addEdge(from: String, to: String, label: String?, relationType: String, dashed: Boolean) {
        val fromId = NodeId(from)
        val toId = NodeId(to)
        ensurePlaceholder(fromId)
        ensurePlaceholder(toId)
        val style = edgeStyleFor(relationType, dashed)
        edges += Edge(
            from = fromId,
            to = toId,
            label = label?.let { RichLabel.Plain(it.trim()) },
            kind = if (style.dash != null) EdgeKind.Dashed else EdgeKind.Solid,
            arrow = arrowFor(relationType),
            style = style,
        )
        relationTypes += relationType
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
            payload = mapOf(KIND_KEY to "placeholder", ELEMENT_TYPE_KEY to "unknown") + buildMap {
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

    private fun normalizeElementType(raw: String): String {
        val token = raw.trim().removePrefix("<<").removeSuffix(">>").replace("_", "-").lowercase()
        return when {
            token.contains("business") -> token
            token.contains("application") -> token
            token.contains("technology") -> token
            token.contains("physical") -> token
            token.contains("motivation") -> token
            token.contains("strategy") -> token
            token.contains("implementation") || token.contains("migration") -> token
            token.contains("layer") -> "layer"
            token.isBlank() -> "archimate"
            else -> token
        }
    }

    private fun normalizeRelationType(raw: String): String {
        val token = raw.trim().replace("_", "-").lowercase()
        return when {
            token.contains("composition") -> "composition"
            token.contains("aggregation") -> "aggregation"
            token.contains("assignment") -> "assignment"
            token.contains("realization") || token.contains("realisation") -> "realization"
            token.contains("specialization") || token.contains("specialisation") -> "specialization"
            token.contains("serving") || token.contains("usedby") || token.contains("used-by") -> "serving"
            token.contains("access") -> "access"
            token.contains("influence") -> "influence"
            token.contains("triggering") -> "triggering"
            token.contains("flow") -> "flow"
            token.contains("association") -> "association"
            token.isBlank() -> "association"
            else -> token
        }
    }

    private fun edgeStyleFor(relationType: String, dashed: Boolean): EdgeStyle {
        val dash = when (relationType) {
            "realization", "access", "influence", "flow" -> listOf(6f, 4f)
            else -> if (dashed) listOf(6f, 4f) else null
        }
        val width = when (relationType) {
            "composition", "aggregation", "assignment", "specialization" -> 1.8f
            else -> 1.4f
        }
        val color = when (relationType) {
            "composition", "aggregation" -> ArgbColor(0xFF37474F.toInt())
            "assignment" -> ArgbColor(0xFF5D4037.toInt())
            "realization", "specialization" -> ArgbColor(0xFF455A64.toInt())
            "access" -> ArgbColor(0xFF6A1B9A.toInt())
            "flow", "triggering" -> ArgbColor(0xFF1565C0.toInt())
            "influence" -> ArgbColor(0xFFAD1457.toInt())
            else -> ArgbColor(0xFF607D8B.toInt())
        }
        return EdgeStyle(color = color, width = width, dash = dash)
    }

    private fun arrowFor(relationType: String): ArrowEnds =
        when (relationType) {
            "association", "composition", "aggregation" -> ArrowEnds.None
            else -> ArrowEnds.ToOnly
        }

    private fun colorForElementType(type: String, stereotype: String): ArgbColor =
        when {
            type.contains("business") || stereotype.contains("business", ignoreCase = true) -> ArgbColor(0xFFFFF3E0.toInt())
            type.contains("application") || stereotype.contains("application", ignoreCase = true) -> ArgbColor(0xFFE3F2FD.toInt())
            type.contains("technology") || stereotype.contains("technology", ignoreCase = true) -> ArgbColor(0xFFE8F5E9.toInt())
            type.contains("physical") || stereotype.contains("physical", ignoreCase = true) -> ArgbColor(0xFFE0F2F1.toInt())
            type.contains("motivation") || stereotype.contains("motivation", ignoreCase = true) -> ArgbColor(0xFFF3E5F5.toInt())
            type.contains("strategy") || stereotype.contains("strategy", ignoreCase = true) -> ArgbColor(0xFFFFEBEE.toInt())
            type.contains("implementation") || type.contains("migration") || stereotype.contains("implementation", ignoreCase = true) -> ArgbColor(0xFFFFF8E1.toInt())
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
