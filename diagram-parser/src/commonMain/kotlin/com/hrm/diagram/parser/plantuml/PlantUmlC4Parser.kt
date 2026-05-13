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
import com.hrm.diagram.core.ir.Port
import com.hrm.diagram.core.ir.PortId
import com.hrm.diagram.core.ir.PortSide
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Streaming parser for PlantUML C4-PlantUML macros.
 *
 * Supported syntax:
 * - `!include <C4/...>` / `!includeurl ...` lines are ignored as macro imports
 * - headers: `C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`
 * - elements: `Person*`, `System*`, `Container*`, `Component*`, `Deployment_Node`
 * - boundaries: `Boundary`, `Enterprise_Boundary`, `System_Boundary`, `Container_Boundary`, `Deployment_Node`
 * - relations: `Rel`, `BiRel`, `RelIndex`, `Rel_U/D/L/R/Back`, `BiRel_U/D/L/R`
 * - layout helpers: `Lay_U/D/L/R`, `UpdateLayoutConfig`
 * - style macros: `AddElementTag`, `AddRelTag`, `UpdateElementStyle`, `UpdateRelStyle`
 * - legend macros: `SHOW_LEGEND()` / `LAYOUT_WITH_LEGEND()`
 */
@DiagramApi
class PlantUmlC4Parser {
    companion object {
        const val KIND_KEY = "plantuml.c4.kind"
        const val STEREOTYPE_KEY = "plantuml.c4.stereotype"
        const val TECHNOLOGY_KEY = "plantuml.c4.technology"
        const val DESCRIPTION_KEY = "plantuml.c4.description"
        const val PARENT_KEY = "plantuml.c4.parent"
        const val TAGS_KEY = "plantuml.c4.tags"
        const val LINK_KEY = "plantuml.c4.link"
        const val LEGEND_KEY = "plantuml.c4.legend"
        const val EXTERNAL_KEY = "plantuml.c4.external"

        val HEADERS = setOf("C4Context", "C4Container", "C4Component", "C4Dynamic", "C4Deployment")
        val ELEMENT_NAMES = setOf(
            "Person", "Person_Ext",
            "System", "System_Ext", "SystemDb", "SystemDb_Ext", "SystemQueue", "SystemQueue_Ext",
            "Container", "Container_Ext", "ContainerDb", "ContainerDb_Ext", "ContainerQueue", "ContainerQueue_Ext",
            "Component", "Component_Ext", "ComponentDb", "ComponentDb_Ext", "ComponentQueue", "ComponentQueue_Ext",
            "Deployment_Node",
        )
        val BOUNDARY_NAMES = setOf("Boundary", "Enterprise_Boundary", "System_Boundary", "Container_Boundary", "Deployment_Node", "Node", "Node_L", "Node_R")
        val REL_NAMES = setOf(
            "Rel", "BiRel", "RelIndex",
            "Rel_U", "Rel_Up", "Rel_D", "Rel_Down", "Rel_L", "Rel_Left", "Rel_R", "Rel_Right", "Rel_Back",
            "BiRel_U", "BiRel_Up", "BiRel_D", "BiRel_Down", "BiRel_L", "BiRel_Left", "BiRel_R", "BiRel_Right",
        )
        val LAYOUT_NAMES = setOf("Lay_U", "Lay_Up", "Lay_D", "Lay_Down", "Lay_L", "Lay_Left", "Lay_R", "Lay_Right")
        val NOOP_NAMES = setOf("SHOW_FLOATING_LEGEND", "HIDE_STEREOTYPE", "SHOW_PERSON_OUTLINE", "SHOW_BOUNDARY")
    }

    data class C4EdgePresentation(
        val textColor: ArgbColor? = null,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
    )

