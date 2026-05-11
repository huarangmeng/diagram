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
        const val ACTOR_VARIANT_KEY = "plantuml.usecase.actorVariant"
        const val NOTE_TARGET_KEY = "plantuml.usecase.note.target"
        const val NOTE_PLACEMENT_KEY = "plantuml.usecase.note.placement"
        const val STYLE_ACTOR_FILL_KEY = "plantuml.usecase.style.actor.fill"
        const val STYLE_ACTOR_STROKE_KEY = "plantuml.usecase.style.actor.stroke"
        const val STYLE_ACTOR_TEXT_KEY = "plantuml.usecase.style.actor.text"
        const val STYLE_ACTOR_FONT_SIZE_KEY = "plantuml.usecase.style.actor.fontSize"
        const val STYLE_ACTOR_FONT_NAME_KEY = "plantuml.usecase.style.actor.fontName"
        const val STYLE_ACTOR_LINE_THICKNESS_KEY = "plantuml.usecase.style.actor.lineThickness"
        const val STYLE_ACTOR_SHADOWING_KEY = "plantuml.usecase.style.actor.shadowing"
        const val STYLE_USECASE_FILL_KEY = "plantuml.usecase.style.usecase.fill"
        const val STYLE_USECASE_STROKE_KEY = "plantuml.usecase.style.usecase.stroke"
        const val STYLE_USECASE_TEXT_KEY = "plantuml.usecase.style.usecase.text"
        const val STYLE_USECASE_FONT_SIZE_KEY = "plantuml.usecase.style.usecase.fontSize"
        const val STYLE_USECASE_FONT_NAME_KEY = "plantuml.usecase.style.usecase.fontName"
        const val STYLE_USECASE_LINE_THICKNESS_KEY = "plantuml.usecase.style.usecase.lineThickness"
        const val STYLE_USECASE_SHADOWING_KEY = "plantuml.usecase.style.usecase.shadowing"
        const val STYLE_NOTE_FILL_KEY = "plantuml.usecase.style.note.fill"
        const val STYLE_NOTE_STROKE_KEY = "plantuml.usecase.style.note.stroke"
        const val STYLE_NOTE_TEXT_KEY = "plantuml.usecase.style.note.text"
        const val STYLE_NOTE_FONT_SIZE_KEY = "plantuml.usecase.style.note.fontSize"
        const val STYLE_NOTE_FONT_NAME_KEY = "plantuml.usecase.style.note.fontName"
        const val STYLE_NOTE_LINE_THICKNESS_KEY = "plantuml.usecase.style.note.lineThickness"
        const val STYLE_NOTE_SHADOWING_KEY = "plantuml.usecase.style.note.shadowing"
        const val STYLE_RECTANGLE_FILL_KEY = "plantuml.usecase.style.rectangle.fill"
        const val STYLE_RECTANGLE_STROKE_KEY = "plantuml.usecase.style.rectangle.stroke"
        const val STYLE_RECTANGLE_FONT_SIZE_KEY = "plantuml.usecase.style.rectangle.fontSize"
        const val STYLE_RECTANGLE_FONT_NAME_KEY = "plantuml.usecase.style.rectangle.fontName"
        const val STYLE_RECTANGLE_LINE_THICKNESS_KEY = "plantuml.usecase.style.rectangle.lineThickness"
        const val STYLE_RECTANGLE_SHADOWING_KEY = "plantuml.usecase.style.rectangle.shadowing"
        const val STYLE_PACKAGE_FILL_KEY = "plantuml.usecase.style.package.fill"
        const val STYLE_PACKAGE_STROKE_KEY = "plantuml.usecase.style.package.stroke"
        const val STYLE_PACKAGE_FONT_SIZE_KEY = "plantuml.usecase.style.package.fontSize"
        const val STYLE_PACKAGE_FONT_NAME_KEY = "plantuml.usecase.style.package.fontName"
        const val STYLE_PACKAGE_LINE_THICKNESS_KEY = "plantuml.usecase.style.package.lineThickness"
        const val STYLE_PACKAGE_SHADOWING_KEY = "plantuml.usecase.style.package.shadowing"
        const val STYLE_EDGE_COLOR_KEY = "plantuml.usecase.style.edge.color"
        val RELATION_OPERATORS = listOf("<|--", "-->", "<--", "..>", "<..", ".>", "<.", "--", "..")

        private const val ACTOR_KIND = "actor"
        private const val BUSINESS_ACTOR_VARIANT = "business"
        private const val USECASE_KIND = "usecase"
        private const val NOTE_KIND = "note"
        private const val INCLUDE_KIND = "include"
        private const val EXTEND_KIND = "extend"
        val SUPPORTED_SKINPARAM_SCOPES = setOf("actor", "usecase", "note", "rectangle", "package")
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

    private var seq: Long = 0
    private var direction: Direction = Direction.LR
    private var pendingNote: PendingNote? = null
    private var pendingSkinparamScope: String? = null
    private var noteSeq: Long = 0
    private val styleExtras: LinkedHashMap<String, String> = LinkedHashMap()

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
        pendingSkinparamScope?.let { scope ->
            if (trimmed == "}") {
                pendingSkinparamScope = null
                return IrPatchBatch(seq, emptyList())
            }
            return applySkinparamEntry(scope, trimmed)
        }
        if (trimmed == "}") {
            if (clusterStack.isEmpty()) return errorBatch("Unmatched '}' in PlantUML usecase body")
            clusterStack.removeLast()
            return IrPatchBatch(seq, emptyList())
        }

        val patches = ArrayList<IrPatch>()
        when {
            trimmed.startsWith("skinparam", ignoreCase = true) -> return applySkinparam(trimmed)
            trimmed.equals("left to right direction", ignoreCase = true) -> direction = Direction.LR
            trimmed.equals("right to left direction", ignoreCase = true) -> direction = Direction.RL
            trimmed.equals("top to bottom direction", ignoreCase = true) -> direction = Direction.TB
            trimmed.equals("bottom to top direction", ignoreCase = true) -> direction = Direction.BT
            trimmed.startsWith("actor/", ignoreCase = true) -> parseActorDecl(trimmed, patches, business = true)
            trimmed.startsWith("actor ", ignoreCase = true) -> parseActorDecl(trimmed, patches, business = false)
            trimmed.startsWith("usecase ", ignoreCase = true) -> parseUsecaseDecl(trimmed, patches)
            trimmed.startsWith("(") && !hasRelationOperator(trimmed) -> parseParenUsecaseDecl(trimmed, patches)
            isActorColonVariant(trimmed) -> parseActorColon(trimmed, patches)
            trimmed.startsWith("rectangle ", ignoreCase = true) -> parseClusterDecl(trimmed, "rectangle")
            trimmed.startsWith("package ", ignoreCase = true) -> parseClusterDecl(trimmed, "package")
            trimmed.startsWith("note ", ignoreCase = true) -> parseNote(trimmed, patches)
            findRelationOperator(trimmed) != null -> parseEdge(trimmed, patches)
            else -> return errorBatch("Unsupported PlantUML usecase statement: $trimmed")
        }
        return IrPatchBatch(seq, patches)
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (pendingNote != null) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed usecase note block before end of PlantUML block",
                    code = "PLANTUML-E006",
                ),
            )
            pendingNote = null
        }
        if (pendingSkinparamScope != null) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.WARNING,
                    message = "Unsupported or unclosed 'skinparam ${pendingSkinparamScope!!}' block ignored",
                    code = "PLANTUML-W001",
                ),
            )
            pendingSkinparamScope = null
        }
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
        styleHints = StyleHints(direction = direction, extras = styleExtras),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseActorDecl(line: String, out: MutableList<IrPatch>, business: Boolean) {
        val body = if (line.startsWith("actor/", ignoreCase = true)) line.removePrefix("actor/").trim() else line.removePrefix("actor").trim()
        val spec = parseActorSpec(body, business) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML actor declaration", "PLANTUML-E006")
            return
        }
        ensureActorNode(spec, out)
    }

    private fun parseActorColon(line: String, out: MutableList<IrPatch>) {
        val spec = parseActorColonSpec(line) ?: run {
            diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML actor shorthand", "PLANTUML-E006")
            return
        }
        ensureActorNode(spec, out)
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
        val semantic = normalizeRelationSemantic(parts.getOrNull(1)?.trim())
        val label = semantic.displayLabel?.let(RichLabel::Plain)
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
                "..>", "<..", ".>", "<.", ".." -> EdgeKind.Dashed
                else -> EdgeKind.Solid
            },
            arrow = when (op) {
                "-->", "..>", "<|--" -> ArrowEnds.ToOnly
                "<--", "<..", "<." -> ArrowEnds.FromOnly
                ".>" -> ArrowEnds.ToOnly
                else -> ArrowEnds.None
            },
            style = EdgeStyle(
                color = relationColor(semantic.kind),
                width = 1.5f,
                dash = if (op.contains("..") || op.contains('.')) listOf(6f, 4f) else null,
            ),
        )
        if (edges.any { it.from == edge.from && it.to == edge.to && it.kind == edge.kind && it.arrow == edge.arrow && it.label == edge.label }) return
        edges += edge
        out += IrPatch.AddEdge(edge)
    }

    private fun parseNote(line: String, out: MutableList<IrPatch>) {
        val inlineAnchored = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+(.+?)\\s*:\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (inlineAnchored != null) {
            val target = parseEndpoint(inlineAnchored.groupValues[2].trim()) ?: run {
                diagnostics += Diagnostic(Severity.ERROR, "Invalid usecase note target", "PLANTUML-E006")
                return
            }
            ensureEndpointNode(target, fallbackKind = USECASE_KIND, out = out)
            addAnchoredNote(target.id, inlineAnchored.groupValues[1].lowercase(), inlineAnchored.groupValues[3].trim(), out)
            return
        }
        val blockAnchored = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+(.+?)\\s*$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (blockAnchored != null) {
            val target = parseEndpoint(blockAnchored.groupValues[2].trim()) ?: run {
                diagnostics += Diagnostic(Severity.ERROR, "Invalid usecase note target", "PLANTUML-E006")
                return
            }
            ensureEndpointNode(target, fallbackKind = USECASE_KIND, out = out)
            pendingNote = PendingNote(target = target.id, placement = blockAnchored.groupValues[1].lowercase())
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
        diagnostics += Diagnostic(Severity.ERROR, "Invalid PlantUML usecase note syntax", "PLANTUML-E006")
    }

    private fun applySkinparam(line: String): IrPatchBatch {
        val body = line.substringAfter(" ", "").trim()
        val normalized = body.substringBefore(' ', body).substringBefore('{').trim().lowercase()
        if (body.endsWith("{") && normalized in SUPPORTED_SKINPARAM_SCOPES) {
            pendingSkinparamScope = normalized
            return IrPatchBatch(seq, emptyList())
        }
        if (normalized in SUPPORTED_SKINPARAM_SCOPES && body.length > normalized.length) {
            return applySkinparamEntry(normalized, body.substring(normalized.length).trim())
        }
        val key = body.substringBefore(' ', "").trim()
        val value = body.substringAfter(' ', "").trim()
        return when (key.lowercase()) {
            "actorbackgroundcolor" -> storeSkinparam(STYLE_ACTOR_FILL_KEY, value)
            "actorbordercolor" -> storeSkinparam(STYLE_ACTOR_STROKE_KEY, value)
            "actorfontcolor" -> storeSkinparam(STYLE_ACTOR_TEXT_KEY, value)
            "actorfontsize" -> storeSkinparam(STYLE_ACTOR_FONT_SIZE_KEY, value)
            "actorfontname" -> storeSkinparam(STYLE_ACTOR_FONT_NAME_KEY, value)
            "actorlinethickness" -> storeSkinparam(STYLE_ACTOR_LINE_THICKNESS_KEY, value)
            "actorshadowing" -> storeSkinparam(STYLE_ACTOR_SHADOWING_KEY, value)
            "usecasebackgroundcolor" -> storeSkinparam(STYLE_USECASE_FILL_KEY, value)
            "usecasebordercolor" -> storeSkinparam(STYLE_USECASE_STROKE_KEY, value)
            "usecasefontcolor" -> storeSkinparam(STYLE_USECASE_TEXT_KEY, value)
            "usecasefontsize" -> storeSkinparam(STYLE_USECASE_FONT_SIZE_KEY, value)
            "usecasefontname" -> storeSkinparam(STYLE_USECASE_FONT_NAME_KEY, value)
            "usecaselinethickness" -> storeSkinparam(STYLE_USECASE_LINE_THICKNESS_KEY, value)
            "usecaseshadowing" -> storeSkinparam(STYLE_USECASE_SHADOWING_KEY, value)
            "notebackgroundcolor" -> storeSkinparam(STYLE_NOTE_FILL_KEY, value)
            "notebordercolor" -> storeSkinparam(STYLE_NOTE_STROKE_KEY, value)
            "notefontcolor" -> storeSkinparam(STYLE_NOTE_TEXT_KEY, value)
            "notefontsize" -> storeSkinparam(STYLE_NOTE_FONT_SIZE_KEY, value)
            "notefontname" -> storeSkinparam(STYLE_NOTE_FONT_NAME_KEY, value)
            "notelinethickness" -> storeSkinparam(STYLE_NOTE_LINE_THICKNESS_KEY, value)
            "noteshadowing" -> storeSkinparam(STYLE_NOTE_SHADOWING_KEY, value)
            "rectanglebackgroundcolor" -> storeSkinparam(STYLE_RECTANGLE_FILL_KEY, value)
            "rectanglebordercolor" -> storeSkinparam(STYLE_RECTANGLE_STROKE_KEY, value)
            "rectanglefontsize" -> storeSkinparam(STYLE_RECTANGLE_FONT_SIZE_KEY, value)
            "rectanglefontname" -> storeSkinparam(STYLE_RECTANGLE_FONT_NAME_KEY, value)
            "rectanglelinethickness" -> storeSkinparam(STYLE_RECTANGLE_LINE_THICKNESS_KEY, value)
            "rectangleshadowing" -> storeSkinparam(STYLE_RECTANGLE_SHADOWING_KEY, value)
            "packagebackgroundcolor" -> storeSkinparam(STYLE_PACKAGE_FILL_KEY, value)
            "packagebordercolor" -> storeSkinparam(STYLE_PACKAGE_STROKE_KEY, value)
            "packagefontsize" -> storeSkinparam(STYLE_PACKAGE_FONT_SIZE_KEY, value)
            "packagefontname" -> storeSkinparam(STYLE_PACKAGE_FONT_NAME_KEY, value)
            "packagelinethickness" -> storeSkinparam(STYLE_PACKAGE_LINE_THICKNESS_KEY, value)
            "packageshadowing" -> storeSkinparam(STYLE_PACKAGE_SHADOWING_KEY, value)
            "arrowcolor" -> storeSkinparam(STYLE_EDGE_COLOR_KEY, value)
            else -> warnUnsupportedSkinparam(line)
        }
    }

    private fun applySkinparamEntry(scope: String, line: String): IrPatchBatch {
        val key = line.substringBefore(' ', "").trim()
        val value = line.substringAfter(' ', "").trim()
        return when (scope.lowercase()) {
            "actor" -> when (key.lowercase()) {
                "backgroundcolor" -> storeSkinparam(STYLE_ACTOR_FILL_KEY, value)
                "bordercolor" -> storeSkinparam(STYLE_ACTOR_STROKE_KEY, value)
                "fontcolor" -> storeSkinparam(STYLE_ACTOR_TEXT_KEY, value)
                "fontsize" -> storeSkinparam(STYLE_ACTOR_FONT_SIZE_KEY, value)
                "fontname" -> storeSkinparam(STYLE_ACTOR_FONT_NAME_KEY, value)
                "linethickness" -> storeSkinparam(STYLE_ACTOR_LINE_THICKNESS_KEY, value)
                "shadowing" -> storeSkinparam(STYLE_ACTOR_SHADOWING_KEY, value)
                else -> warnUnsupportedSkinparam("skinparam actor $line")
            }
            "usecase" -> when (key.lowercase()) {
                "backgroundcolor" -> storeSkinparam(STYLE_USECASE_FILL_KEY, value)
                "bordercolor" -> storeSkinparam(STYLE_USECASE_STROKE_KEY, value)
                "fontcolor" -> storeSkinparam(STYLE_USECASE_TEXT_KEY, value)
                "fontsize" -> storeSkinparam(STYLE_USECASE_FONT_SIZE_KEY, value)
                "fontname" -> storeSkinparam(STYLE_USECASE_FONT_NAME_KEY, value)
                "linethickness" -> storeSkinparam(STYLE_USECASE_LINE_THICKNESS_KEY, value)
                "shadowing" -> storeSkinparam(STYLE_USECASE_SHADOWING_KEY, value)
                else -> warnUnsupportedSkinparam("skinparam usecase $line")
            }
            "note" -> when (key.lowercase()) {
                "backgroundcolor" -> storeSkinparam(STYLE_NOTE_FILL_KEY, value)
                "bordercolor" -> storeSkinparam(STYLE_NOTE_STROKE_KEY, value)
                "fontcolor" -> storeSkinparam(STYLE_NOTE_TEXT_KEY, value)
                "fontsize" -> storeSkinparam(STYLE_NOTE_FONT_SIZE_KEY, value)
                "fontname" -> storeSkinparam(STYLE_NOTE_FONT_NAME_KEY, value)
                "linethickness" -> storeSkinparam(STYLE_NOTE_LINE_THICKNESS_KEY, value)
                "shadowing" -> storeSkinparam(STYLE_NOTE_SHADOWING_KEY, value)
                else -> warnUnsupportedSkinparam("skinparam note $line")
            }
            "rectangle" -> when (key.lowercase()) {
                "backgroundcolor" -> storeSkinparam(STYLE_RECTANGLE_FILL_KEY, value)
                "bordercolor" -> storeSkinparam(STYLE_RECTANGLE_STROKE_KEY, value)
                "fontsize" -> storeSkinparam(STYLE_RECTANGLE_FONT_SIZE_KEY, value)
                "fontname" -> storeSkinparam(STYLE_RECTANGLE_FONT_NAME_KEY, value)
                "linethickness" -> storeSkinparam(STYLE_RECTANGLE_LINE_THICKNESS_KEY, value)
                "shadowing" -> storeSkinparam(STYLE_RECTANGLE_SHADOWING_KEY, value)
                else -> warnUnsupportedSkinparam("skinparam rectangle $line")
            }
            "package" -> when (key.lowercase()) {
                "backgroundcolor" -> storeSkinparam(STYLE_PACKAGE_FILL_KEY, value)
                "bordercolor" -> storeSkinparam(STYLE_PACKAGE_STROKE_KEY, value)
                "fontsize" -> storeSkinparam(STYLE_PACKAGE_FONT_SIZE_KEY, value)
                "fontname" -> storeSkinparam(STYLE_PACKAGE_FONT_NAME_KEY, value)
                "linethickness" -> storeSkinparam(STYLE_PACKAGE_LINE_THICKNESS_KEY, value)
                "shadowing" -> storeSkinparam(STYLE_PACKAGE_SHADOWING_KEY, value)
                else -> warnUnsupportedSkinparam("skinparam package $line")
            }
            else -> warnUnsupportedSkinparam("skinparam $scope $line")
        }
    }

    private fun storeSkinparam(key: String, value: String): IrPatchBatch {
        if (value.isBlank()) return warnUnsupportedSkinparam("skinparam $key")
        styleExtras[key] = value
        return IrPatchBatch(seq, emptyList())
    }

    private fun warnUnsupportedSkinparam(line: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.WARNING, "Unsupported '$line' ignored", "PLANTUML-W001"))))

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

    private fun ensureActorNode(spec: ActorSpec, out: MutableList<IrPatch>) {
        ensureNode(spec.id, spec.label, ACTOR_KIND, out)
        if (!spec.business) return
        val nodeId = NodeId(spec.id)
        val existing = nodes[nodeId] ?: return
        val updated = existing.copy(
            payload = existing.payload + mapOf(ACTOR_VARIANT_KEY to BUSINESS_ACTOR_VARIANT),
        )
        nodes[nodeId] = updated
    }

    private fun flushPendingNote(note: PendingNote): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        val text = note.lines.joinToString("\n").trim()
        if (text.isEmpty()) {
            out += addDiagnostic(Diagnostic(Severity.ERROR, "Empty usecase note block", "PLANTUML-E006"))
        } else if (note.target != null) {
            addAnchoredNote(note.target, note.placement, text, out)
        } else {
            addStandaloneNote(text, out)
        }
        pendingNote = null
        return IrPatchBatch(seq, out)
    }

    private fun addAnchoredNote(target: NodeId, placement: String, text: String, out: MutableList<IrPatch>) {
        val noteId = NodeId("${target.value}__note_${noteSeq++}")
        val parent = clusterStack.lastOrNull()
        val note = Node(
            id = noteId,
            label = RichLabel.Plain(text),
            shape = NodeShape.Note,
            style = styleFor(NOTE_KIND),
            payload = buildMap {
                put(KIND_KEY, NOTE_KIND)
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
                width = 1.25f,
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
            style = styleFor(NOTE_KIND),
            payload = buildMap {
                put(KIND_KEY, NOTE_KIND)
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

    private fun parseQuotedOrSimple(body: String): AliasSpec? {
        val quotedAs = Regex("^\"([^\"]+)\"\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (quotedAs != null) return AliasSpec(id = quotedAs.groupValues[2], label = quotedAs.groupValues[1])
        val aliasQuoted = Regex("^([A-Za-z0-9_.:-]+)\\s+as\\s+\"([^\"]+)\"$").matchEntire(body)
        if (aliasQuoted != null) return AliasSpec(id = aliasQuoted.groupValues[1], label = aliasQuoted.groupValues[2])
        val simple = Regex("^([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (simple != null) return AliasSpec(id = simple.groupValues[1], label = simple.groupValues[1])
        return null
    }

    private fun parseActorSpec(body: String, business: Boolean): ActorSpec? {
        val colon = parseActorColonSpec(body)
        if (colon != null) return colon.copy(business = business || colon.business)
        val quotedOrSimple = parseQuotedOrSimple(body) ?: return null
        return ActorSpec(id = quotedOrSimple.id, label = quotedOrSimple.label, business = business)
    }

    private fun parseActorColonSpec(body: String): ActorSpec? {
        val businessAliased = Regex("^:([^:]+):/\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (businessAliased != null) {
            return ActorSpec(
                id = businessAliased.groupValues[2],
                label = businessAliased.groupValues[1].trim(),
                business = true,
            )
        }
        val plainAliased = Regex("^:([^:]+):\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (plainAliased != null) {
            return ActorSpec(
                id = plainAliased.groupValues[2],
                label = plainAliased.groupValues[1].trim(),
                business = false,
            )
        }
        val business = Regex("^:([^:]+):/$").matchEntire(body)
        if (business != null) {
            val label = business.groupValues[1].trim()
            return ActorSpec(id = sanitizeId(label), label = label, business = true)
        }
        val plain = Regex("^:([^:]+):$").matchEntire(body)
        if (plain != null) {
            val label = plain.groupValues[1].trim()
            return ActorSpec(id = sanitizeId(label), label = label, business = false)
        }
        return null
    }

    private fun isActorColonVariant(line: String): Boolean =
        parseActorColonSpec(line) != null

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

    private fun normalizeRelationSemantic(raw: String?): RelationSemantic {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return RelationSemantic(null, null)
        val normalized = text.removePrefix("<<").removeSuffix(">>").trim().lowercase()
        return when (normalized) {
            "include" -> RelationSemantic(INCLUDE_KIND, "<<include>>")
            "extend", "extends" -> RelationSemantic(EXTEND_KIND, "<<extend>>")
            else -> RelationSemantic(null, text)
        }
    }

    private fun relationColor(kind: String?): ArgbColor = when (kind) {
        INCLUDE_KIND -> ArgbColor(0xFF5E35B1.toInt())
        EXTEND_KIND -> ArgbColor(0xFF00897B.toInt())
        else -> ArgbColor(0xFF546E7A.toInt())
    }

    private fun shapeFor(kind: String): NodeShape = when (kind) {
        ACTOR_KIND -> NodeShape.RoundedBox
        NOTE_KIND -> NodeShape.Note
        else -> NodeShape.Stadium
    }

    private fun styleFor(kind: String): NodeStyle = when (kind) {
        ACTOR_KIND -> NodeStyle(
            fill = null,
            stroke = ArgbColor(0xFF455A64.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF263238.toInt()),
        )
        NOTE_KIND -> NodeStyle(
            fill = ArgbColor(0xFFFFF8E1.toInt()),
            stroke = ArgbColor(0xFFFFA000.toInt()),
            strokeWidth = 1.25f,
            textColor = ArgbColor(0xFF5D4037.toInt()),
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

    private data class ActorSpec(
        val id: String,
        val label: String,
        val business: Boolean,
    )

    private data class RelationSemantic(
        val kind: String?,
        val displayLabel: String?,
    )

}
