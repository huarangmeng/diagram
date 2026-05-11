package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.Edge
import com.hrm.diagram.core.ir.EdgeKind
import com.hrm.diagram.core.ir.EdgeStyle
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Cluster
import com.hrm.diagram.core.ir.ClusterStyle
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
 * Streaming parser for the Phase-4 PlantUML `object` MVP.
 *
 * Example:
 * ```kotlin
 * val parser = PlantUmlObjectParser()
 * parser.acceptLine("object Order")
 * parser.acceptLine("Order : id = 1")
 * parser.acceptLine("Order --> Customer")
 * val ir = parser.snapshot()
 * ```
 */
@DiagramApi
class PlantUmlObjectParser {
    companion object {
        const val KIND_KEY = "plantuml.object.kind"
        const val MEMBERS_KEY = "plantuml.object.members"
        const val PARENT_KEY = "plantuml.object.parent"
        const val NOTE_TARGET_KEY = "plantuml.object.note.target"
        const val NOTE_PLACEMENT_KEY = "plantuml.object.note.placement"
        const val STYLE_OBJECT_FILL_KEY = "plantuml.object.style.object.fill"
        const val STYLE_OBJECT_STROKE_KEY = "plantuml.object.style.object.stroke"
        const val STYLE_OBJECT_TEXT_KEY = "plantuml.object.style.object.text"
        const val STYLE_MAP_FILL_KEY = "plantuml.object.style.map.fill"
        const val STYLE_MAP_STROKE_KEY = "plantuml.object.style.map.stroke"
        const val STYLE_MAP_TEXT_KEY = "plantuml.object.style.map.text"
        const val STYLE_JSON_FILL_KEY = "plantuml.object.style.json.fill"
        const val STYLE_JSON_STROKE_KEY = "plantuml.object.style.json.stroke"
        const val STYLE_JSON_TEXT_KEY = "plantuml.object.style.json.text"
        const val STYLE_NOTE_FILL_KEY = "plantuml.object.style.note.fill"
        const val STYLE_NOTE_STROKE_KEY = "plantuml.object.style.note.stroke"
        const val STYLE_NOTE_TEXT_KEY = "plantuml.object.style.note.text"
        const val STYLE_PACKAGE_FILL_KEY = "plantuml.object.style.package.fill"
        const val STYLE_PACKAGE_STROKE_KEY = "plantuml.object.style.package.stroke"
        const val STYLE_PACKAGE_TEXT_KEY = "plantuml.object.style.package.text"
        const val STYLE_NAMESPACE_FILL_KEY = "plantuml.object.style.namespace.fill"
        const val STYLE_NAMESPACE_STROKE_KEY = "plantuml.object.style.namespace.stroke"
        const val STYLE_NAMESPACE_TEXT_KEY = "plantuml.object.style.namespace.text"
        const val STYLE_EDGE_COLOR_KEY = "plantuml.object.style.edge.color"
        val RELATION_OPERATORS = listOf("<|--", "*--", "o--", "-->", "<--", "..>", "<..", "--", "..")
        val IDENTIFIER = Regex("[A-Za-z0-9_.:-]+")
        private val SUPPORTED_SKINPARAM_SCOPES = setOf("object", "map", "json", "note", "package", "namespace")

        fun styleFontSizeKey(scope: String): String = "plantuml.object.style.$scope.fontSize"
        fun styleFontNameKey(scope: String): String = "plantuml.object.style.$scope.fontName"
        fun styleLineThicknessKey(scope: String): String = "plantuml.object.style.$scope.lineThickness"
        fun styleShadowingKey(scope: String): String = "plantuml.object.style.$scope.shadowing"
    }

    private data class AliasSpec(
        val id: String,
        val label: String,
    )

    private data class PendingNote(
        val target: NodeId?,
        val placement: String,
        val lines: MutableList<String> = ArrayList(),
    )