    private data class ParsedCall(val name: String, val args: List<String>)
    private data class BoundaryDef(val id: NodeId, val title: String, val type: String, val parent: NodeId?, val tags: List<String>)
    private data class TagStyle(
        val fill: ArgbColor? = null,
        val stroke: ArgbColor? = null,
        val text: ArgbColor? = null,
        val shape: NodeShape? = null,
        val legendText: String? = null,
    )
    private data class RelTagStyle(
        val color: ArgbColor? = null,
        val text: ArgbColor? = null,
        val width: Float? = null,
        val dash: List<Float>? = null,
        val offsetX: Float? = null,
        val offsetY: Float? = null,
        val legendText: String? = null,
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val edges: MutableList<Edge> = ArrayList()
    private val boundaries: LinkedHashMap<NodeId, BoundaryDef> = LinkedHashMap()
    private val boundaryStack: MutableList<NodeId> = ArrayList()
    private val elementTagStyles: LinkedHashMap<String, TagStyle> = LinkedHashMap()
    private val relTagStyles: LinkedHashMap<String, RelTagStyle> = LinkedHashMap()
    private val elementStyleOverrides: LinkedHashMap<NodeId, TagStyle> = LinkedHashMap()
    private val relStyleOverrides: LinkedHashMap<Pair<NodeId, NodeId>, RelTagStyle> = LinkedHashMap()
    private val nodeLinks: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val relLinks: LinkedHashMap<Pair<NodeId, NodeId>, String> = LinkedHashMap()
    private val elementTags: LinkedHashMap<NodeId, List<String>> = LinkedHashMap()
    private val boundaryTags: LinkedHashMap<NodeId, List<String>> = LinkedHashMap()
    private val relTags: LinkedHashMap<Pair<NodeId, NodeId>, List<String>> = LinkedHashMap()
    private var latestEdgePresentation: Map<Int, C4EdgePresentation> = emptyMap()
    private var latestEdgeLinks: Map<Int, String> = emptyMap()
    private var direction: Direction = Direction.LR
    private var diagramKind = "C4Context"
    private val layoutExtras: LinkedHashMap<String, String> = linkedMapOf("plantuml.graph.kind" to "c4", "c4.diagramKind" to diagramKind)
    private var title: String? = null
    private var seq: Long = 0L

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) return IrPatchBatch(seq, emptyList())
        if (trimmed.startsWith("!include", ignoreCase = true)) return IrPatchBatch(seq, emptyList())
        if (trimmed in HEADERS) {
            diagramKind = trimmed
            layoutExtras["c4.diagramKind"] = diagramKind
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.startsWith("title ", ignoreCase = true)) {
            title = unquote(trimmed.substringAfter(' ').trim())
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed == "}") {
            if (boundaryStack.isNotEmpty()) boundaryStack.removeAt(boundaryStack.lastIndex) else return errorBatch("Unexpected '}' in C4 diagram")
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.endsWith("{")) {
            return parseBoundaryLine(trimmed.removeSuffix("{").trim())
        }
        val call = parseCall(trimmed) ?: return errorBatch("Invalid C4 statement syntax: $trimmed")
        return when (call.name) {
            in ELEMENT_NAMES -> parseElement(call)
            in REL_NAMES -> parseRelation(call)
            in LAYOUT_NAMES -> parseLayoutRelation(call)
            "AddElementTag" -> parseAddElementTag(call)
            "AddRelTag" -> parseAddRelTag(call)
            "UpdateElementStyle" -> parseUpdateElementStyle(call)
            "UpdateRelStyle" -> parseUpdateRelStyle(call)
            "UpdateLayoutConfig" -> parseUpdateLayoutConfig(call)
            "SHOW_LEGEND", "ShowLegend", "LAYOUT_WITH_LEGEND" -> parseLegend()
            in NOOP_NAMES -> IrPatchBatch(seq, emptyList())
            else -> errorBatch("Unknown C4 statement '${call.name}'")
        }
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (boundaryStack.isNotEmpty()) {
            val d = Diagnostic(Severity.ERROR, "Unclosed C4 boundary block", "PLANTUML-E019")
            diagnostics += d
            out += IrPatch.AddDiagnostic(d)
            boundaryStack.clear()
        }
        if (!blockClosed) {
            val d = Diagnostic(Severity.ERROR, "Missing @enduml closing delimiter for C4 diagram", "PLANTUML-E019")
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
            title = title,
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = StyleHints(direction = direction, extras = layoutExtras.toMap()),
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    fun nodeLinkSnapshot(): Map<NodeId, String> = nodeLinks.toMap()

    fun edgePresentationSnapshot(): Map<Int, C4EdgePresentation> {
        rebuildEdgePresentation()
        return latestEdgePresentation
    }

    fun edgeLinkSnapshot(): Map<Int, String> {
        rebuildEdgePresentation()
        return latestEdgeLinks
    }

    private fun parseBoundaryLine(text: String): IrPatchBatch {
        val call = parseCall(text) ?: return errorBatch("Invalid C4 boundary syntax: $text")
        if (call.name !in BOUNDARY_NAMES) return errorBatch("Unknown C4 boundary '${call.name}'")
        val args = splitNamedArgs(call.args)
        val positional = args.first
        val named = args.second
        val alias = positional.getOrNull(0)?.trim().orEmpty()
        if (alias.isEmpty()) return errorBatch("C4 boundary alias is required")
        val id = NodeId(alias)
        val title = positional.getOrNull(1)?.let(::unquote).orEmpty().ifBlank { alias }
        val parent = boundaryStack.lastOrNull()
        val tags = parseTags(named["tags"])
        boundaries[id] = BoundaryDef(id, title, call.name, parent, tags)
        if (tags.isNotEmpty()) boundaryTags[id] = tags
        named["link"]?.takeIf { it.isNotBlank() }?.let { nodeLinks[id] = it }
        boundaryStack += id
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseElement(call: ParsedCall): IrPatchBatch {
        val args = splitNamedArgs(call.args)
        val positional = args.first
        val named = args.second
        val alias = positional.getOrNull(0)?.trim().orEmpty()
        if (alias.isEmpty()) return errorBatch("C4 element alias is required")
        val id = NodeId(alias)
        val label = positional.getOrNull(1)?.let(::unquote).orEmpty().ifBlank { alias }
        val (technology, description) = elementMeta(call.name, positional, named)
        val tags = parseTags(named["tags"])
        val style = applyElementStyleOverrides(id, applyTagStyle(defaultNodeStyle(call.name), tags))
        val shape = elementShapeOverride(id, tags) ?: shapeOf(call.name)
        val parent = boundaryStack.lastOrNull()
        val node = Node(
            id = id,
            label = RichLabel.Plain(buildElementLabel(stereotypeOf(call.name), label, technology, description)),
            shape = shape,
            style = style,
            ports = defaultPorts(),
            payload = buildMap {
                put(KIND_KEY, call.name)
                put(STEREOTYPE_KEY, stereotypeOf(call.name))
                technology?.let { put(TECHNOLOGY_KEY, it) }
                description?.let { put(DESCRIPTION_KEY, it) }
                if (call.name.endsWith("_Ext")) put(EXTERNAL_KEY, "true")
                parent?.let { put(PARENT_KEY, it.value) }
                if (tags.isNotEmpty()) put(TAGS_KEY, tags.joinToString(","))
                named["link"]?.takeIf { it.isNotBlank() }?.let { put(LINK_KEY, it) }
            },
        )
        nodes[id] = node
        if (tags.isNotEmpty()) elementTags[id] = tags
        named["link"]?.takeIf { it.isNotBlank() }?.let { nodeLinks[id] = it }
        return IrPatchBatch(seq, listOf(IrPatch.AddNode(node)))
    }

    private fun parseRelation(call: ParsedCall): IrPatchBatch {
        val args = splitNamedArgs(call.args)
        val positional = args.first
        val named = args.second
        val start = if (call.name == "RelIndex") 1 else 0
        val from = positional.getOrNull(start)?.trim().orEmpty()
        val to = positional.getOrNull(start + 1)?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) return errorBatch("C4 relationship endpoints are required")
        ensurePlaceholder(NodeId(from))
        ensurePlaceholder(NodeId(to))
        val label = positional.getOrNull(start + 2)?.let(::unquote)
        val tech = named["techn"] ?: positional.getOrNull(start + 3)?.let(::unquote)
        val relTagList = parseTags(named["tags"])
        val key = NodeId(from) to NodeId(to)
        val edgeStyle = applyRelStyleOverrides(
            key,
            applyRelTagStyle(
                EdgeStyle(
                    color = ArgbColor(0xFF546E7A.toInt()),
                    width = if (call.name.startsWith("BiRel")) 2f else 1.5f,
                    dash = if (call.name == "Rel_Back") listOf(7f, 5f) else null,
                ),
                relTagList,
            ),
        )
        val ports = relationPorts(call.name)
        val edge = Edge(
            from = NodeId(from),
            to = NodeId(to),
            label = listOfNotNull(label, tech?.takeIf { it.isNotBlank() }?.let { "[$it]" }).joinToString("\n").takeIf { it.isNotBlank() }?.let { RichLabel.Plain(it) },
            kind = if (call.name == "Rel_Back") EdgeKind.Dashed else EdgeKind.Solid,
            arrow = if (call.name.startsWith("BiRel")) ArrowEnds.Both else ArrowEnds.ToOnly,
            fromPort = ports.first,
            toPort = ports.second,
            style = edgeStyle,
        )
        edges += edge
        if (relTagList.isNotEmpty()) relTags[key] = relTagList
        named["link"]?.takeIf { it.isNotBlank() }?.let { relLinks[key] = it }
        rebuildEdgePresentation()
        return IrPatchBatch(seq, listOf(IrPatch.AddEdge(edge)))
    }

    private fun parseLayoutRelation(call: ParsedCall): IrPatchBatch {
        val args = splitNamedArgs(call.args)
        val from = args.first.getOrNull(0)?.trim().orEmpty()
        val to = args.first.getOrNull(1)?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) return errorBatch("C4 layout relationship endpoints are required")
        ensurePlaceholder(NodeId(from))
        ensurePlaceholder(NodeId(to))
        val ports = relationPorts(call.name.replace("Lay_", "Rel_").replace("Lay", "Rel"))
        val edge = Edge(
            from = NodeId(from),
            to = NodeId(to),
            kind = EdgeKind.Invisible,
            arrow = ArrowEnds.None,
            fromPort = ports.first,
            toPort = ports.second,
            style = EdgeStyle(color = ArgbColor(0x00000000), width = 0f),
        )
        edges += edge
        rebuildEdgePresentation()
        return IrPatchBatch(seq, listOf(IrPatch.AddEdge(edge)))
    }

