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
        const val PORT_HOST_KEY = "plantuml.component.portHost"
        const val NOTE_TARGET_KEY = "plantuml.component.note.target"
        const val NOTE_PLACEMENT_KEY = "plantuml.component.note.placement"
        const val STYLE_COMPONENT_FILL_KEY = "plantuml.component.style.component.fill"
        const val STYLE_COMPONENT_STROKE_KEY = "plantuml.component.style.component.stroke"
        const val STYLE_COMPONENT_TEXT_KEY = "plantuml.component.style.component.text"
        const val STYLE_INTERFACE_FILL_KEY = "plantuml.component.style.interface.fill"
        const val STYLE_INTERFACE_STROKE_KEY = "plantuml.component.style.interface.stroke"
        const val STYLE_INTERFACE_TEXT_KEY = "plantuml.component.style.interface.text"
        const val STYLE_PORT_FILL_KEY = "plantuml.component.style.port.fill"
        const val STYLE_PORT_STROKE_KEY = "plantuml.component.style.port.stroke"
        const val STYLE_PORT_TEXT_KEY = "plantuml.component.style.port.text"
        const val STYLE_DATABASE_FILL_KEY = "plantuml.component.style.database.fill"
        const val STYLE_DATABASE_STROKE_KEY = "plantuml.component.style.database.stroke"
        const val STYLE_DATABASE_TEXT_KEY = "plantuml.component.style.database.text"
        const val STYLE_QUEUE_FILL_KEY = "plantuml.component.style.queue.fill"
        const val STYLE_QUEUE_STROKE_KEY = "plantuml.component.style.queue.stroke"
        const val STYLE_QUEUE_TEXT_KEY = "plantuml.component.style.queue.text"
        const val STYLE_NOTE_FILL_KEY = "plantuml.component.style.note.fill"
        const val STYLE_NOTE_STROKE_KEY = "plantuml.component.style.note.stroke"
        const val STYLE_NOTE_TEXT_KEY = "plantuml.component.style.note.text"
        const val STYLE_PACKAGE_FILL_KEY = "plantuml.component.style.package.fill"
        const val STYLE_PACKAGE_STROKE_KEY = "plantuml.component.style.package.stroke"
        const val STYLE_PACKAGE_TEXT_KEY = "plantuml.component.style.package.text"
        const val STYLE_RECTANGLE_FILL_KEY = "plantuml.component.style.rectangle.fill"
        const val STYLE_RECTANGLE_STROKE_KEY = "plantuml.component.style.rectangle.stroke"
        const val STYLE_RECTANGLE_TEXT_KEY = "plantuml.component.style.rectangle.text"
        const val STYLE_FRAME_FILL_KEY = "plantuml.component.style.frame.fill"
        const val STYLE_FRAME_STROKE_KEY = "plantuml.component.style.frame.stroke"
        const val STYLE_FRAME_TEXT_KEY = "plantuml.component.style.frame.text"
        const val STYLE_CLOUD_FILL_KEY = "plantuml.component.style.cloud.fill"
        const val STYLE_CLOUD_STROKE_KEY = "plantuml.component.style.cloud.stroke"
        const val STYLE_CLOUD_TEXT_KEY = "plantuml.component.style.cloud.text"
        const val STYLE_NODE_FILL_KEY = "plantuml.component.style.node.fill"
        const val STYLE_NODE_STROKE_KEY = "plantuml.component.style.node.stroke"
        const val STYLE_NODE_TEXT_KEY = "plantuml.component.style.node.text"
        const val STYLE_EDGE_COLOR_KEY = "plantuml.component.style.edge.color"
        val RELATION_OPERATORS = listOf("-->", "<--", "..>", "<..", "--", "..")
        val SUPPORTED_SKINPARAM_SCOPES = setOf("component", "interface", "port", "database", "queue", "note", "package", "rectangle", "frame", "cloud", "node")

        fun styleFontSizeKey(scope: String): String = "plantuml.component.style.$scope.fontSize"
        fun styleFontNameKey(scope: String): String = "plantuml.component.style.$scope.fontName"
        fun styleLineThicknessKey(scope: String): String = "plantuml.component.style.$scope.lineThickness"
        fun styleShadowingKey(scope: String): String = "plantuml.component.style.$scope.shadowing"
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

    private data class PendingNote(
        val target: NodeId?,
        val placement: String,
        val lines: MutableList<String> = ArrayList(),
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val edges: MutableList<Edge> = ArrayList()
    private val clusters: LinkedHashMap<NodeId, ClusterDef> = LinkedHashMap()
    private val clusterStack: ArrayDeque<NodeId> = ArrayDeque()
    private val styleExtras: LinkedHashMap<String, String> = LinkedHashMap()
    private val skinparamSupport = PlantUmlSkinparamSupport(
        styleExtras = styleExtras,
        supportedScopes = SUPPORTED_SKINPARAM_SCOPES,
        scopeKeys = mapOf(
            "component" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_COMPONENT_FILL_KEY,
                strokeKey = STYLE_COMPONENT_STROKE_KEY,
                textKey = STYLE_COMPONENT_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("component"),
                fontNameKey = styleFontNameKey("component"),
                lineThicknessKey = styleLineThicknessKey("component"),
                shadowingKey = styleShadowingKey("component"),
            ),
            "interface" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_INTERFACE_FILL_KEY,
                strokeKey = STYLE_INTERFACE_STROKE_KEY,
                textKey = STYLE_INTERFACE_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("interface"),
                fontNameKey = styleFontNameKey("interface"),
                lineThicknessKey = styleLineThicknessKey("interface"),
                shadowingKey = styleShadowingKey("interface"),
            ),
            "port" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_PORT_FILL_KEY,
                strokeKey = STYLE_PORT_STROKE_KEY,
                textKey = STYLE_PORT_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("port"),
                fontNameKey = styleFontNameKey("port"),
                lineThicknessKey = styleLineThicknessKey("port"),
                shadowingKey = styleShadowingKey("port"),
            ),
            "database" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_DATABASE_FILL_KEY,
                strokeKey = STYLE_DATABASE_STROKE_KEY,
                textKey = STYLE_DATABASE_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("database"),
                fontNameKey = styleFontNameKey("database"),
                lineThicknessKey = styleLineThicknessKey("database"),
                shadowingKey = styleShadowingKey("database"),
            ),
            "queue" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_QUEUE_FILL_KEY,
                strokeKey = STYLE_QUEUE_STROKE_KEY,
                textKey = STYLE_QUEUE_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("queue"),
                fontNameKey = styleFontNameKey("queue"),
                lineThicknessKey = styleLineThicknessKey("queue"),
                shadowingKey = styleShadowingKey("queue"),
            ),
            "note" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_NOTE_FILL_KEY,
                strokeKey = STYLE_NOTE_STROKE_KEY,
                textKey = STYLE_NOTE_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("note"),
                fontNameKey = styleFontNameKey("note"),
                lineThicknessKey = styleLineThicknessKey("note"),
                shadowingKey = styleShadowingKey("note"),
            ),
            "package" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_PACKAGE_FILL_KEY,
                strokeKey = STYLE_PACKAGE_STROKE_KEY,
                textKey = STYLE_PACKAGE_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("package"),
                fontNameKey = styleFontNameKey("package"),
                lineThicknessKey = styleLineThicknessKey("package"),
                shadowingKey = styleShadowingKey("package"),
            ),
            "rectangle" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_RECTANGLE_FILL_KEY,
                strokeKey = STYLE_RECTANGLE_STROKE_KEY,
                textKey = STYLE_RECTANGLE_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("rectangle"),
                fontNameKey = styleFontNameKey("rectangle"),
                lineThicknessKey = styleLineThicknessKey("rectangle"),
                shadowingKey = styleShadowingKey("rectangle"),
            ),
            "frame" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_FRAME_FILL_KEY,
                strokeKey = STYLE_FRAME_STROKE_KEY,
                textKey = STYLE_FRAME_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("frame"),
                fontNameKey = styleFontNameKey("frame"),
                lineThicknessKey = styleLineThicknessKey("frame"),
                shadowingKey = styleShadowingKey("frame"),
            ),
            "cloud" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_CLOUD_FILL_KEY,
                strokeKey = STYLE_CLOUD_STROKE_KEY,
                textKey = STYLE_CLOUD_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("cloud"),
                fontNameKey = styleFontNameKey("cloud"),
                lineThicknessKey = styleLineThicknessKey("cloud"),
                shadowingKey = styleShadowingKey("cloud"),
            ),
            "node" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_NODE_FILL_KEY,
                strokeKey = STYLE_NODE_STROKE_KEY,
                textKey = STYLE_NODE_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("node"),
                fontNameKey = styleFontNameKey("node"),
                lineThicknessKey = styleLineThicknessKey("node"),
                shadowingKey = styleShadowingKey("node"),
            ),
        ),
        directKeys = mapOf(
            "componentbackgroundcolor" to STYLE_COMPONENT_FILL_KEY,
            "componentbordercolor" to STYLE_COMPONENT_STROKE_KEY,
            "componentfontcolor" to STYLE_COMPONENT_TEXT_KEY,
            "interfacebackgroundcolor" to STYLE_INTERFACE_FILL_KEY,
            "interfacebordercolor" to STYLE_INTERFACE_STROKE_KEY,
            "interfacefontcolor" to STYLE_INTERFACE_TEXT_KEY,
            "portbackgroundcolor" to STYLE_PORT_FILL_KEY,
            "portbordercolor" to STYLE_PORT_STROKE_KEY,
            "portfontcolor" to STYLE_PORT_TEXT_KEY,
            "databasebackgroundcolor" to STYLE_DATABASE_FILL_KEY,
            "databasebordercolor" to STYLE_DATABASE_STROKE_KEY,
            "databasefontcolor" to STYLE_DATABASE_TEXT_KEY,
            "queuebackgroundcolor" to STYLE_QUEUE_FILL_KEY,
            "queuebordercolor" to STYLE_QUEUE_STROKE_KEY,
            "queuefontcolor" to STYLE_QUEUE_TEXT_KEY,
            "notebackgroundcolor" to STYLE_NOTE_FILL_KEY,
            "notebordercolor" to STYLE_NOTE_STROKE_KEY,
            "notefontcolor" to STYLE_NOTE_TEXT_KEY,
            "packagebackgroundcolor" to STYLE_PACKAGE_FILL_KEY,
            "packagebordercolor" to STYLE_PACKAGE_STROKE_KEY,
            "packagefontcolor" to STYLE_PACKAGE_TEXT_KEY,
            "rectanglebackgroundcolor" to STYLE_RECTANGLE_FILL_KEY,
            "rectanglebordercolor" to STYLE_RECTANGLE_STROKE_KEY,
            "rectanglefontcolor" to STYLE_RECTANGLE_TEXT_KEY,
            "framebackgroundcolor" to STYLE_FRAME_FILL_KEY,
            "framebordercolor" to STYLE_FRAME_STROKE_KEY,
            "framefontcolor" to STYLE_FRAME_TEXT_KEY,
            "cloudbackgroundcolor" to STYLE_CLOUD_FILL_KEY,
            "cloudbordercolor" to STYLE_CLOUD_STROKE_KEY,
            "cloudfontcolor" to STYLE_CLOUD_TEXT_KEY,
            "nodebackgroundcolor" to STYLE_NODE_FILL_KEY,
            "nodebordercolor" to STYLE_NODE_STROKE_KEY,
            "nodefontcolor" to STYLE_NODE_TEXT_KEY,
            "arrowcolor" to STYLE_EDGE_COLOR_KEY,
        ),
        warnUnsupported = ::warnUnsupportedSkinparam,
        emptyBatch = { IrPatchBatch(seq, emptyList()) },
    )

    private var seq: Long = 0
    private var direction: Direction = Direction.LR
    private var pendingNote: PendingNote? = null
    private var noteSeq: Long = 0

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            return IrPatchBatch(seq, emptyList())
        }
        pendingNote?.let { note ->
            if (trimmed.equals("end note", ignoreCase = true)) {
                return flushPendingNote(note)
            }
            note.lines += trimmed
            return IrPatchBatch(seq, emptyList())
        }
        skinparamSupport.pendingScope?.let { scope ->
            if (trimmed == "}") {
                skinparamSupport.pendingScope = null
                return IrPatchBatch(seq, emptyList())
            }
            return skinparamSupport.acceptScopedEntry(scope, trimmed)
        }
        if (trimmed == "}") {
            if (clusterStack.isEmpty()) return errorBatch("Unmatched '}' in PlantUML component body")
            clusterStack.removeLast()
            return IrPatchBatch(seq, emptyList())
        }

        val patches = ArrayList<IrPatch>()
        when {
            trimmed.startsWith("skinparam", ignoreCase = true) -> return skinparamSupport.acceptDirective(trimmed)
            trimmed.equals("left to right direction", ignoreCase = true) -> direction = Direction.LR
            trimmed.equals("right to left direction", ignoreCase = true) -> direction = Direction.RL
            trimmed.equals("top to bottom direction", ignoreCase = true) -> direction = Direction.TB
            trimmed.equals("bottom to top direction", ignoreCase = true) -> direction = Direction.BT
            trimmed.startsWith("[") -> parseNodeDecl("component $trimmed", "component", patches)
            trimmed.startsWith("component ", ignoreCase = true) -> parseNodeDecl(trimmed, "component", patches)
            trimmed.startsWith("()", ignoreCase = true) -> parseShorthandInterface(trimmed, patches)
            trimmed.startsWith("interface ", ignoreCase = true) -> parseNodeDecl(trimmed, "interface", patches)
            trimmed.startsWith("port ", ignoreCase = true) -> parseNodeDecl(trimmed, "port", patches)
            trimmed.startsWith("portin ", ignoreCase = true) -> parseNodeDecl(trimmed, "portin", patches)
            trimmed.startsWith("portout ", ignoreCase = true) -> parseNodeDecl(trimmed, "portout", patches)
            trimmed.startsWith("database ", ignoreCase = true) -> parseNodeDecl(trimmed, "database", patches)
            trimmed.startsWith("queue ", ignoreCase = true) -> parseNodeDecl(trimmed, "queue", patches)
            trimmed.startsWith("frame ", ignoreCase = true) -> parseClusterDecl(trimmed, "frame")
            trimmed.startsWith("rectangle ", ignoreCase = true) -> parseClusterDecl(trimmed, "rectangle")
            trimmed.startsWith("package ", ignoreCase = true) -> parseClusterDecl(trimmed, "package")
            trimmed.startsWith("cloud ", ignoreCase = true) -> parseClusterDecl(trimmed, "cloud")
            trimmed.startsWith("node ", ignoreCase = true) -> parseClusterDecl(trimmed, "node")
            trimmed.startsWith("note ", ignoreCase = true) -> parseNote(trimmed, patches)
            findRelationOperator(trimmed) != null -> parseEdge(trimmed, patches)
            else -> return errorBatch("Unsupported PlantUML component statement: $trimmed")
        }
        return IrPatchBatch(seq, patches)
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (pendingNote != null) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed component note block before end of PlantUML block",
                    code = "PLANTUML-E005",
                ),
            )
            pendingNote = null
        }
        if (skinparamSupport.pendingScope != null) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.WARNING,
                    message = "Unsupported or unclosed 'skinparam ${skinparamSupport.pendingScope!!}' block ignored",
                    code = "PLANTUML-W001",
                ),
            )
            skinparamSupport.pendingScope = null
        }
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
        styleHints = StyleHints(direction = direction, extras = styleExtras),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseShorthandInterface(line: String, out: MutableList<IrPatch>) {
        val body = line.removePrefix("()").trim()
        if (body.isEmpty()) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML interface shorthand", "PLANTUML-E005")
            return
        }
        val normalized = when {
            body.startsWith("\"") || body.matches(Regex("[A-Za-z0-9_.:-]+\\s+as\\s+\"[^\"]+\"")) -> "interface $body"
            body.matches(Regex("[A-Za-z0-9_.:-]+")) -> "interface $body"
            else -> "interface \"$body\""
        }
        parseNodeDecl(normalized, "interface", out)
    }

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
                portHostFor(parent)?.let { put(PORT_HOST_KEY, it.value) }
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

    private fun parseNote(line: String, out: MutableList<IrPatch>) {
        val inlineAnchored = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*:\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (inlineAnchored != null) {
            val target = NodeId(inlineAnchored.groupValues[2])
            ensureImplicitNode(target)
            addAnchoredNote(target, inlineAnchored.groupValues[1].lowercase(), inlineAnchored.groupValues[3].trim(), out)
            return
        }
        val blockAnchored = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (blockAnchored != null) {
            val target = NodeId(blockAnchored.groupValues[2])
            ensureImplicitNode(target)
            pendingNote = PendingNote(target = target, placement = blockAnchored.groupValues[1].lowercase())
            return
        }
        val standaloneQuoted = Regex("^note\\s+\"([^\"]+)\"$", RegexOption.IGNORE_CASE).matchEntire(line)
        if (standaloneQuoted != null) {
            addStandaloneNote(standaloneQuoted.groupValues[1], out)
            return
        }
        if (line.equals("note", ignoreCase = true)) {
            pendingNote = PendingNote(target = null, placement = "standalone")
            return
        }
        diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML component note syntax", "PLANTUML-E005")
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

    private fun flushPendingNote(note: PendingNote): IrPatchBatch {
        val patches = ArrayList<IrPatch>()
        val text = note.lines.joinToString("\n").trim()
        if (text.isEmpty()) {
            patches += addDiagnostic(Diagnostic(Severity.ERROR, "Empty component note block", "PLANTUML-E005"))
        } else if (note.target != null) {
            addAnchoredNote(note.target, note.placement, text, patches)
        } else {
            addStandaloneNote(text, patches)
        }
        pendingNote = null
        return IrPatchBatch(seq, patches)
    }

    private fun addAnchoredNote(target: NodeId, placement: String, text: String, out: MutableList<IrPatch>) {
        val noteId = NodeId("${target.value}__note_${noteSeq++}")
        val parent = clusterStack.lastOrNull()
        val note = Node(
            id = noteId,
            label = RichLabel.Plain(text),
            shape = NodeShape.Note,
            style = styleFor("note"),
            payload = buildMap {
                put(KIND_KEY, "note")
                put(NOTE_TARGET_KEY, target.value)
                put(NOTE_PLACEMENT_KEY, placement)
                parent?.let { put(PARENT_KEY, it.value) }
            },
        )
        nodes[noteId] = note
        out += IrPatch.AddNode(note)
        val edge = Edge(
            from = noteId,
            to = target,
            label = null,
            kind = EdgeKind.Dashed,
            arrow = ArrowEnds.None,
            style = EdgeStyle(
                color = ArgbColor(0xFFFFA000.toInt()),
                width = 1f,
                dash = listOf(4f, 4f),
            ),
        )
        edges += edge
        out += IrPatch.AddEdge(edge)
    }

    private fun addStandaloneNote(text: String, out: MutableList<IrPatch>) {
        val noteId = NodeId("note_${noteSeq++}")
        val parent = clusterStack.lastOrNull()
        val note = Node(
            id = noteId,
            label = RichLabel.Plain(text),
            shape = NodeShape.Note,
            style = styleFor("note"),
            payload = buildMap {
                put(KIND_KEY, "note")
                put(NOTE_PLACEMENT_KEY, "standalone")
                parent?.let { put(PARENT_KEY, it.value) }
            },
        )
        nodes[noteId] = note
        out += IrPatch.AddNode(note)
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

    private fun portHostFor(parent: NodeId?): NodeId? {
        val clusterId = parent?.value ?: return null
        if (!clusterId.endsWith("__cluster")) return null
        val hostId = NodeId(clusterId.removeSuffix("__cluster"))
        return if (hostId in nodes) hostId else null
    }

    private fun kindFor(keyword: String): String = when (keyword.lowercase()) {
        "component" -> "component"
        "interface" -> "interface"
        "port", "portin", "portout" -> "port"
        "database" -> "database"
        "queue" -> "queue"
        "note" -> "note"
        "package" -> "package"
        "cloud" -> "cloud"
        "node" -> "node"
        "frame" -> "frame"
        "rectangle" -> "rectangle"
        else -> keyword.lowercase()
    }

    private fun shapeFor(keyword: String): NodeShape = when (keyword.lowercase()) {
        "component" -> NodeShape.Component
        "interface" -> NodeShape.Circle
        "port", "portin", "portout" -> NodeShape.Circle
        "database" -> NodeShape.Cylinder
        "queue" -> NodeShape.Note
        "note" -> NodeShape.Note
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
        "database" -> NodeStyle(
            fill = ArgbColor(0xFFE8F5E9.toInt()),
            stroke = ArgbColor(0xFF2E7D32.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF1B5E20.toInt()),
        )
        "queue" -> NodeStyle(
            fill = ArgbColor(0xFFF3E5F5.toInt()),
            stroke = ArgbColor(0xFF8E24AA.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF4A148C.toInt()),
        )
        "note" -> NodeStyle(
            fill = ArgbColor(0xFFFFF8E1.toInt()),
            stroke = ArgbColor(0xFFFFA000.toInt()),
            strokeWidth = 1.25f,
            textColor = ArgbColor(0xFF5D4037.toInt()),
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
        "frame" -> ClusterStyle(
            fill = ArgbColor(0xFFFFF8E1.toInt()),
            stroke = ArgbColor(0xFFF9A825.toInt()),
            strokeWidth = 1.5f,
        )
        "rectangle" -> ClusterStyle(
            fill = ArgbColor(0xFFF3E5F5.toInt()),
            stroke = ArgbColor(0xFF8E24AA.toInt()),
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

    private fun warnUnsupportedSkinparam(line: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.WARNING, "Unsupported '$line' ignored", "PLANTUML-W001"))))

}
