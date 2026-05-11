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
 * Streaming parser for the Phase-4 PlantUML `deployment` MVP.
 *
 * Example:
 * ```kotlin
 * val parser = PlantUmlDeploymentParser()
 * parser.acceptLine("node Server {")
 * parser.acceptLine("[App]")
 * parser.acceptLine("}")
 * val ir = parser.snapshot()
 * ```
 */
@DiagramApi
class PlantUmlDeploymentParser {
    companion object {
        const val KIND_KEY = "plantuml.deployment.kind"
        const val PARENT_KEY = "plantuml.deployment.parent"
        const val NOTE_TARGET_KEY = "plantuml.deployment.note.target"
        const val NOTE_PLACEMENT_KEY = "plantuml.deployment.note.placement"
        const val STYLE_ACTOR_FILL_KEY = "plantuml.deployment.style.actor.fill"
        const val STYLE_ACTOR_STROKE_KEY = "plantuml.deployment.style.actor.stroke"
        const val STYLE_ACTOR_TEXT_KEY = "plantuml.deployment.style.actor.text"
        const val STYLE_ARTIFACT_FILL_KEY = "plantuml.deployment.style.artifact.fill"
        const val STYLE_ARTIFACT_STROKE_KEY = "plantuml.deployment.style.artifact.stroke"
        const val STYLE_ARTIFACT_TEXT_KEY = "plantuml.deployment.style.artifact.text"
        const val STYLE_DATABASE_FILL_KEY = "plantuml.deployment.style.database.fill"
        const val STYLE_DATABASE_STROKE_KEY = "plantuml.deployment.style.database.stroke"
        const val STYLE_DATABASE_TEXT_KEY = "plantuml.deployment.style.database.text"
        const val STYLE_STORAGE_FILL_KEY = "plantuml.deployment.style.storage.fill"
        const val STYLE_STORAGE_STROKE_KEY = "plantuml.deployment.style.storage.stroke"
        const val STYLE_STORAGE_TEXT_KEY = "plantuml.deployment.style.storage.text"
        const val STYLE_QUEUE_FILL_KEY = "plantuml.deployment.style.queue.fill"
        const val STYLE_QUEUE_STROKE_KEY = "plantuml.deployment.style.queue.stroke"
        const val STYLE_QUEUE_TEXT_KEY = "plantuml.deployment.style.queue.text"
        const val STYLE_NOTE_FILL_KEY = "plantuml.deployment.style.note.fill"
        const val STYLE_NOTE_STROKE_KEY = "plantuml.deployment.style.note.stroke"
        const val STYLE_NOTE_TEXT_KEY = "plantuml.deployment.style.note.text"
        const val STYLE_PACKAGE_FILL_KEY = "plantuml.deployment.style.package.fill"
        const val STYLE_PACKAGE_STROKE_KEY = "plantuml.deployment.style.package.stroke"
        const val STYLE_PACKAGE_TEXT_KEY = "plantuml.deployment.style.package.text"
        const val STYLE_FRAME_FILL_KEY = "plantuml.deployment.style.frame.fill"
        const val STYLE_FRAME_STROKE_KEY = "plantuml.deployment.style.frame.stroke"
        const val STYLE_FRAME_TEXT_KEY = "plantuml.deployment.style.frame.text"
        const val STYLE_CLOUD_FILL_KEY = "plantuml.deployment.style.cloud.fill"
        const val STYLE_CLOUD_STROKE_KEY = "plantuml.deployment.style.cloud.stroke"
        const val STYLE_CLOUD_TEXT_KEY = "plantuml.deployment.style.cloud.text"
        const val STYLE_NODE_FILL_KEY = "plantuml.deployment.style.node.fill"
        const val STYLE_NODE_STROKE_KEY = "plantuml.deployment.style.node.stroke"
        const val STYLE_NODE_TEXT_KEY = "plantuml.deployment.style.node.text"
        const val STYLE_EDGE_COLOR_KEY = "plantuml.deployment.style.edge.color"
        val RELATION_OPERATORS = listOf("-->", "<--", "..>", "<..", "--", "..")
        private val IDENTIFIER = Regex("[A-Za-z0-9_.:-]+")
        private val SUPPORTED_SKINPARAM_SCOPES = setOf("actor", "artifact", "database", "storage", "queue", "note", "package", "frame", "cloud", "node")

        fun styleFontSizeKey(scope: String): String = "plantuml.deployment.style.$scope.fontSize"
        fun styleFontNameKey(scope: String): String = "plantuml.deployment.style.$scope.fontName"
        fun styleLineThicknessKey(scope: String): String = "plantuml.deployment.style.$scope.lineThickness"
        fun styleShadowingKey(scope: String): String = "plantuml.deployment.style.$scope.shadowing"
    }