    private fun parseAddElementTag(call: ParsedCall): IrPatchBatch {
        val positional = splitNamedArgs(call.args).first
        val named = parseNamedAndPositional(call.args, startIndex = 1, positionalKeys = listOf("bgColor", "fontColor", "borderColor", "shadowing", "shape", "sprite", "techn", "legendText", "legendSprite"))
        val tag = positional.getOrNull(0)?.let(::unquote)?.trim().orEmpty()
        if (tag.isEmpty()) return errorBatch("AddElementTag requires tag name")
        elementTagStyles[tag] = TagStyle(
            fill = parseColor(named["bgColor"]),
            stroke = parseColor(named["borderColor"]),
            text = parseColor(named["fontColor"]),
            shape = named["shape"]?.let(::parseNodeShapeHelper),
            legendText = named["legendText"]?.takeIf { it.isNotBlank() },
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseAddRelTag(call: ParsedCall): IrPatchBatch {
        val positional = splitNamedArgs(call.args).first
        val named = parseNamedAndPositional(call.args, startIndex = 1, positionalKeys = listOf("textColor", "lineColor", "lineStyle", "sprite", "techn", "legendText", "legendSprite"))
        val tag = positional.getOrNull(0)?.let(::unquote)?.trim().orEmpty()
        if (tag.isEmpty()) return errorBatch("AddRelTag requires tag name")
        relTagStyles[tag] = RelTagStyle(
            color = parseColor(named["lineColor"]),
            text = parseColor(named["textColor"]),
            width = parseLineWidth(named["lineStyle"]),
            dash = parseLineDash(named["lineStyle"]),
            legendText = named["legendText"]?.takeIf { it.isNotBlank() },
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseUpdateElementStyle(call: ParsedCall): IrPatchBatch {
        val positional = splitNamedArgs(call.args).first
        val named = parseNamedAndPositional(call.args, startIndex = 1, positionalKeys = listOf("bgColor", "fontColor", "borderColor", "shadowing", "shape"))
        val id = NodeId(positional.getOrNull(0)?.trim().orEmpty())
        if (id.value.isEmpty()) return errorBatch("UpdateElementStyle requires element alias")
        val override = TagStyle(
            fill = parseColor(named["bgColor"]),
            stroke = parseColor(named["borderColor"]),
            text = parseColor(named["fontColor"]),
            shape = named["shape"]?.let(::parseNodeShapeHelper),
        )
        elementStyleOverrides[id] = override
        nodes[id]?.let { node ->
            nodes[id] = node.copy(shape = override.shape ?: node.shape, style = applyElementStyle(override, node.style))
        }
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseUpdateRelStyle(call: ParsedCall): IrPatchBatch {
        val positional = splitNamedArgs(call.args).first
        val named = parseNamedAndPositional(call.args, startIndex = 2, positionalKeys = listOf("textColor", "lineColor", "offsetX", "offsetY", "lineStyle"))
        val from = NodeId(positional.getOrNull(0)?.trim().orEmpty())
        val to = NodeId(positional.getOrNull(1)?.trim().orEmpty())
        if (from.value.isEmpty() || to.value.isEmpty()) return errorBatch("UpdateRelStyle requires relationship endpoints")
        val override = RelTagStyle(
            color = parseColor(named["lineColor"]),
            text = parseColor(named["textColor"]),
            width = parseLineWidth(named["lineStyle"]),
            dash = parseLineDash(named["lineStyle"]),
            offsetX = named["offsetX"]?.toFloatOrNull(),
            offsetY = named["offsetY"]?.toFloatOrNull(),
        )
        relStyleOverrides[from to to] = override
        for (index in edges.indices) {
            val edge = edges[index]
            if (edge.from == from && edge.to == to) {
                edges[index] = edge.copy(style = applyRelStyle(override, edge.style))
            }
        }
        rebuildEdgePresentation()
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseUpdateLayoutConfig(call: ParsedCall): IrPatchBatch {
        val named = parseNamedAndPositional(call.args, startIndex = 0, positionalKeys = listOf("c4ShapeInRow", "c4BoundaryInRow"))
        named["c4ShapeInRow"]?.let { layoutExtras["c4.shapeInRow"] = it }
        named["c4BoundaryInRow"]?.let { layoutExtras["c4.boundaryInRow"] = it }
        named["layout"]?.let { layout ->
            direction = when (layout.uppercase()) {
                "TB", "TOP_BOTTOM" -> Direction.TB
                "BT", "BOTTOM_TOP" -> Direction.BT
                "RL", "RIGHT_LEFT" -> Direction.RL
                else -> Direction.LR
            }
        }
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseLegend(): IrPatchBatch {
        val id = NodeId("c4:legend")
        val node = Node(
            id = id,
            label = RichLabel.Plain(buildLegendEntries()),
            shape = NodeShape.RoundedBox,
            style = NodeStyle(fill = ArgbColor(0xFFFFFFFF.toInt()), stroke = ArgbColor(0xFF90A4AE.toInt()), strokeWidth = 1.2f, textColor = ArgbColor(0xFF263238.toInt())),
            payload = mapOf(KIND_KEY to "Legend", STEREOTYPE_KEY to "Legend", LEGEND_KEY to "true"),
        )
        nodes[id] = node
        return IrPatchBatch(seq, listOf(IrPatch.AddNode(node)))
    }

    private fun ensurePlaceholder(id: NodeId) {
        if (id in nodes) return
        nodes[id] = Node(
            id = id,
            label = RichLabel.Plain(id.value),
            shape = NodeShape.RoundedBox,
            style = NodeStyle(fill = ArgbColor(0xFFECEFF1.toInt()), stroke = ArgbColor(0xFF78909C.toInt()), textColor = ArgbColor(0xFF263238.toInt())),
            ports = defaultPorts(),
            payload = mapOf(KIND_KEY to "Placeholder", STEREOTYPE_KEY to "Unknown"),
        )
    }

    private fun buildClusters(parent: NodeId?): List<Cluster> =
        boundaries.values.filter { it.parent == parent }.map { boundary ->
            val nested = buildClusters(boundary.id)
            val childNodes = nodes.values.filter { it.payload[PARENT_KEY] == boundary.id.value }.map { it.id }
            Cluster(
                id = boundary.id,
                label = RichLabel.Plain("${boundary.type}\n${boundary.title}"),
                children = childNodes,
                nestedClusters = nested,
                style = applyClusterTagStyle(defaultClusterStyle(boundary.type), boundary.tags),
            )
        }

    private fun elementMeta(name: String, positional: List<String>, named: Map<String, String>): Pair<String?, String?> =
        if (name == "Deployment_Node") {
            (named["techn"] ?: positional.getOrNull(2)?.let(::unquote)?.takeIf { it.isNotBlank() }) to
                (named["descr"] ?: positional.getOrNull(3)?.let(::unquote)?.takeIf { it.isNotBlank() })
        } else if (name.startsWith("Container") || name.startsWith("Component")) {
            (named["techn"] ?: positional.getOrNull(2)?.let(::unquote)?.takeIf { it.isNotBlank() }) to
                (named["descr"] ?: positional.getOrNull(3)?.let(::unquote)?.takeIf { it.isNotBlank() })
        } else {
            null to (named["descr"] ?: positional.getOrNull(2)?.let(::unquote)?.takeIf { it.isNotBlank() })
        }

    private fun buildElementLabel(stereotype: String, label: String, tech: String?, desc: String?): String =
        listOfNotNull("[$stereotype]", label, tech?.let { "[$it]" }, desc).joinToString("\n")

    private fun stereotypeOf(name: String): String = when {
        name.endsWith("_Ext") -> "External ${stereotypeOf(name.removeSuffix("_Ext"))}"
        name.startsWith("Person") -> "Person"
        name.startsWith("SystemDb") -> "Database System"
        name.startsWith("SystemQueue") -> "Queue System"
        name.startsWith("System") -> "Software System"
        name.startsWith("ContainerDb") -> "Database Container"
        name.startsWith("ContainerQueue") -> "Queue Container"
        name.startsWith("Container") -> "Container"
        name.startsWith("ComponentDb") -> "Database Component"
        name.startsWith("ComponentQueue") -> "Queue Component"
        name.startsWith("Component") -> "Component"
        name == "Deployment_Node" -> "Deployment Node"
        else -> name
    }

    private fun shapeOf(name: String): NodeShape = when {
        name.contains("Db") -> NodeShape.Cylinder
        name.contains("Queue") -> NodeShape.Hexagon
        name.startsWith("Component") -> NodeShape.Component
        name.startsWith("Person") -> NodeShape.Stadium
        name == "Deployment_Node" -> NodeShape.Package
        else -> NodeShape.RoundedBox
    }

    private fun defaultNodeStyle(name: String): NodeStyle = when {
        name.startsWith("Person") -> NodeStyle(ArgbColor(0xFFE3F2FD.toInt()), ArgbColor(0xFF1565C0.toInt()), 1.4f, ArgbColor(0xFF0D47A1.toInt()))
        name.startsWith("System") -> NodeStyle(ArgbColor(0xFFE8F5E9.toInt()), ArgbColor(0xFF2E7D32.toInt()), 1.4f, ArgbColor(0xFF1B5E20.toInt()))
        name.startsWith("Container") -> NodeStyle(ArgbColor(0xFFFFF3E0.toInt()), ArgbColor(0xFFEF6C00.toInt()), 1.4f, ArgbColor(0xFFE65100.toInt()))
        name.startsWith("Component") -> NodeStyle(ArgbColor(0xFFF3E5F5.toInt()), ArgbColor(0xFF7B1FA2.toInt()), 1.4f, ArgbColor(0xFF4A148C.toInt()))
        name == "Deployment_Node" -> NodeStyle(ArgbColor(0xFFECEFF1.toInt()), ArgbColor(0xFF546E7A.toInt()), 1.4f, ArgbColor(0xFF263238.toInt()))
        else -> NodeStyle.Default
    }

    private fun buildLegendEntries(): String {
        val stereotypes = nodes.values
            .mapNotNull { node ->
                val tag = elementTags[node.id].orEmpty().firstOrNull { elementTagStyles[it]?.legendText != null }
                tag?.let { elementTagStyles[it]?.legendText } ?: node.payload[STEREOTYPE_KEY]
            }
            .filter { it != "Legend" && it != "Unknown" }
            .distinct()
        val relation = relTags.values.flatten().mapNotNull { relTagStyles[it]?.legendText }.distinct().ifEmpty {
            if (edges.any { it.kind != EdgeKind.Invisible }) listOf("Relationship") else emptyList()
        }
        val entries = (stereotypes + relation).ifEmpty { listOf("C4 elements") }
        return "[Legend]\n" + entries.joinToString("\n")
    }

    private fun applyTagStyle(base: NodeStyle, tags: List<String>): NodeStyle {
        var out = base
        for (tag in tags) {
            val style = elementTagStyles[tag] ?: continue
            out = applyElementStyle(style, out)
        }
        return out
    }

    private fun applyElementStyleOverrides(id: NodeId, base: NodeStyle): NodeStyle =
        elementStyleOverrides[id]?.let { applyElementStyle(it, base) } ?: base

    private fun applyElementStyle(style: TagStyle, base: NodeStyle): NodeStyle =
        base.copy(fill = style.fill ?: base.fill, stroke = style.stroke ?: base.stroke, textColor = style.text ?: base.textColor)

    private fun elementShapeOverride(id: NodeId, tags: List<String>): NodeShape? =
        elementStyleOverrides[id]?.shape ?: tags.firstNotNullOfOrNull { elementTagStyles[it]?.shape }

    private fun applyRelTagStyle(base: EdgeStyle, tags: List<String>): EdgeStyle {
        var out = base
        for (tag in tags) {
            val style = relTagStyles[tag] ?: continue
            out = applyRelStyle(style, out)
        }
        return out
    }

    private fun applyRelStyleOverrides(key: Pair<NodeId, NodeId>, base: EdgeStyle): EdgeStyle =
        relStyleOverrides[key]?.let { applyRelStyle(it, base) } ?: base

    private fun applyRelStyle(style: RelTagStyle, base: EdgeStyle): EdgeStyle =
        base.copy(
            color = style.color ?: base.color,
            width = style.width?.let { maxOf(it, (base.width ?: 0f) + 1f) } ?: base.width,
            dash = style.dash ?: base.dash,
        )

    private fun rebuildEdgePresentation() {
        val presentation = LinkedHashMap<Int, C4EdgePresentation>()
        val links = LinkedHashMap<Int, String>()
        for ((index, edge) in edges.withIndex()) {
            val key = edge.from to edge.to
            val tagStyle = mergedRelTagStyle(relTags[key].orEmpty())
            val override = mergeRelStyle(tagStyle, relStyleOverrides[key])
            presentation[index] = C4EdgePresentation(
                textColor = override?.text,
                offsetX = override?.offsetX ?: 0f,
                offsetY = override?.offsetY ?: 0f,
            )
            relLinks[key]?.let { links[index] = it }
        }
        latestEdgePresentation = presentation
        latestEdgeLinks = links
    }

    private fun mergedRelTagStyle(tags: List<String>): RelTagStyle? {
        var out: RelTagStyle? = null
        for (tag in tags) {
            out = mergeRelStyle(out, relTagStyles[tag])
        }
        return out
    }

    private fun mergeRelStyle(base: RelTagStyle?, override: RelTagStyle?): RelTagStyle? =
        when {
            base == null -> override
            override == null -> base
            else -> RelTagStyle(
                color = override.color ?: base.color,
                text = override.text ?: base.text,
                width = override.width ?: base.width,
                dash = override.dash ?: base.dash,
                offsetX = override.offsetX ?: base.offsetX,
                offsetY = override.offsetY ?: base.offsetY,
                legendText = override.legendText ?: base.legendText,
            )
        }

    private fun parseCall(text: String): ParsedCall? {
        val nameEnd = text.indexOf('(')
        if (nameEnd <= 0 || !text.endsWith(")")) return null
        val name = text.substring(0, nameEnd).trim()
        val body = text.substring(nameEnd + 1, text.length - 1)
        return ParsedCall(name, splitArgs(body))
    }

    private fun splitNamedArgs(args: List<String>): Pair<List<String>, Map<String, String>> {
        val positional = ArrayList<String>()
        val named = LinkedHashMap<String, String>()
        for (arg in args) {
            val eq = arg.indexOf('=')
            if (eq > 0 && arg.substring(0, eq).trim().startsWith("$")) {
                named[arg.substring(0, eq).trim().removePrefix("$")] = unquote(arg.substring(eq + 1).trim())
            } else {
                positional += arg
            }
        }
        return positional to named
    }

    private fun parseNamedAndPositional(args: List<String>, startIndex: Int, positionalKeys: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var positionalIndex = 0
        for (i in startIndex until args.size) {
            val raw = args[i].trim()
            val eq = raw.indexOf('=')
            if (eq > 0 && raw.startsWith("$")) {
                val key = raw.substring(1, eq).trim()
                val value = unquote(raw.substring(eq + 1).trim())
                if (key.isNotEmpty()) out[key] = value
            } else if (positionalIndex < positionalKeys.size) {
                out[positionalKeys[positionalIndex]] = unquote(raw)
                positionalIndex++
            }
        }
        return out
    }

    private fun splitArgs(raw: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuote = false
        var escape = false
        var depth = 0
        for (ch in raw) {
            when {
                escape -> {
                    cur.append(ch)
                    escape = false
                }
                ch == '\\' && inQuote -> {
                    cur.append(ch)
                    escape = true
                }
                ch == '"' -> {
                    cur.append(ch)
                    inQuote = !inQuote
                }
                !inQuote && ch == '(' -> {
                    cur.append(ch)
                    depth++
                }
                !inQuote && ch == ')' -> {
                    cur.append(ch)
                    depth = (depth - 1).coerceAtLeast(0)
                }
                !inQuote && depth == 0 && ch == ',' -> {
                    out += cur.toString().trim()
                    cur.clear()
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    private fun parseTags(raw: String?): List<String> =
        raw?.split(',', ';')?.map { it.trim().removeSurrounding("\"") }?.filter { it.isNotEmpty() }.orEmpty()

    private fun parseColor(raw: String?): ArgbColor? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        if (s.startsWith("#") && s.length == 7) return ArgbColor((0xFF000000 or s.drop(1).toLong(16)).toInt())
        if (s.startsWith("#") && s.length == 4) {
            val hex = s.drop(1).flatMap { listOf(it, it) }.joinToString("")
            return ArgbColor((0xFF000000 or hex.toLong(16)).toInt())
        }
        return when (s.lowercase()) {
            "blue" -> ArgbColor(0xFF0000FF.toInt())
            "red" -> ArgbColor(0xFFFF0000.toInt())
            "green" -> ArgbColor(0xFF008000.toInt())
            "orange" -> ArgbColor(0xFFFFA500.toInt())
            "black" -> ArgbColor(0xFF000000.toInt())
            "white" -> ArgbColor(0xFFFFFFFF.toInt())
            "gray", "grey" -> ArgbColor(0xFF808080.toInt())
            "yellow" -> ArgbColor(0xFFFFFF00.toInt())
            else -> null
        }
    }

    private fun parseLineDash(raw: String?): List<Float>? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        return when {
            s.contains("DashedLine", ignoreCase = true) -> listOf(7f, 5f)
            s.contains("DottedLine", ignoreCase = true) -> listOf(2f, 4f)
            else -> null
        }
    }

    private fun parseLineWidth(raw: String?): Float? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        return if (s.contains("BoldLine", ignoreCase = true)) 2.5f else null
    }

    private fun parseNodeShapeHelper(raw: String): NodeShape? =
        when {
            raw.contains("RoundedBoxShape", ignoreCase = true) -> NodeShape.RoundedBox
            raw.contains("EightSidedShape", ignoreCase = true) -> NodeShape.Custom("octagon")
            else -> null
        }

    private fun applyClusterTagStyle(base: ClusterStyle, tags: List<String>): ClusterStyle {
        var out = base
        for (tag in tags) {
            val style = elementTagStyles[tag] ?: continue
            out = out.copy(fill = style.fill ?: out.fill, stroke = style.stroke ?: out.stroke)
        }
        return out
    }

    private fun defaultClusterStyle(type: String): ClusterStyle = when {
        type.contains("Enterprise", ignoreCase = true) -> ClusterStyle(ArgbColor(0xFFFFFBF0.toInt()), ArgbColor(0xFFF9A825.toInt()), 1.5f)
        type.contains("Deployment", ignoreCase = true) || type == "Node" || type == "Node_L" || type == "Node_R" -> ClusterStyle(ArgbColor(0xFFF3F6FB.toInt()), ArgbColor(0xFF78909C.toInt()), 1.5f)
        else -> ClusterStyle(ArgbColor(0xFFF8FBFF.toInt()), ArgbColor(0xFF90A4AE.toInt()), 1.5f)
    }

    private fun relationPorts(name: String): Pair<PortId?, PortId?> = when (name) {
        "Rel_U", "Rel_Up", "BiRel_U", "BiRel_Up" -> PortId("T") to PortId("B")
        "Rel_D", "Rel_Down", "BiRel_D", "BiRel_Down" -> PortId("B") to PortId("T")
        "Rel_L", "Rel_Left", "BiRel_L", "BiRel_Left" -> PortId("L") to PortId("R")
        "Rel_R", "Rel_Right", "BiRel_R", "BiRel_Right" -> PortId("R") to PortId("L")
        "Rel_Back" -> PortId("L") to PortId("L")
        else -> null to null
    }

    private fun defaultPorts(): List<Port> = listOf(
        Port(PortId("T"), side = PortSide.TOP),
        Port(PortId("R"), side = PortSide.RIGHT),
        Port(PortId("B"), side = PortSide.BOTTOM),
        Port(PortId("L"), side = PortSide.LEFT),
    )

    private fun unquote(raw: String): String = raw.trim().removeSurrounding("\"")

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "PLANTUML-E019")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