    private data class ClusterBuilder(
        val id: NodeId,
        val kind: String,
        val title: String,
        val children: MutableList<NodeId> = ArrayList(),
        val nested: MutableList<ClusterBuilder> = ArrayList(),
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val edges: MutableList<Edge> = ArrayList()
    private val rootClusters: MutableList<ClusterBuilder> = ArrayList()
    private val clusterStack: ArrayDeque<ClusterBuilder> = ArrayDeque()
    private val styleExtras: LinkedHashMap<String, String> = LinkedHashMap()
    private val skinparamSupport = PlantUmlSkinparamSupport(
        styleExtras = styleExtras,
        supportedScopes = SUPPORTED_SKINPARAM_SCOPES,
        scopeKeys = mapOf(
            "object" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_OBJECT_FILL_KEY,
                strokeKey = STYLE_OBJECT_STROKE_KEY,
                textKey = STYLE_OBJECT_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("object"),
                fontNameKey = styleFontNameKey("object"),
                lineThicknessKey = styleLineThicknessKey("object"),
                shadowingKey = styleShadowingKey("object"),
            ),
            "map" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_MAP_FILL_KEY,
                strokeKey = STYLE_MAP_STROKE_KEY,
                textKey = STYLE_MAP_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("map"),
                fontNameKey = styleFontNameKey("map"),
                lineThicknessKey = styleLineThicknessKey("map"),
                shadowingKey = styleShadowingKey("map"),
            ),
            "json" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_JSON_FILL_KEY,
                strokeKey = STYLE_JSON_STROKE_KEY,
                textKey = STYLE_JSON_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("json"),
                fontNameKey = styleFontNameKey("json"),
                lineThicknessKey = styleLineThicknessKey("json"),
                shadowingKey = styleShadowingKey("json"),
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
            "namespace" to PlantUmlSkinparamScopeKeys(
                fillKey = STYLE_NAMESPACE_FILL_KEY,
                strokeKey = STYLE_NAMESPACE_STROKE_KEY,
                textKey = STYLE_NAMESPACE_TEXT_KEY,
                fontSizeKey = styleFontSizeKey("namespace"),
                fontNameKey = styleFontNameKey("namespace"),
                lineThicknessKey = styleLineThicknessKey("namespace"),
                shadowingKey = styleShadowingKey("namespace"),
            ),
        ),
        directKeys = mapOf(
            "objectbackgroundcolor" to STYLE_OBJECT_FILL_KEY,
            "objectbordercolor" to STYLE_OBJECT_STROKE_KEY,
            "objectfontcolor" to STYLE_OBJECT_TEXT_KEY,
            "mapbackgroundcolor" to STYLE_MAP_FILL_KEY,
            "mapbordercolor" to STYLE_MAP_STROKE_KEY,
            "mapfontcolor" to STYLE_MAP_TEXT_KEY,
            "jsonbackgroundcolor" to STYLE_JSON_FILL_KEY,
            "jsonbordercolor" to STYLE_JSON_STROKE_KEY,
            "jsonfontcolor" to STYLE_JSON_TEXT_KEY,
            "notebackgroundcolor" to STYLE_NOTE_FILL_KEY,
            "notebordercolor" to STYLE_NOTE_STROKE_KEY,
            "notefontcolor" to STYLE_NOTE_TEXT_KEY,
            "packagebackgroundcolor" to STYLE_PACKAGE_FILL_KEY,
            "packagebordercolor" to STYLE_PACKAGE_STROKE_KEY,
            "packagefontcolor" to STYLE_PACKAGE_TEXT_KEY,
            "namespacebackgroundcolor" to STYLE_NAMESPACE_FILL_KEY,
            "namespacebordercolor" to STYLE_NAMESPACE_STROKE_KEY,
            "namespacefontcolor" to STYLE_NAMESPACE_TEXT_KEY,
            "arrowcolor" to STYLE_EDGE_COLOR_KEY,
        ),
        warnUnsupported = ::warnUnsupportedSkinparam,
        emptyBatch = { IrPatchBatch(seq, emptyList()) },
    )

    private var currentObject: NodeId? = null
    private var pendingNote: PendingNote? = null
    private var noteSeq: Int = 0
    private var clusterSeq: Int = 0
    private var seq: Long = 0
    private var direction: Direction = Direction.LR

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
        currentObject?.let { current ->
            if (trimmed == "}") {
                currentObject = null
                return IrPatchBatch(seq, emptyList())
            }
            return parseMemberInto(current, trimmed)
        }

        return when {
            trimmed.startsWith("skinparam", ignoreCase = true) -> skinparamSupport.acceptDirective(trimmed)
            trimmed.equals("left to right direction", ignoreCase = true) -> {
                direction = Direction.LR
                IrPatchBatch(seq, emptyList())
            }
            trimmed.equals("right to left direction", ignoreCase = true) -> {
                direction = Direction.RL
                IrPatchBatch(seq, emptyList())
            }
            trimmed.equals("top to bottom direction", ignoreCase = true) -> {
                direction = Direction.TB
                IrPatchBatch(seq, emptyList())
            }
            trimmed.equals("bottom to top direction", ignoreCase = true) -> {
                direction = Direction.BT
                IrPatchBatch(seq, emptyList())
            }
            trimmed.startsWith("package ", ignoreCase = true) -> parseClusterDecl(trimmed, "package")
            trimmed.startsWith("namespace ", ignoreCase = true) -> parseClusterDecl(trimmed, "namespace")
            trimmed.startsWith("object ", ignoreCase = true) -> parseObjectDecl(trimmed)
            trimmed.startsWith("map ", ignoreCase = true) -> parseObjectDecl(trimmed, keyword = "map", kind = "map")
            trimmed.startsWith("json ", ignoreCase = true) -> parseObjectDecl(trimmed, keyword = "json", kind = "json")
            trimmed.startsWith("note ", ignoreCase = true) -> parseNote(trimmed)
            trimmed == "}" -> closeCluster()
            findRelationOperator(trimmed) != null -> parseRelation(trimmed)
            isDottedMember(trimmed) -> parseDottedMember(trimmed)
            else -> errorBatch("Unsupported PlantUML object statement: $trimmed")
        }
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (pendingNote != null) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed object note block before end of PlantUML block",
                    code = "PLANTUML-E008",
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
        if (currentObject != null) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed object member block before end of PlantUML block",
                    code = "PLANTUML-E008",
                ),
            )
            currentObject = null
        }
        if (clusterStack.isNotEmpty()) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed object package/namespace block before end of PlantUML block",
                    code = "PLANTUML-E008",
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
        clusters = rootClusters.map { it.build() },
        sourceLanguage = SourceLanguage.PLANTUML,
        styleHints = StyleHints(direction = direction, extras = styleExtras),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseObjectDecl(line: String, keyword: String = "object", kind: String = "object"): IrPatchBatch {
        var body = line.removePrefix(keyword).trim()
        val opens = body.endsWith("{")
        if (opens) body = body.removeSuffix("{").trim()
        val spec = parseAliasSpec(body) ?: return errorBatch("Invalid PlantUML object declaration: $line")
        ensureNode(spec.id, spec.label, kind = kind)
        if (opens) currentObject = NodeId(spec.id)
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseDottedMember(line: String): IrPatchBatch {
        val idx = line.indexOf(':')
        if (idx <= 0 || idx >= line.lastIndex) return errorBatch("Invalid object property syntax: $line")
        val objectName = line.substring(0, idx).trim()
        val member = line.substring(idx + 1).trim()
        if (objectName.isEmpty() || member.isEmpty()) return errorBatch("Invalid object property syntax: $line")
        ensureNode(objectName, objectName, kind = "object")
        return parseMemberInto(NodeId(objectName), member)
    }

    private fun parseMemberInto(id: NodeId, line: String): IrPatchBatch {
        val member = line.removePrefix(":").removeSuffix(";").trim()
        if (member.isEmpty()) return errorBatch("Invalid empty object property")
        val existing = nodes[id] ?: Node(id = id, label = RichLabel.Plain(id.value), shape = NodeShape.Box, style = objectStyle("object"))
        val kind = existing.payload[KIND_KEY].orEmpty().ifEmpty { "object" }
        val members = existing.payload[MEMBERS_KEY].orEmpty().split('\n').filter { it.isNotEmpty() }.toMutableList()
        members += member
        nodes[id] = existing.copy(
            payload = existing.payload + mapOf(
                KIND_KEY to kind,
                MEMBERS_KEY to members.joinToString("\n"),
            ),
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseRelation(line: String): IrPatchBatch {
        val op = findRelationOperator(line) ?: return errorBatch("Invalid object relation: $line")
        val parts = line.split(":", limit = 2)
        val relationPart = parts[0].trim()
        val label = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(RichLabel::Plain)
        val pattern = Regex("^(.*?)\\s*" + Regex.escape(op) + "\\s*(.*?)$")
        val match = pattern.matchEntire(relationPart) ?: return errorBatch("Invalid object relation syntax: $line")
        val fromRaw = match.groupValues[1].trim()
        val toRaw = match.groupValues[2].trim()
        if (!fromRaw.matches(IDENTIFIER) || !toRaw.matches(IDENTIFIER)) return errorBatch("Invalid object relation endpoints: $line")
        ensureNode(fromRaw, fromRaw, kind = "object")
        ensureNode(toRaw, toRaw, kind = "object")
        val edge = Edge(
            from = NodeId(fromRaw),
            to = NodeId(toRaw),
            label = label,
            kind = when (op) {
                "..>", "<..", ".." -> EdgeKind.Dashed
                else -> EdgeKind.Solid
            },
            arrow = when (op) {
                "-->", "..>", "<|--", "*--", "o--" -> ArrowEnds.ToOnly
                "<--", "<.." -> ArrowEnds.FromOnly
                else -> ArrowEnds.None
            },
            style = EdgeStyle(
                color = ArgbColor(0xFF546E7A.toInt()),
                width = if (op == "*--" || op == "o--") 1.8f else 1.5f,
                dash = if (op.contains("..")) listOf(6f, 4f) else null,
            ),
        )
        if (edges.any { it.from == edge.from && it.to == edge.to && it.kind == edge.kind && it.arrow == edge.arrow && it.label == edge.label }) {
            return IrPatchBatch(seq, emptyList())
        }
        edges += edge
        return IrPatchBatch(seq, listOf(IrPatch.AddEdge(edge)))
    }

    private fun parseClusterDecl(line: String, keyword: String): IrPatchBatch {
        var body = line.removePrefix(keyword).trim()
        if (!body.endsWith("{")) return errorBatch("Expected '{' after $keyword declaration")
        body = body.removeSuffix("{").trim()
        val title = body.removePrefix("\"").removeSuffix("\"").trim().ifEmpty { keyword }
        val id = NodeId("${keyword}_${sanitizeId(title)}_${clusterSeq++}")
        val builder = ClusterBuilder(id = id, kind = keyword, title = title)
        clusterStack.lastOrNull()?.nested?.add(builder) ?: rootClusters.add(builder)
        clusterStack.addLast(builder)
        return IrPatchBatch(seq, emptyList())
    }

    private fun closeCluster(): IrPatchBatch {
        if (clusterStack.isEmpty()) return errorBatch("Unmatched '}' in PlantUML object body")
        clusterStack.removeLast()
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseNote(line: String): IrPatchBatch {
        val anchoredInline = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*:\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (anchoredInline != null) {
            val target = NodeId(anchoredInline.groupValues[2])
            ensureNode(target.value, target.value, kind = "object")
            return addAnchoredNote(target, anchoredInline.groupValues[1].lowercase(), anchoredInline.groupValues[3].trim())
        }
        val anchoredBlock = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (anchoredBlock != null) {
            val target = NodeId(anchoredBlock.groupValues[2])
            ensureNode(target.value, target.value, kind = "object")
            pendingNote = PendingNote(target = target, placement = anchoredBlock.groupValues[1].lowercase())
            return IrPatchBatch(seq, emptyList())
        }
        val standaloneQuoted = Regex("^note\\s+\"([^\"]+)\"$", RegexOption.IGNORE_CASE).matchEntire(line)
        if (standaloneQuoted != null) return addStandaloneNote(standaloneQuoted.groupValues[1])
        if (line.equals("note", ignoreCase = true)) {
            pendingNote = PendingNote(target = null, placement = "standalone")
            return IrPatchBatch(seq, emptyList())
        }
        return errorBatch("Invalid PlantUML object note syntax: $line")
    }

    private fun flushPendingNote(note: PendingNote): IrPatchBatch {
        val text = note.lines.joinToString("\n").trim()
        if (text.isEmpty()) return errorBatch("Invalid empty object note")
        return if (note.target != null) addAnchoredNote(note.target, note.placement, text) else addStandaloneNote(text)
    }

    private fun addAnchoredNote(target: NodeId, placement: String, text: String): IrPatchBatch {
        val noteId = NodeId("${target.value}__note_${noteSeq++}")
        val node = Node(
            id = noteId,
            label = RichLabel.Plain(text),
            shape = NodeShape.Note,
            style = objectStyle("note"),
            payload = buildMap {
                put(KIND_KEY, "note")
                put(NOTE_TARGET_KEY, target.value)
                put(NOTE_PLACEMENT_KEY, placement)
                clusterStack.lastOrNull()?.let { put(PARENT_KEY, it.id.value) }
            },
        )
        nodes[noteId] = node
        clusterStack.lastOrNull()?.children?.add(noteId)
        val edge = Edge(
            from = noteId,
            to = target,
            kind = EdgeKind.Dashed,
            arrow = ArrowEnds.None,
            style = EdgeStyle(
                color = ArgbColor(0xFFFFA000.toInt()),
                width = 1.25f,
                dash = listOf(4f, 4f),
            ),
        )
        edges += edge
        return IrPatchBatch(seq, listOf(IrPatch.AddNode(node), IrPatch.AddEdge(edge)))
    }

    private fun addStandaloneNote(text: String): IrPatchBatch {
        val noteId = NodeId("note_${noteSeq++}")
        val node = Node(
            id = noteId,
            label = RichLabel.Plain(text),
            shape = NodeShape.Note,
            style = objectStyle("note"),
            payload = buildMap {
                put(KIND_KEY, "note")
                clusterStack.lastOrNull()?.let { put(PARENT_KEY, it.id.value) }
            },
        )
        nodes[noteId] = node
        clusterStack.lastOrNull()?.children?.add(noteId)
        return IrPatchBatch(seq, listOf(IrPatch.AddNode(node)))
    }

    private fun ensureNode(idText: String, label: String, kind: String) {
        val id = NodeId(idText)
        val existing = nodes[id]
        val effectiveKind = existing?.payload?.get(KIND_KEY).orEmpty().ifEmpty { kind }
        val base = Node(
            id = id,
            label = RichLabel.Plain(label),
            shape = if (effectiveKind == "note") NodeShape.Note else NodeShape.Box,
            style = objectStyle(effectiveKind),
            payload = buildMap {
                put(KIND_KEY, effectiveKind)
                val parent = existing?.payload?.get(PARENT_KEY) ?: clusterStack.lastOrNull()?.id?.value
                if (parent != null) put(PARENT_KEY, parent)
            },
        )
        nodes[id] = if (existing == null) base else existing.copy(
            label = if (label.isNotEmpty()) base.label else existing.label,
            payload = existing.payload + base.payload,
        )
        if (existing == null) clusterStack.lastOrNull()?.children?.add(id)
    }

    private fun parseAliasSpec(body: String): AliasSpec? {
        val quotedAs = Regex("^\"([^\"]+)\"\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (quotedAs != null) return AliasSpec(id = quotedAs.groupValues[2], label = quotedAs.groupValues[1])
        val aliasQuoted = Regex("^([A-Za-z0-9_.:-]+)\\s+as\\s+\"([^\"]+)\"$").matchEntire(body)
        if (aliasQuoted != null) return AliasSpec(id = aliasQuoted.groupValues[1], label = aliasQuoted.groupValues[2])
        val simple = IDENTIFIER.matchEntire(body)
        if (simple != null) return AliasSpec(id = simple.value, label = simple.value)
        return null
    }

    private fun objectStyle(kind: String): NodeStyle = when (kind) {
        "map" -> NodeStyle(
            fill = ArgbColor(0xFFE8F5E9.toInt()),
            stroke = ArgbColor(0xFF2E7D32.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF1B5E20.toInt()),
        )
        "json" -> NodeStyle(
            fill = ArgbColor(0xFFE3F2FD.toInt()),
            stroke = ArgbColor(0xFF1565C0.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF0D47A1.toInt()),
        )
        "note" -> NodeStyle(
            fill = ArgbColor(0xFFFFF8E1.toInt()),
            stroke = ArgbColor(0xFFFFA000.toInt()),
            strokeWidth = 1.25f,
            textColor = ArgbColor(0xFF6D4C41.toInt()),
        )
        else -> NodeStyle(
            fill = ArgbColor(0xFFF3E5F5.toInt()),
            stroke = ArgbColor(0xFF6A1B9A.toInt()),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF4A148C.toInt()),
        )
    }

    private fun isDottedMember(line: String): Boolean =
        ':' in line && findRelationOperator(line) == null && IDENTIFIER.matches(line.substringBefore(':').trim())

    private fun findRelationOperator(line: String): String? {
        for (candidate in RELATION_OPERATORS) {
            if (line.contains(candidate)) return candidate
        }
        return null
    }

    private fun errorBatch(message: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.ERROR, message, "PLANTUML-E008"))))

    private fun addDiagnostic(diagnostic: Diagnostic): IrPatch {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }

    private fun warnUnsupportedSkinparam(line: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.WARNING, "Unsupported '$line' ignored", "PLANTUML-W001"))))

    private fun ClusterBuilder.build(): Cluster = Cluster(
        id = id,
        label = RichLabel.Plain("$kind\n$title"),
        children = children.toList(),
        nestedClusters = nested.map { it.build() },
        style = ClusterStyle(
            fill = ArgbColor(0xFFF5F5F5.toInt()),
            stroke = ArgbColor(0xFF90A4AE.toInt()),
            strokeWidth = 1.5f,
        ),
    )

    private fun sanitizeId(text: String): String = text.replace(Regex("[^A-Za-z0-9_.:-]+"), "_").trim('_').ifEmpty { "cluster" }
}