    private data class ClusterDef(
        val id: NodeId,
        val title: String,
        val kind: String,
        val parent: NodeId?,
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
            "actor" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_ACTOR_FILL_KEY,
                strokeKey = STYLE_ACTOR_STROKE_KEY,
                textKey = STYLE_ACTOR_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("actor"),
                fontNameKey = styleFontNameKey("actor"),
                lineThicknessKey = styleLineThicknessKey("actor"),
                shadowingKey = styleShadowingKey("actor"),
            ),
            "artifact" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_ARTIFACT_FILL_KEY,
                strokeKey = STYLE_ARTIFACT_STROKE_KEY,
                textKey = STYLE_ARTIFACT_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("artifact"),
                fontNameKey = styleFontNameKey("artifact"),
                lineThicknessKey = styleLineThicknessKey("artifact"),
                shadowingKey = styleShadowingKey("artifact"),
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
            "storage" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_STORAGE_FILL_KEY,
                strokeKey = STYLE_STORAGE_STROKE_KEY,
                textKey = STYLE_STORAGE_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("storage"),
                fontNameKey = styleFontNameKey("storage"),
                lineThicknessKey = styleLineThicknessKey("storage"),
                shadowingKey = styleShadowingKey("storage"),
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
            "actorbackgroundcolor" to STYLE_ACTOR_FILL_KEY,
            "actorbordercolor" to STYLE_ACTOR_STROKE_KEY,
            "actorfontcolor" to STYLE_ACTOR_TEXT_KEY,
            "artifactbackgroundcolor" to STYLE_ARTIFACT_FILL_KEY,
            "artifactbordercolor" to STYLE_ARTIFACT_STROKE_KEY,
            "artifactfontcolor" to STYLE_ARTIFACT_TEXT_KEY,
            "databasebackgroundcolor" to STYLE_DATABASE_FILL_KEY,
            "databasebordercolor" to STYLE_DATABASE_STROKE_KEY,
            "databasefontcolor" to STYLE_DATABASE_TEXT_KEY,
            "storagebackgroundcolor" to STYLE_STORAGE_FILL_KEY,
            "storagebordercolor" to STYLE_STORAGE_STROKE_KEY,
            "storagefontcolor" to STYLE_STORAGE_TEXT_KEY,
            "queuebackgroundcolor" to STYLE_QUEUE_FILL_KEY,
            "queuebordercolor" to STYLE_QUEUE_STROKE_KEY,
            "queuefontcolor" to STYLE_QUEUE_TEXT_KEY,
            "notebackgroundcolor" to STYLE_NOTE_FILL_KEY,
            "notebordercolor" to STYLE_NOTE_STROKE_KEY,
            "notefontcolor" to STYLE_NOTE_TEXT_KEY,
            "packagebackgroundcolor" to STYLE_PACKAGE_FILL_KEY,
            "packagebordercolor" to STYLE_PACKAGE_STROKE_KEY,
            "packagefontcolor" to STYLE_PACKAGE_TEXT_KEY,
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
            if (trimmed.equals("end note", ignoreCase = true) || trimmed.equals("endnote", ignoreCase = true)) {
                pendingNote = null
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
            if (clusterStack.isEmpty()) return errorBatch("Unmatched '}' in PlantUML deployment body")
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
            trimmed.startsWith("[") -> parseNodeDecl("artifact $trimmed", "artifact", patches)
            trimmed.startsWith("actor ", ignoreCase = true) -> parseNodeDecl(trimmed, "actor", patches)
            trimmed.startsWith("actor/", ignoreCase = true) -> parseNodeDecl("actor " + trimmed.removePrefix("actor/").trim(), "actor", patches)
            trimmed.startsWith("artifact ", ignoreCase = true) -> parseNodeDecl(trimmed, "artifact", patches)
            trimmed.startsWith("database ", ignoreCase = true) -> parseKeyword(trimmed, "database", patches)
            trimmed.startsWith("storage ", ignoreCase = true) -> parseKeyword(trimmed, "storage", patches)
            trimmed.startsWith("queue ", ignoreCase = true) -> parseKeyword(trimmed, "queue", patches)
            trimmed.startsWith("node ", ignoreCase = true) -> parseKeyword(trimmed, "node", patches)
            trimmed.startsWith("cloud ", ignoreCase = true) -> parseKeyword(trimmed, "cloud", patches)
            trimmed.startsWith("frame ", ignoreCase = true) -> parseKeyword(trimmed, "frame", patches)
            trimmed.startsWith("package ", ignoreCase = true) -> parseKeyword(trimmed, "package", patches)
            trimmed.startsWith("note ", ignoreCase = true) -> parseNote(trimmed, patches)
            findRelationOperator(trimmed) != null -> parseEdge(trimmed, patches)
            else -> return errorBatch("Unsupported PlantUML deployment statement: $trimmed")
        }
        return IrPatchBatch(seq, patches)
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (pendingNote != null) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed deployment note block before end of PlantUML block",
                    code = "PLANTUML-E009",
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
                    message = "Unclosed deployment cluster body before end of PlantUML block",
                    code = "PLANTUML-E009",
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

