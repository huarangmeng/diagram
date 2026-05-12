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
 * Streaming parser for the PlantUML C4-PlantUML macro subset.
 *
 * Supported syntax:
 * - `!include <C4/...>` / `!includeurl ...` lines are ignored as macro imports
 * - headers: `C4Context`, `C4Container`, `C4Component`, `C4Dynamic`, `C4Deployment`
 * - elements: `Person*`, `System*`, `Container*`, `Component*`
 * - boundaries: `Boundary`, `Enterprise_Boundary`, `System_Boundary`, `Container_Boundary`
 * - relations: `Rel`, `BiRel`, `RelIndex`, `Rel_U/D/L/R/Back`
 * - style macros: `AddElementTag`, `AddRelTag`, `UpdateElementStyle`, `UpdateRelStyle`
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

        val HEADERS = setOf("C4Context", "C4Container", "C4Component", "C4Dynamic", "C4Deployment")
        val ELEMENT_NAMES = setOf(
            "Person", "Person_Ext",
            "System", "System_Ext", "SystemDb", "SystemDb_Ext", "SystemQueue", "SystemQueue_Ext",
            "Container", "Container_Ext", "ContainerDb", "ContainerDb_Ext", "ContainerQueue", "ContainerQueue_Ext",
            "Component", "Component_Ext", "ComponentDb", "ComponentDb_Ext", "ComponentQueue", "ComponentQueue_Ext",
        )
        val BOUNDARY_NAMES = setOf("Boundary", "Enterprise_Boundary", "System_Boundary", "Container_Boundary")
        val REL_NAMES = setOf(
            "Rel", "BiRel", "RelIndex",
            "Rel_U", "Rel_Up", "Rel_D", "Rel_Down", "Rel_L", "Rel_Left", "Rel_R", "Rel_Right", "Rel_Back",
        )
    }

    private data class ParsedCall(val name: String, val args: List<String>)
    private data class BoundaryDef(val id: NodeId, val title: String, val type: String, val parent: NodeId?, val tags: List<String>)
    private data class TagStyle(val fill: ArgbColor? = null, val stroke: ArgbColor? = null, val text: ArgbColor? = null)
    private data class RelTagStyle(val color: ArgbColor? = null, val width: Float? = null, val dash: List<Float>? = null)

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
    private var diagramKind = "C4Context"
    private var title: String? = null
    private var seq: Long = 0L

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) return IrPatchBatch(seq, emptyList())
        if (trimmed.startsWith("!include", ignoreCase = true)) return IrPatchBatch(seq, emptyList())
        if (trimmed in HEADERS) {
            diagramKind = trimmed
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
            "AddElementTag" -> parseAddElementTag(call)
            "AddRelTag" -> parseAddRelTag(call)
            "UpdateElementStyle" -> parseUpdateElementStyle(call)
            "UpdateRelStyle" -> parseUpdateRelStyle(call)
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
            styleHints = StyleHints(extras = mapOf("plantuml.graph.kind" to "c4", "c4.diagramKind" to diagramKind)),
        )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    fun nodeLinkSnapshot(): Map<NodeId, String> = nodeLinks.toMap()

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
        boundaries[id] = BoundaryDef(id, title, call.name, parent, parseTags(named["tags"]))
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
        val (technology, description) = elementMeta(call.name, positional)
        val tags = parseTags(named["tags"])
        val style = applyElementStyleOverrides(id, applyTagStyle(defaultNodeStyle(call.name), tags))
        val parent = boundaryStack.lastOrNull()
        val node = Node(
            id = id,
            label = RichLabel.Plain(buildElementLabel(stereotypeOf(call.name), label, technology, description)),
            shape = shapeOf(call.name),
            style = style,
            payload = buildMap {
                put(KIND_KEY, call.name)
                put(STEREOTYPE_KEY, stereotypeOf(call.name))
                technology?.let { put(TECHNOLOGY_KEY, it) }
                description?.let { put(DESCRIPTION_KEY, it) }
                parent?.let { put(PARENT_KEY, it.value) }
                if (tags.isNotEmpty()) put(TAGS_KEY, tags.joinToString(","))
                named["link"]?.takeIf { it.isNotBlank() }?.let { put(LINK_KEY, it) }
            },
        )
        nodes[id] = node
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
        val tech = positional.getOrNull(start + 3)?.let(::unquote)
        val relTags = parseTags(named["tags"])
        val edgeStyle = applyRelStyleOverrides(
            NodeId(from) to NodeId(to),
            applyRelTagStyle(
                EdgeStyle(
                    color = ArgbColor(0xFF546E7A.toInt()),
                    dash = if (call.name == "Rel_Back") listOf(7f, 5f) else null,
                ),
                relTags,
            ),
        )
        val edge = Edge(
            from = NodeId(from),
            to = NodeId(to),
            label = listOfNotNull(label, tech?.takeIf { it.isNotBlank() }?.let { "[$it]" }).joinToString("\n").takeIf { it.isNotBlank() }?.let { RichLabel.Plain(it) },
            kind = if (call.name == "Rel_Back") EdgeKind.Dashed else EdgeKind.Solid,
            arrow = if (call.name == "BiRel") ArrowEnds.Both else ArrowEnds.ToOnly,
            style = edgeStyle,
        )
        edges += edge
        return IrPatchBatch(seq, listOf(IrPatch.AddEdge(edge)))
    }

    private fun parseAddElementTag(call: ParsedCall): IrPatchBatch {
        val args = splitNamedArgs(call.args)
        val positional = args.first
        val named = args.second
        val tag = positional.getOrNull(0)?.let(::unquote)?.trim().orEmpty()
        if (tag.isEmpty()) return errorBatch("AddElementTag requires tag name")
        elementTagStyles[tag] = TagStyle(
            fill = parseColor(named["bgColor"]),
            stroke = parseColor(named["borderColor"]),
            text = parseColor(named["fontColor"]),
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseAddRelTag(call: ParsedCall): IrPatchBatch {
        val args = splitNamedArgs(call.args)
        val positional = args.first
        val named = args.second
        val tag = positional.getOrNull(0)?.let(::unquote)?.trim().orEmpty()
        if (tag.isEmpty()) return errorBatch("AddRelTag requires tag name")
        relTagStyles[tag] = RelTagStyle(
            color = parseColor(named["lineColor"]),
            width = parseLineWidth(named["lineStyle"]),
            dash = parseLineDash(named["lineStyle"]),
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseUpdateElementStyle(call: ParsedCall): IrPatchBatch {
        val args = splitNamedArgs(call.args)
        val positional = args.first
        val named = args.second
        val id = NodeId(positional.getOrNull(0)?.trim().orEmpty())
        if (id.value.isEmpty()) return errorBatch("UpdateElementStyle requires element alias")
        val override = TagStyle(
            fill = parseColor(named["bgColor"]),
            stroke = parseColor(named["borderColor"]),
            text = parseColor(named["fontColor"]),
        )
        elementStyleOverrides[id] = override
        nodes[id]?.let { node ->
            nodes[id] = node.copy(style = applyElementStyle(override, node.style))
        }
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseUpdateRelStyle(call: ParsedCall): IrPatchBatch {
        val args = splitNamedArgs(call.args)
        val positional = args.first
        val named = args.second
        val from = NodeId(positional.getOrNull(0)?.trim().orEmpty())
        val to = NodeId(positional.getOrNull(1)?.trim().orEmpty())
        if (from.value.isEmpty() || to.value.isEmpty()) return errorBatch("UpdateRelStyle requires relationship endpoints")
        val override = RelTagStyle(
            color = parseColor(named["lineColor"]),
            width = parseLineWidth(named["lineStyle"]),
            dash = parseLineDash(named["lineStyle"]),
        )
        relStyleOverrides[from to to] = override
        for (index in edges.indices) {
            val edge = edges[index]
            if (edge.from == from && edge.to == to) {
                edges[index] = edge.copy(style = applyRelStyle(override, edge.style))
            }
        }
        return IrPatchBatch(seq, emptyList())
    }

    private fun ensurePlaceholder(id: NodeId) {
        if (id in nodes) return
        nodes[id] = Node(
            id = id,
            label = RichLabel.Plain(id.value),
            shape = NodeShape.RoundedBox,
            style = NodeStyle(fill = ArgbColor(0xFFECEFF1.toInt()), stroke = ArgbColor(0xFF78909C.toInt()), textColor = ArgbColor(0xFF263238.toInt())),
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
                style = ClusterStyle(fill = ArgbColor(0xFFF8FBFF.toInt()), stroke = ArgbColor(0xFF90A4AE.toInt()), strokeWidth = 1.4f),
            )
        }

    private fun elementMeta(name: String, positional: List<String>): Pair<String?, String?> =
        if (name.startsWith("Container") || name.startsWith("Component")) {
            positional.getOrNull(2)?.let(::unquote)?.takeIf { it.isNotBlank() } to positional.getOrNull(3)?.let(::unquote)?.takeIf { it.isNotBlank() }
        } else {
            null to positional.getOrNull(2)?.let(::unquote)?.takeIf { it.isNotBlank() }
        }

    private fun buildElementLabel(stereotype: String, label: String, tech: String?, desc: String?): String =
        listOfNotNull("[$stereotype]", label, tech?.let { "[$it]" }, desc).joinToString("\n")

    private fun stereotypeOf(name: String): String = when {
        name.startsWith("Person") -> "Person"
        name.startsWith("System") -> "Software System"
        name.startsWith("Container") -> "Container"
        name.startsWith("Component") -> "Component"
        else -> name
    }

    private fun shapeOf(name: String): NodeShape = when {
        name.contains("Db") -> NodeShape.Cylinder
        name.contains("Queue") -> NodeShape.Subroutine
        name.startsWith("Component") -> NodeShape.Component
        name.startsWith("Person") -> NodeShape.Actor
        else -> NodeShape.RoundedBox
    }

    private fun defaultNodeStyle(name: String): NodeStyle = when {
        name.startsWith("Person") -> NodeStyle(ArgbColor(0xFFE3F2FD.toInt()), ArgbColor(0xFF1565C0.toInt()), 1.4f, ArgbColor(0xFF0D47A1.toInt()))
        name.startsWith("System") -> NodeStyle(ArgbColor(0xFFE8F5E9.toInt()), ArgbColor(0xFF2E7D32.toInt()), 1.4f, ArgbColor(0xFF1B5E20.toInt()))
        name.startsWith("Container") -> NodeStyle(ArgbColor(0xFFFFF3E0.toInt()), ArgbColor(0xFFEF6C00.toInt()), 1.4f, ArgbColor(0xFFE65100.toInt()))
        name.startsWith("Component") -> NodeStyle(ArgbColor(0xFFF3E5F5.toInt()), ArgbColor(0xFF7B1FA2.toInt()), 1.4f, ArgbColor(0xFF4A148C.toInt()))
        else -> NodeStyle.Default
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
        base.copy(color = style.color ?: base.color, width = style.width ?: base.width, dash = style.dash ?: base.dash)

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
        return when (s.lowercase()) {
            "blue" -> ArgbColor(0xFF0000FF.toInt())
            "red" -> ArgbColor(0xFFFF0000.toInt())
            "green" -> ArgbColor(0xFF008000.toInt())
            "orange" -> ArgbColor(0xFFFFA500.toInt())
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

    private fun unquote(raw: String): String = raw.trim().removeSurrounding("\"")

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "PLANTUML-E019")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
