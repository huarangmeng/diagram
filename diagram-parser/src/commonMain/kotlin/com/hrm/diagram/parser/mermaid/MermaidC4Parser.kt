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

data class C4EdgePresentation(
    val textColor: ArgbColor? = null,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

data class C4LegendEntry(
    val kind: String,
    val name: String,
    val text: String,
    val fill: ArgbColor? = null,
    val stroke: ArgbColor? = null,
    val textColor: ArgbColor? = null,
)

/**
 * Streaming parser for Mermaid experimental C4 diagrams.
 *
 * Supported subset:
 * - Headers: `C4Context` / `C4Container` / `C4Component` / `C4Dynamic` / `C4Deployment`
 * - Elements: `Person*`, `System*`, `Container*`, `Component*`
 * - Boundaries: `Boundary`, `Enterprise_Boundary`, `System_Boundary`, `Container_Boundary`,
 *   `Deployment_Node`, `Node`, `Node_L`, `Node_R`
 * - Relations: `Rel`, `BiRel`, `RelIndex`, `Rel_U/D/L/R/Back`
 * - Styling: `UpdateElementStyle`, `UpdateRelStyle`
 */
class MermaidC4Parser {
    companion object {
        const val KIND_KEY = "mermaid.c4.kind"
        const val STEREOTYPE_KEY = "mermaid.c4.stereotype"
        const val TECHNOLOGY_KEY = "mermaid.c4.technology"
        const val DESCRIPTION_KEY = "mermaid.c4.description"
        const val EXTERNAL_KEY = "mermaid.c4.external"
        const val PARENT_KEY = "mermaid.c4.parent"
        const val TAGS_KEY = "mermaid.c4.tags"
        const val LINK_KEY = "mermaid.c4.link"
        val ELEMENT_NAMES = setOf(
            "Person", "Person_Ext",
            "System", "System_Ext", "SystemDb", "SystemDb_Ext", "SystemQueue", "SystemQueue_Ext",
            "Container", "Container_Ext", "ContainerDb", "ContainerDb_Ext", "ContainerQueue", "ContainerQueue_Ext",
            "Component", "Component_Ext", "ComponentDb", "ComponentDb_Ext", "ComponentQueue", "ComponentQueue_Ext",
        )
        val BOUNDARY_NAMES = setOf(
            "Boundary", "Enterprise_Boundary", "System_Boundary", "Container_Boundary",
            "Deployment_Node", "Node", "Node_L", "Node_R",
        )
        val REL_NAMES = setOf(
            "Rel", "BiRel", "RelIndex",
            "Rel_U", "Rel_Up", "Rel_D", "Rel_Down",
            "Rel_L", "Rel_Left", "Rel_R", "Rel_Right", "Rel_Back",
        )
    }

    private data class BoundaryDef(
        val id: NodeId,
        val title: String,
        val type: String,
        val parent: NodeId?,
    )

    private data class PendingEdge(
        val from: NodeId,
        val to: NodeId,
        val label: String?,
        val tech: String?,
        val arrow: ArrowEnds,
        val fromPort: PortId?,
        val toPort: PortId?,
    )

    private data class ParsedCall(
        val name: String,
        val args: List<String>,
        val rest: String,
    )

    private data class NodeStyleOverride(
        val fill: ArgbColor? = null,
        val stroke: ArgbColor? = null,
        val textColor: ArgbColor? = null,
        val shape: NodeShape? = null,
    )

    private data class RelStyleOverride(
        val lineColor: ArgbColor? = null,
        val textColor: ArgbColor? = null,
        val offsetX: Float? = null,
        val offsetY: Float? = null,
        val width: Float? = null,
        val dash: List<Float>? = null,
    )

    private data class ElementTagStyle(
        val fill: ArgbColor? = null,
        val stroke: ArgbColor? = null,
        val textColor: ArgbColor? = null,
        val shape: NodeShape? = null,
        val legendText: String? = null,
    )

    private data class RelTagStyle(
        val lineColor: ArgbColor? = null,
        val textColor: ArgbColor? = null,
        val width: Float? = null,
        val dash: List<Float>? = null,
        val legendText: String? = null,
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val baseEdges: MutableList<Edge> = ArrayList()
    private val boundaries: LinkedHashMap<NodeId, BoundaryDef> = LinkedHashMap()
    private val boundaryStack: MutableList<NodeId> = ArrayList()
    private val pendingEdges: MutableList<PendingEdge> = ArrayList()
    private val nodeStyleOverrides: LinkedHashMap<NodeId, NodeStyleOverride> = LinkedHashMap()
    private val relStyleOverrides: LinkedHashMap<Pair<NodeId, NodeId>, RelStyleOverride> = LinkedHashMap()
    private val elementTagStyles: LinkedHashMap<String, ElementTagStyle> = LinkedHashMap()
    private val relTagStyles: LinkedHashMap<String, RelTagStyle> = LinkedHashMap()
    private val nodeTags: LinkedHashMap<NodeId, List<String>> = LinkedHashMap()
    private val boundaryTags: LinkedHashMap<NodeId, List<String>> = LinkedHashMap()
    private val relTags: LinkedHashMap<Pair<NodeId, NodeId>, List<String>> = LinkedHashMap()
    private val nodeLinks: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val boundaryLinks: LinkedHashMap<NodeId, String> = LinkedHashMap()
    private val relLinks: LinkedHashMap<Pair<NodeId, NodeId>, String> = LinkedHashMap()
    private val layoutExtras: LinkedHashMap<String, String> = LinkedHashMap()
    private var latestEdgePresentation: Map<Int, C4EdgePresentation> = emptyMap()
    private var latestEdgeLinks: Map<Int, String> = emptyMap()
    private var headerSeen = false
    private var diagramKind = "C4Context"
    private var title: String? = null
    private var seq: Long = 0

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())
        val lexErr = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (lexErr != null) return errorBatch("Lex error at ${lexErr.start}: ${lexErr.text}")

        if (!headerSeen) {
            val first = toks.first()
            if (first.kind == MermaidTokenKind.C4_HEADER) {
                headerSeen = true
                diagramKind = first.text.toString()
                layoutExtras["c4.diagramKind"] = diagramKind
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected C4 header")
        }

        val text = toks.joinToString(" ") { it.text.toString() }.trim()
        if (text.isBlank()) return IrPatchBatch(seq, emptyList())

        val patches = ArrayList<IrPatch>()
        when {
            text == "}" -> closeBoundary(patches)
            text.startsWith("title ") -> title = unquote(text.removePrefix("title").trim())
            text.endsWith("{") -> parseBoundary(text, patches)
            else -> parseStatement(text, patches)
        }
        flushPendingEdges(patches)
        return IrPatchBatch(seq, patches)
    }

    fun snapshot(): GraphIR {
        flushPendingEdges(ArrayList())
        val styledNodes = nodes.values.map { applyNodeStyleOverride(it) }
        val styledEdges = applyRelStyleOverrides(baseEdges)
        return GraphIR(
            nodes = styledNodes,
            edges = styledEdges,
            clusters = buildClusters(parent = null),
            title = title,
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(direction = Direction.LR, extras = layoutExtras.toMap()),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    fun edgePresentationSnapshot(): Map<Int, C4EdgePresentation> = latestEdgePresentation

    fun edgeLinkSnapshot(): Map<Int, String> = latestEdgeLinks

    fun nodeLinkSnapshot(): Map<NodeId, String> = nodeLinks.toMap()

    fun boundaryLinkSnapshot(): Map<NodeId, String> = boundaryLinks.toMap()

    fun legendSnapshot(): List<C4LegendEntry> {
        val usedElementTags = linkedSetOf<String>()
        nodeTags.values.forEach { usedElementTags += it }
        boundaryTags.values.forEach { usedElementTags += it }
        val usedRelTags = linkedSetOf<String>()
        relTags.values.forEach { usedRelTags += it }
        val out = ArrayList<C4LegendEntry>()
        for (tag in usedElementTags) {
            val style = elementTagStyles[tag]
            out += C4LegendEntry(
                kind = "element",
                name = tag,
                text = style?.legendText ?: tag,
                fill = style?.fill ?: ArgbColor(0xFFE3F2FD.toInt()),
                stroke = style?.stroke ?: ArgbColor(0xFF1E88E5.toInt()),
                textColor = style?.textColor ?: ArgbColor(0xFF0D47A1.toInt()),
            )
        }
        for (tag in usedRelTags) {
            val style = relTagStyles[tag]
            out += C4LegendEntry(
                kind = "relationship",
                name = tag,
                text = style?.legendText ?: tag,
                stroke = style?.lineColor ?: ArgbColor(0xFF546E7A.toInt()),
                textColor = style?.textColor ?: ArgbColor(0xFF263238.toInt()),
            )
        }
        return out
    }

    private fun parseBoundary(text: String, out: MutableList<IrPatch>) {
        val parsed = parseCall(text.removeSuffix("{").trim()) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid C4 boundary syntax", "MERMAID-E213")
            return
        }
        if (parsed.name !in BOUNDARY_NAMES) {
            diagnostics += Diagnostic(Severity.ERROR, "Unknown C4 boundary '${parsed.name}'", "MERMAID-E213")
            return
        }
        val parsedArgs = splitNamedArgs(parsed.args)
        val positional = parsedArgs.positional
        val named = parsedArgs.named
        val alias = positional.getOrNull(0)?.trim().orEmpty()
        if (alias.isEmpty()) {
            diagnostics += Diagnostic(Severity.ERROR, "C4 boundary alias is required", "MERMAID-E213")
            return
        }
        val id = NodeId(alias)
        val title = positional.getOrNull(1)?.let(::unquote).orEmpty().ifBlank { alias }
        val type = positional.getOrNull(2)?.let(::unquote)?.takeIf { it.isNotBlank() } ?: parsed.name
        val parent = boundaryStack.lastOrNull()
        boundaries[id] = BoundaryDef(id = id, title = title, type = type, parent = parent)
        parseTags(named["tags"])?.let {
            boundaryTags[id] = it
            layoutExtras["c4.boundaryTags.$alias"] = it.joinToString(",")
        }
        named["link"]?.takeIf { it.isNotBlank() }?.let { boundaryLinks[id] = it }
        boundaryStack += id
        layoutExtras["c4.boundaryDepth"] = boundaryStack.size.toString()
    }

    private fun closeBoundary(out: MutableList<IrPatch>) {
        if (boundaryStack.isEmpty()) {
            diagnostics += Diagnostic(Severity.ERROR, "Unexpected '}' in C4 diagram", "MERMAID-E213")
            out += IrPatch.AddDiagnostic(diagnostics.last())
            return
        }
        boundaryStack.removeAt(boundaryStack.lastIndex)
    }

    private fun parseStatement(text: String, out: MutableList<IrPatch>) {
        val parsed = parseCall(text) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid C4 statement syntax", "MERMAID-E213")
            out += IrPatch.AddDiagnostic(diagnostics.last())
            return
        }
        when (parsed.name) {
            in ELEMENT_NAMES -> parseElement(parsed, out)
            in REL_NAMES -> parseRelation(parsed, out)
            "AddElementTag" -> parseAddElementTag(parsed)
            "AddRelTag" -> parseAddRelTag(parsed)
            "UpdateElementStyle" -> parseUpdateElementStyle(parsed)
            "UpdateRelStyle" -> parseUpdateRelStyle(parsed)
            "UpdateLayoutConfig" -> parseUpdateLayoutConfig(parsed)
            else -> {
                diagnostics += Diagnostic(Severity.ERROR, "Unknown C4 statement '${parsed.name}'", "MERMAID-E213")
                out += IrPatch.AddDiagnostic(diagnostics.last())
            }
        }
    }

    private fun parseElement(call: ParsedCall, out: MutableList<IrPatch>) {
        val parsedArgs = splitNamedArgs(call.args)
        val positional = parsedArgs.positional
        val named = parsedArgs.named
        val alias = positional.getOrNull(0)?.trim().orEmpty()
        if (alias.isEmpty()) {
            diagnostics += Diagnostic(Severity.ERROR, "C4 element alias is required", "MERMAID-E213")
            out += IrPatch.AddDiagnostic(diagnostics.last())
            return
        }
        val id = NodeId(alias)
        val label = positional.getOrNull(1)?.let(::unquote).orEmpty().ifBlank { alias }
        val (technology, description) = elementMeta(call.name, positional, named)
        val stereotype = stereotypeOf(call.name)
        val style = defaultNodeStyle(call.name)
        val parent = boundaryStack.lastOrNull()
        val tags = parseTags(named["tags"])
        val link = named["link"]?.takeIf { it.isNotBlank() }
        val node = Node(
            id = id,
            label = RichLabel.Plain(buildElementLabel(stereotype, label, technology, description)),
            shape = shapeOf(call.name),
            style = style,
            ports = defaultPorts(),
            payload = buildMap {
                put(KIND_KEY, call.name)
                put(STEREOTYPE_KEY, stereotype)
                if (technology != null) put(TECHNOLOGY_KEY, technology)
                if (description != null) put(DESCRIPTION_KEY, description)
                if (isExternal(call.name)) put(EXTERNAL_KEY, "true")
                parent?.let { put(PARENT_KEY, it.value) }
                if (!tags.isNullOrEmpty()) put(TAGS_KEY, tags.joinToString(","))
                link?.let { put(LINK_KEY, it) }
            },
        )
        nodes[id] = node
        if (!tags.isNullOrEmpty()) nodeTags[id] = tags
        link?.let { nodeLinks[id] = it }
        out += IrPatch.AddNode(node)
    }

    private fun parseRelation(call: ParsedCall, out: MutableList<IrPatch>) {
        val parsedArgs = splitNamedArgs(call.args)
        val positional = parsedArgs.positional
        val named = parsedArgs.named
        val rel = when (call.name) {
            "RelIndex" -> {
                val from = positional.getOrNull(1)?.trim().orEmpty()
                val to = positional.getOrNull(2)?.trim().orEmpty()
                val label = positional.getOrNull(3)?.let(::unquote)
                val tech = named["techn"] ?: positional.getOrNull(4)?.let(::unquote)
                ParsedRel(from, to, label, tech)
            }
            else -> {
                val from = positional.getOrNull(0)?.trim().orEmpty()
                val to = positional.getOrNull(1)?.trim().orEmpty()
                val label = positional.getOrNull(2)?.let(::unquote)
                val tech = named["techn"] ?: positional.getOrNull(3)?.let(::unquote)
                ParsedRel(from, to, label, tech)
            }
        }
        if (rel.from.isEmpty() || rel.to.isEmpty()) {
            diagnostics += Diagnostic(Severity.ERROR, "C4 relationship endpoints are required", "MERMAID-E213")
            out += IrPatch.AddDiagnostic(diagnostics.last())
            return
        }
        val ports = relationPorts(call.name)
        val pending = PendingEdge(
            from = NodeId(rel.from),
            to = NodeId(rel.to),
            label = rel.label,
            tech = rel.tech,
            arrow = if (call.name == "BiRel") ArrowEnds.Both else ArrowEnds.ToOnly,
            fromPort = ports.first,
            toPort = ports.second,
        )
        val key = pending.from to pending.to
        parseTags(named["tags"])?.let { relTags[key] = it }
        named["link"]?.takeIf { it.isNotBlank() }?.let { relLinks[key] = it }
        if (pending.from in nodes && pending.to in nodes) {
            registerEdge(pending, out)
        } else {
            pendingEdges += pending
        }
    }

    private fun parseAddElementTag(call: ParsedCall) {
        val positional = splitNamedArgs(call.args).positional
        val tag = positional.getOrNull(0)?.let(::unquote)?.trim().orEmpty()
        if (tag.isEmpty()) {
            diagnostics += Diagnostic(Severity.ERROR, "AddElementTag requires tag name", "MERMAID-E213")
            return
        }
        val args = parseNamedAndPositional(
            call.args,
            startIndex = 1,
            positionalKeys = listOf("bgColor", "fontColor", "borderColor", "shadowing", "shape", "sprite", "techn", "legendText", "legendSprite"),
        )
        elementTagStyles[tag] = ElementTagStyle(
            fill = args["bgColor"]?.let(::parseColor),
            stroke = args["borderColor"]?.let(::parseColor),
            textColor = args["fontColor"]?.let(::parseColor),
            shape = args["shape"]?.let(::parseNodeShapeHelper),
            legendText = args["legendText"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseAddRelTag(call: ParsedCall) {
        val positional = splitNamedArgs(call.args).positional
        val tag = positional.getOrNull(0)?.let(::unquote)?.trim().orEmpty()
        if (tag.isEmpty()) {
            diagnostics += Diagnostic(Severity.ERROR, "AddRelTag requires tag name", "MERMAID-E213")
            return
        }
        val args = parseNamedAndPositional(
            call.args,
            startIndex = 1,
            positionalKeys = listOf("textColor", "lineColor", "lineStyle", "sprite", "techn", "legendText", "legendSprite"),
        )
        relTagStyles[tag] = RelTagStyle(
            lineColor = args["lineColor"]?.let(::parseColor),
            textColor = args["textColor"]?.let(::parseColor),
            width = args["lineStyle"]?.let(::parseLineWidthHelper),
            dash = args["lineStyle"]?.let(::parseLineDashHelper),
            legendText = args["legendText"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseUpdateElementStyle(call: ParsedCall) {
        val alias = call.args.getOrNull(0)?.trim().orEmpty()
        if (alias.isEmpty()) {
            diagnostics += Diagnostic(Severity.ERROR, "UpdateElementStyle requires target alias", "MERMAID-E213")
            return
        }
        val args = parseNamedAndPositional(call.args, startIndex = 1, positionalKeys = listOf("bgColor", "fontColor", "borderColor"))
        val override = mergeNodeOverride(
            nodeStyleOverrides[NodeId(alias)],
            NodeStyleOverride(
                fill = args["bgColor"]?.let(::parseColor),
                stroke = args["borderColor"]?.let(::parseColor),
                textColor = args["fontColor"]?.let(::parseColor),
                shape = args["shape"]?.let(::parseNodeShapeHelper),
            ),
        )
        nodeStyleOverrides[NodeId(alias)] = override
    }

    private fun parseUpdateRelStyle(call: ParsedCall) {
        val from = call.args.getOrNull(0)?.trim().orEmpty()
        val to = call.args.getOrNull(1)?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty()) {
            diagnostics += Diagnostic(Severity.ERROR, "UpdateRelStyle requires from/to aliases", "MERMAID-E213")
            return
        }
        val args = parseNamedAndPositional(call.args, startIndex = 2, positionalKeys = listOf("textColor", "lineColor", "offsetX", "offsetY"))
        val key = NodeId(from) to NodeId(to)
        val merged = mergeRelOverride(
            relStyleOverrides[key],
            RelStyleOverride(
                lineColor = args["lineColor"]?.let(::parseColor),
                textColor = args["textColor"]?.let(::parseColor),
                offsetX = args["offsetX"]?.toFloatOrNull(),
                offsetY = args["offsetY"]?.toFloatOrNull(),
                width = args["lineStyle"]?.let(::parseLineWidthHelper),
                dash = args["lineStyle"]?.let(::parseLineDashHelper),
            ),
        )
        relStyleOverrides[key] = merged
    }

    private fun parseUpdateLayoutConfig(call: ParsedCall) {
        val args = parseNamedAndPositional(call.args, startIndex = 0, positionalKeys = listOf("c4ShapeInRow", "c4BoundaryInRow"))
        args["c4ShapeInRow"]?.let { layoutExtras["c4.shapeInRow"] = it }
        args["c4BoundaryInRow"]?.let { layoutExtras["c4.boundaryInRow"] = it }
    }

    private fun registerEdge(pending: PendingEdge, out: MutableList<IrPatch>) {
        val edge = Edge(
            from = pending.from,
            to = pending.to,
            label = buildRelLabel(pending.label, pending.tech),
            arrow = pending.arrow,
            fromPort = pending.fromPort,
            toPort = pending.toPort,
            style = EdgeStyle(
                color = ArgbColor(0xFF546E7A.toInt()),
                width = 1.5f,
            ),
        )
        baseEdges += edge
        out += IrPatch.AddEdge(edge)
    }

    private fun flushPendingEdges(out: MutableList<IrPatch>) {
        val ready = pendingEdges.filter { it.from in nodes && it.to in nodes }
        if (ready.isEmpty()) return
        pendingEdges.removeAll(ready)
        ready.forEach { registerEdge(it, out) }
    }

    private fun buildClusters(parent: NodeId?): List<Cluster> {
        return boundaries.values
            .filter { it.parent == parent }
            .map { boundary ->
                val styleOverride = mergedElementTagStyle(boundaryTags[boundary.id].orEmpty())
                val baseStyle = defaultClusterStyle(boundary.type)
                Cluster(
                    id = boundary.id,
                    label = encodeBoundaryLabel(boundary.type, boundary.title),
                    children = nodes.values.filter { it.payload[PARENT_KEY] == boundary.id.value }.map { it.id },
                    nestedClusters = buildClusters(boundary.id),
                    style = baseStyle.copy(
                        fill = styleOverride?.fill ?: baseStyle.fill,
                        stroke = styleOverride?.stroke ?: baseStyle.stroke,
                    ),
                )
            }
    }

    private fun applyNodeStyleOverride(node: Node): Node {
        val tagOverride = mergedElementTagStyle(nodeTags[node.id].orEmpty())
        val override = mergeNullableNodeOverride(tagOverride, nodeStyleOverrides[node.id])
        if (override == null) return node
        return node.copy(
            shape = override.shape ?: node.shape,
            style = NodeStyle(
                fill = override.fill ?: node.style.fill,
                stroke = override.stroke ?: node.style.stroke,
                strokeWidth = node.style.strokeWidth,
                textColor = override.textColor ?: node.style.textColor,
            ),
        )
    }

    private fun applyRelStyleOverrides(edges: List<Edge>): List<Edge> {
        val presentation = LinkedHashMap<Int, C4EdgePresentation>()
        val links = LinkedHashMap<Int, String>()
        val styled = edges.mapIndexed { index, edge ->
            val key = edge.from to edge.to
            val override = mergeNullableRelOverride(mergedRelTagStyle(relTags[key].orEmpty()), relStyleOverrides[key])
            if (override == null) {
                presentation[index] = C4EdgePresentation()
                relLinks[key]?.let { links[index] = it }
                edge
            } else {
                presentation[index] = C4EdgePresentation(
                    textColor = override.textColor,
                    offsetX = override.offsetX ?: 0f,
                    offsetY = override.offsetY ?: 0f,
                )
                relLinks[key]?.let { links[index] = it }
                edge.copy(
                    style = edge.style.copy(
                        color = override.lineColor ?: edge.style.color,
                        width = override.width ?: edge.style.width,
                        dash = override.dash ?: edge.style.dash,
                    ),
                )
            }
        }
        latestEdgePresentation = presentation
        latestEdgeLinks = links
        return styled
    }

    private fun parseCall(text: String): ParsedCall? {
        val open = text.indexOf('(')
        if (open <= 0) return null
        val name = text.substring(0, open).trim()
        var depth = 0
        var inString = false
        var close = -1
        var i = open
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"' && (i == 0 || text[i - 1] != '\\')) {
                inString = !inString
            } else if (!inString) {
                if (ch == '(') depth++
                if (ch == ')') {
                    depth--
                    if (depth == 0) {
                        close = i
                        break
                    }
                }
            }
            i++
        }
        if (close < 0) return null
        val args = splitArgs(text.substring(open + 1, close))
        val rest = text.substring(close + 1).trim()
        return ParsedCall(name = name, args = args, rest = rest)
    }

    private fun splitArgs(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"' && (i == 0 || text[i - 1] != '\\')) {
                inString = !inString
                cur.append(ch)
            } else if (!inString && ch == '(') {
                depth++
                cur.append(ch)
            } else if (!inString && ch == ')') {
                depth--
                cur.append(ch)
            } else if (!inString && depth == 0 && ch == ',') {
                out += cur.toString().trim()
                cur.clear()
            } else {
                cur.append(ch)
            }
            i++
        }
        if (cur.isNotEmpty()) out += cur.toString().trim()
        return out
    }

    private data class SplitArgsResult(
        val positional: List<String>,
        val named: Map<String, String>,
    )

    private fun splitNamedArgs(args: List<String>): SplitArgsResult {
        val positional = ArrayList<String>()
        val named = LinkedHashMap<String, String>()
        for (arg in args) {
            val raw = arg.trim()
            val eq = raw.indexOf('=')
            if (eq > 0 && raw.startsWith("$")) {
                val key = raw.substring(1, eq).trim()
                val value = unquote(raw.substring(eq + 1).trim())
                if (key.isNotEmpty()) named[key] = value
            } else {
                positional += raw
            }
        }
        return SplitArgsResult(positional, named)
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

    private fun parseColor(text: String): ArgbColor? =
        MermaidCssColors.parseToArgbIntOrNull(text)?.let(::ArgbColor)

    private fun unquote(text: String): String {
        val trimmed = text.trim()
        val unwrapped = if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.lastIndex)
        } else {
            trimmed
        }
        return unwrapped
            .replace("\\\"", "\"")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
    }

    private fun buildElementLabel(stereotype: String, label: String, technology: String?, description: String?): String {
        val parts = ArrayList<String>()
        parts += "<<$stereotype>>"
        parts += label
        technology?.takeIf { it.isNotBlank() }?.let { parts += "[$it]" }
        description?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString("\n")
    }

    private fun buildRelLabel(label: String?, tech: String?): RichLabel? {
        val left = label?.takeIf { it.isNotBlank() }
        val right = tech?.takeIf { it.isNotBlank() }?.let { "[$it]" }
        val text = listOfNotNull(left, right).joinToString("\n")
        return if (text.isBlank()) null else RichLabel.Plain(text)
    }

    private fun elementMeta(name: String, args: List<String>, named: Map<String, String>): Pair<String?, String?> {
        return when {
            name.startsWith("Container") || name.startsWith("Component") -> {
                (
                    named["techn"]?.takeIf { it.isNotBlank() } ?: args.getOrNull(2)?.let(::unquote).takeIf { !it.isNullOrBlank() }
                    ) to (
                    named["descr"]?.takeIf { it.isNotBlank() } ?: args.getOrNull(3)?.let(::unquote).takeIf { !it.isNullOrBlank() }
                    )
            }
            else -> {
                null to (named["descr"]?.takeIf { it.isNotBlank() } ?: args.getOrNull(2)?.let(::unquote).takeIf { !it.isNullOrBlank() })
            }
        }
    }

    private fun parseTags(raw: String?): List<String>? =
        raw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

    private fun shapeOf(name: String): NodeShape = when {
        name.startsWith("Person") -> NodeShape.Stadium
        "Db" in name -> NodeShape.Cylinder
        "Queue" in name -> NodeShape.Hexagon
        name.startsWith("Component") -> NodeShape.Component
        else -> NodeShape.RoundedBox
    }

    private fun stereotypeOf(name: String): String = buildString {
        val external = name.endsWith("_Ext")
        val base = name.removeSuffix("_Ext")
        if (external) append("external_")
        append(
            when {
                base.startsWith("Person") -> "person"
                base.startsWith("SystemDb") -> "system_db"
                base.startsWith("SystemQueue") -> "system_queue"
                base.startsWith("System") -> "system"
                base.startsWith("ContainerDb") -> "container_db"
                base.startsWith("ContainerQueue") -> "container_queue"
                base.startsWith("Container") -> "container"
                base.startsWith("ComponentDb") -> "component_db"
                base.startsWith("ComponentQueue") -> "component_queue"
                base.startsWith("Component") -> "component"
                else -> "element"
            },
        )
    }

    private fun isExternal(name: String): Boolean = name.endsWith("_Ext")

    private fun defaultNodeStyle(name: String): NodeStyle = when {
        name.startsWith("Person") -> NodeStyle(
            fill = ArgbColor(0xFFFFF8E1.toInt()),
            stroke = ArgbColor(0xFFFB8C00.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF6D4C41.toInt()),
        )
        "Db" in name -> NodeStyle(
            fill = ArgbColor(0xFFE8F5E9.toInt()),
            stroke = ArgbColor(0xFF43A047.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF1B5E20.toInt()),
        )
        "Queue" in name -> NodeStyle(
            fill = ArgbColor(0xFFF3E5F5.toInt()),
            stroke = ArgbColor(0xFF8E24AA.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF4A148C.toInt()),
        )
        name.startsWith("Component") -> NodeStyle(
            fill = ArgbColor(0xFFEDE7F6.toInt()),
            stroke = ArgbColor(0xFF5E35B1.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF311B92.toInt()),
        )
        else -> NodeStyle(
            fill = ArgbColor(0xFFE3F2FD.toInt()),
            stroke = ArgbColor(0xFF1E88E5.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF0D47A1.toInt()),
        )
    }

    private fun defaultClusterStyle(type: String): ClusterStyle = when {
        type.contains("Enterprise", ignoreCase = true) -> ClusterStyle(
            fill = ArgbColor(0xFFFFFBF0.toInt()),
            stroke = ArgbColor(0xFFF9A825.toInt()),
            strokeWidth = 1.5f,
        )
        type.contains("Deployment", ignoreCase = true) || type == "Node" || type == "Node_L" || type == "Node_R" -> ClusterStyle(
            fill = ArgbColor(0xFFF3F6FB.toInt()),
            stroke = ArgbColor(0xFF78909C.toInt()),
            strokeWidth = 1.5f,
        )
        else -> ClusterStyle(
            fill = ArgbColor(0xFFF8FBFF.toInt()),
            stroke = ArgbColor(0xFF90A4AE.toInt()),
            strokeWidth = 1.5f,
        )
    }

    private fun relationPorts(name: String): Pair<PortId?, PortId?> = when (name) {
        "Rel_U", "Rel_Up" -> PortId("T") to PortId("B")
        "Rel_D", "Rel_Down" -> PortId("B") to PortId("T")
        "Rel_L", "Rel_Left" -> PortId("L") to PortId("R")
        "Rel_R", "Rel_Right" -> PortId("R") to PortId("L")
        "Rel_Back" -> PortId("L") to PortId("L")
        else -> null to null
    }

    private fun encodeBoundaryLabel(type: String, title: String): RichLabel =
        RichLabel.Plain("__type:$type\n$title")

    private fun defaultPorts(): List<Port> = listOf(
        Port(PortId("T"), side = PortSide.TOP),
        Port(PortId("R"), side = PortSide.RIGHT),
        Port(PortId("B"), side = PortSide.BOTTOM),
        Port(PortId("L"), side = PortSide.LEFT),
    )

    private fun mergeNodeOverride(base: NodeStyleOverride?, override: NodeStyleOverride): NodeStyleOverride =
        NodeStyleOverride(
            fill = override.fill ?: base?.fill,
            stroke = override.stroke ?: base?.stroke,
            textColor = override.textColor ?: base?.textColor,
            shape = override.shape ?: base?.shape,
        )

    private fun mergeNullableNodeOverride(base: NodeStyleOverride?, override: NodeStyleOverride?): NodeStyleOverride? =
        when {
            base == null -> override
            override == null -> base
            else -> NodeStyleOverride(
                fill = override.fill ?: base.fill,
                stroke = override.stroke ?: base.stroke,
                textColor = override.textColor ?: base.textColor,
                shape = override.shape ?: base.shape,
            )
        }

    private fun mergeRelOverride(base: RelStyleOverride?, override: RelStyleOverride): RelStyleOverride =
        RelStyleOverride(
            lineColor = override.lineColor ?: base?.lineColor,
            textColor = override.textColor ?: base?.textColor,
            offsetX = override.offsetX ?: base?.offsetX,
            offsetY = override.offsetY ?: base?.offsetY,
            width = override.width ?: base?.width,
            dash = override.dash ?: base?.dash,
        )

    private fun mergeNullableRelOverride(base: RelStyleOverride?, override: RelStyleOverride?): RelStyleOverride? =
        when {
            base == null -> override
            override == null -> base
            else -> RelStyleOverride(
                lineColor = override.lineColor ?: base.lineColor,
                textColor = override.textColor ?: base.textColor,
                offsetX = override.offsetX ?: base.offsetX,
                offsetY = override.offsetY ?: base.offsetY,
                width = override.width ?: base.width,
                dash = override.dash ?: base.dash,
            )
        }

    private fun mergedElementTagStyle(tags: List<String>): NodeStyleOverride? {
        var merged: NodeStyleOverride? = null
        for (tag in tags) {
            val style = elementTagStyles[tag] ?: continue
            merged = mergeNodeOverride(
                merged,
                NodeStyleOverride(fill = style.fill, stroke = style.stroke, textColor = style.textColor, shape = style.shape),
            )
        }
        return merged
    }

    private fun mergedRelTagStyle(tags: List<String>): RelStyleOverride? {
        var merged: RelStyleOverride? = null
        for (tag in tags) {
            val style = relTagStyles[tag] ?: continue
            merged = mergeRelOverride(
                merged,
                RelStyleOverride(lineColor = style.lineColor, textColor = style.textColor, width = style.width, dash = style.dash),
            )
        }
        return merged
    }

    private fun parseNodeShapeHelper(raw: String): NodeShape? = when (raw.trim()) {
        "RoundedBoxShape()" -> NodeShape.RoundedBox
        "EightSidedShape()" -> NodeShape.Custom("octagon")
        else -> null
    }

    private fun parseLineWidthHelper(raw: String): Float? = when (raw.trim()) {
        "BoldLine()" -> 3f
        else -> null
    }

    private fun parseLineDashHelper(raw: String): List<Float>? = when (raw.trim()) {
        "DashedLine()" -> listOf(8f, 6f)
        "DottedLine()" -> listOf(2f, 4f)
        else -> null
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val diagnostic = Diagnostic(Severity.ERROR, message, "MERMAID-E213")
        diagnostics += diagnostic
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(diagnostic)))
    }

    private data class ParsedRel(
        val from: String,
        val to: String,
        val label: String?,
        val tech: String?,
    )

}