    private fun parseKeyword(line: String, keyword: String, out: MutableList<IrPatch>) {
        var body = line.substring(keyword.length).trim()
        val opens = body.endsWith("{")
        if (opens) body = body.removeSuffix("{").trim()
        val spec = parseAliasSpec(body) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML $keyword declaration", "PLANTUML-E009")
            return
        }
        if (opens) {
            val clusterId = NodeId(spec.id)
            clusters[clusterId] = ClusterDef(
                id = clusterId,
                title = spec.label.ifBlank { spec.id },
                kind = keyword.lowercase(),
                parent = clusterStack.lastOrNull(),
            )
            clusterStack.addLast(clusterId)
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
                put(KIND_KEY, keyword.lowercase())
                parent?.let { put(PARENT_KEY, it.value) }
            },
        )
        nodes[id] = node
        out += IrPatch.AddNode(node)
    }

    private fun parseNodeDecl(line: String, keyword: String, out: MutableList<IrPatch>) {
        var body = line.substring(keyword.length).trim()
        val opens = body.endsWith("{")
        if (opens) body = body.removeSuffix("{").trim()
        val spec = parseAliasSpec(body) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML $keyword declaration", "PLANTUML-E009")
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
                put(KIND_KEY, keyword.lowercase())
                parent?.let { put(PARENT_KEY, it.value) }
            },
        )
        nodes[id] = node
        out += IrPatch.AddNode(node)
        if (opens) {
            val clusterId = NodeId("${id.value}__cluster")
            clusters[clusterId] = ClusterDef(
                id = clusterId,
                title = spec.label.ifBlank { spec.id },
                kind = keyword.lowercase(),
                parent = parent,
            )
            clusterStack.addLast(clusterId)
        }
    }

    private fun parseEdge(line: String, out: MutableList<IrPatch>) {
        val op = findRelationOperator(line) ?: return
        val parts = line.split(":", limit = 2)
        val relText = parts[0].trim()
        val label = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(RichLabel::Plain)
        val regex = Regex("^(.*?)\\s*" + Regex.escape(op) + "\\s*(.*?)$")
        val match = regex.matchEntire(relText) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML deployment relation", "PLANTUML-E009")
            return
        }
        val left = parseEndpoint(match.groupValues[1].trim())
        val right = parseEndpoint(match.groupValues[2].trim())
        if (left == null || right == null) {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML deployment relation endpoint", "PLANTUML-E009")
            return
        }
        ensureImplicitArtifact(left)
        ensureImplicitArtifact(right)
        val edge = Edge(
            from = left,
            to = right,
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

    private fun parseNote(line: String, out: MutableList<IrPatch>) {
        val anchoredInline = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*:\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (anchoredInline != null) {
            val target = NodeId(anchoredInline.groupValues[2])
            ensureImplicitArtifact(target)
            addAnchoredNote(target, anchoredInline.groupValues[1].lowercase(), anchoredInline.groupValues[3].trim(), out)
            return
        }
        val anchoredBlock = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (anchoredBlock != null) {
            val target = NodeId(anchoredBlock.groupValues[2])
            ensureImplicitArtifact(target)
            pendingNote = PendingNote(target = target, placement = anchoredBlock.groupValues[1].lowercase())
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
        diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML deployment note syntax", "PLANTUML-E009")
    }

    private fun flushPendingNote(note: PendingNote): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        val text = note.lines.joinToString("\n").trim()
        if (text.isEmpty()) {
            out += addDiagnostic(Diagnostic(Severity.ERROR, "Empty deployment note block", "PLANTUML-E009"))
        } else if (note.target != null) {
            addAnchoredNote(note.target, note.placement, text, out)
        } else {
            addStandaloneNote(text, out)
        }
        return IrPatchBatch(seq, out)
    }

    private fun addAnchoredNote(target: NodeId, placement: String, text: String, out: MutableList<IrPatch>) {
        val parent = clusterStack.lastOrNull()
        val note = Node(
            id = NodeId("${target.value}__note_${noteSeq++}"),
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
        nodes[note.id] = note
        out += IrPatch.AddNode(note)
        val edge = Edge(
            from = note.id,
            to = target,
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
        val parent = clusterStack.lastOrNull()
        val note = Node(
            id = NodeId("note_${noteSeq++}"),
            label = RichLabel.Plain(text),
            shape = NodeShape.Note,
            style = styleFor("note"),
            payload = buildMap {
                put(KIND_KEY, "note")
                put(NOTE_PLACEMENT_KEY, "standalone")
                parent?.let { put(PARENT_KEY, it.value) }
            },
        )
        nodes[note.id] = note
        out += IrPatch.AddNode(note)
    }

    private fun parseEndpoint(raw: String): NodeId? {
        val clean = raw.removePrefix("[").removeSuffix("]").removePrefix("(").removeSuffix(")").trim()
        if (!clean.matches(IDENTIFIER)) return null
        return NodeId(clean)
    }

    private fun ensureImplicitArtifact(id: NodeId) {
        if (id in nodes) return
        val parent = clusterStack.lastOrNull()
        nodes[id] = Node(
            id = id,
            label = RichLabel.Plain(id.value),
            shape = NodeShape.Note,
            style = styleFor("artifact"),
            payload = buildMap {
                put(KIND_KEY, "artifact")
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

    private data class AliasSpec(val id: String, val label: String)

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
        text.replace(Regex("[^A-Za-z0-9_.:-]+"), "_").trim('_').ifEmpty { "node_$seq" }

    private fun shapeFor(keyword: String): NodeShape = when (keyword.lowercase()) {
        "actor" -> NodeShape.Actor
        "artifact" -> NodeShape.Note
        "database" -> NodeShape.Cylinder
        "storage" -> NodeShape.Cylinder
        "cloud" -> NodeShape.Cloud
        "queue" -> NodeShape.Note
        "note" -> NodeShape.Note
        "node" -> NodeShape.Package
        "frame", "package" -> NodeShape.Box
        else -> NodeShape.Box
    }

    private fun styleFor(keyword: String): NodeStyle = when (keyword.lowercase()) {
        "actor" -> NodeStyle(
            fill = ArgbColor(0xFFFFFFFF.toInt()),
            stroke = ArgbColor(0xFF546E7A.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF263238.toInt()),
        )
        "artifact" -> NodeStyle(
            fill = ArgbColor(0xFFFFF8E1.toInt()),
            stroke = ArgbColor(0xFFEF6C00.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFFE65100.toInt()),
        )
        "database" -> NodeStyle(
            fill = ArgbColor(0xFFE1F5FE.toInt()),
            stroke = ArgbColor(0xFF0277BD.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF01579B.toInt()),
        )
        "storage" -> NodeStyle(
            fill = ArgbColor(0xFFE8EAF6.toInt()),
            stroke = ArgbColor(0xFF3949AB.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF1A237E.toInt()),
        )
        "cloud" -> NodeStyle(
            fill = ArgbColor(0xFFF3E5F5.toInt()),
            stroke = ArgbColor(0xFF8E24AA.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF6A1B9A.toInt()),
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
            fill = ArgbColor(0xFFE8F5E9.toInt()),
            stroke = ArgbColor(0xFF2E7D32.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF1B5E20.toInt()),
        )
    }

    private fun clusterStyleFor(kind: String): ClusterStyle = when (kind) {
        "cloud" -> ClusterStyle(fill = ArgbColor(0xFFF3E5F5.toInt()), stroke = ArgbColor(0xFF8E24AA.toInt()), strokeWidth = 1.5f)
        "database" -> ClusterStyle(fill = ArgbColor(0xFFE1F5FE.toInt()), stroke = ArgbColor(0xFF0277BD.toInt()), strokeWidth = 1.5f)
        "frame" -> ClusterStyle(fill = ArgbColor(0xFFFFF8E1.toInt()), stroke = ArgbColor(0xFFEF6C00.toInt()), strokeWidth = 1.5f)
        else -> ClusterStyle(fill = ArgbColor(0xFFF1F8E9.toInt()), stroke = ArgbColor(0xFF558B2F.toInt()), strokeWidth = 1.5f)
    }

    private fun findRelationOperator(line: String): String? {
        for (candidate in RELATION_OPERATORS) {
            if (line.contains(candidate)) return candidate
        }
        return null
    }

    private fun errorBatch(message: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.ERROR, message, "PLANTUML-E009"))))

    private fun addDiagnostic(diagnostic: Diagnostic): IrPatch {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }

    private fun warnUnsupportedSkinparam(line: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.WARNING, "Unsupported '$line' ignored", "PLANTUML-W001"))))
}
