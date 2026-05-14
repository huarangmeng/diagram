package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.ArgbColor
import com.hrm.diagram.core.ir.ArrowEnds
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.Edge
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
 * Streaming parser for the Phase-4 PlantUML `erd` MVP.
 *
 * Example:
 * ```kotlin
 * val parser = PlantUmlErdParser()
 * parser.acceptLine("entity Customer {")
 * parser.acceptLine("*id : uuid")
 * parser.acceptLine("}")
 * parser.acceptLine("Customer ||--o{ Order : places")
 * val ir = parser.snapshot()
 * ```
 */
@DiagramApi
class PlantUmlErdParser {
    companion object {
        const val ER_KIND_KEY = "plantuml.erd.kind"
        const val ER_ENTITY_KIND = "entity"
        const val ER_ATTRIBUTE_KIND = "attribute"
        const val ER_NOTE_KIND = "note"
        const val ER_ATTRIBUTE_NAME_KEY = "plantuml.erd.attribute.name"
        const val ER_ATTRIBUTE_TYPE_KEY = "plantuml.erd.attribute.type"
        const val ER_ATTRIBUTE_FLAGS_KEY = "plantuml.erd.attribute.flags"
        const val ER_NOTE_TARGET_KEY = "plantuml.erd.note.target"
        const val ER_NOTE_PLACEMENT_KEY = "plantuml.erd.note.placement"
        const val ER_RELATION_OP_KEY = "plantuml.erd.relation.op"
        const val ER_RELATION_LEFT_KEY = "plantuml.erd.relation.left"
        const val ER_RELATION_RIGHT_KEY = "plantuml.erd.relation.right"
        const val ER_RELATION_LINE_KEY = "plantuml.erd.relation.line"
        private val IDENT = Regex("[A-Za-z0-9_.:-]+")
        private val REL_OP = Regex("[|}{o.\\-]+")
        private val ANCHORED_NOTE = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*:\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        )
        private val ATTRIBUTE_TOKEN = Regex(""""[^"]+"|\S+""")
        private val COMMON_ATTRIBUTE_TYPES = setOf(
            "string", "text", "uuid", "int", "integer", "long", "bigint", "smallint",
            "decimal", "numeric", "float", "double", "real", "boolean", "bool", "date",
            "datetime", "timestamp", "timestamptz", "time", "json", "jsonb", "blob", "clob",
            "varchar", "char", "binary", "varbinary",
        )
    }

    private val knownNodes: MutableSet<NodeId> = LinkedHashSet()
    private val nodes: MutableList<Node> = ArrayList()
    private val edges: MutableList<Edge> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()

    private var seq: Long = 0
    private var currentEntity: NodeId? = null
    private var direction: Direction? = null
    private var noteSeq: Long = 0

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            return IrPatchBatch(seq, emptyList())
        }

        currentEntity?.let { entity ->
            if (trimmed == "}") {
                currentEntity = null
                return IrPatchBatch(seq, emptyList())
            }
            return parseAttributeLine(entity, trimmed)
        }

        if (trimmed.equals("left to right direction", ignoreCase = true)) {
            direction = Direction.LR
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.equals("right to left direction", ignoreCase = true)) {
            direction = Direction.RL
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.equals("top to bottom direction", ignoreCase = true)) {
            direction = Direction.TB
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed.equals("bottom to top direction", ignoreCase = true)) {
            direction = Direction.BT
            return IrPatchBatch(seq, emptyList())
        }

        if (trimmed.startsWith("entity ", ignoreCase = true)) {
            return parseEntityDecl(trimmed)
        }
        if (trimmed.startsWith("note ", ignoreCase = true)) {
            return parseNoteLine(trimmed)
        }
        if (REL_OP.containsMatchIn(trimmed) && ':' !in trimmed.substringBefore(' ')) {
            return parseRelationshipLine(trimmed)
        }
        return errorBatch("Unsupported PlantUML erd statement: $trimmed")
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (currentEntity != null) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed entity block before end of PlantUML block",
                    code = "PLANTUML-E010",
                ),
            )
            currentEntity = null
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
        nodes = nodes.toList(),
        edges = edges.toList(),
        sourceLanguage = SourceLanguage.PLANTUML,
        styleHints = StyleHints(direction = direction),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseEntityDecl(line: String): IrPatchBatch {
        var body = line.removePrefix("entity").trim()
        val patches = ArrayList<IrPatch>()
        val inlineBlock = Regex("^(.*)\\{(.*)\\}$").matchEntire(body)
        if (inlineBlock != null) {
            val spec = parseAliasSpec(inlineBlock.groupValues[1].trim())
                ?: return errorBatch("Invalid PlantUML entity declaration: $line")
            val entityId = NodeId(spec.id)
            registerEntity(entityId, spec.label, patches)
            val inner = inlineBlock.groupValues[2].trim()
            if (inner.isNotEmpty()) {
                for (part in splitInlineAttributes(inner)) {
                    val batch = parseAttributeLine(entityId, part)
                    patches += batch.patches
                }
            }
            return IrPatchBatch(seq, patches)
        }
        val opens = body.endsWith("{")
        if (opens) body = body.removeSuffix("{").trim()
        val spec = parseAliasSpec(body) ?: return errorBatch("Invalid PlantUML entity declaration: $line")
        val entityId = NodeId(spec.id)
        registerEntity(entityId, spec.label, patches)
        if (opens) currentEntity = entityId
        return IrPatchBatch(seq, patches)
    }

    private fun parseAttributeLine(entity: NodeId, line: String): IrPatchBatch {
        val raw = line.removeSuffix(";").trim()
        if (raw.isEmpty()) return errorBatch("Invalid empty attribute line")
        var flags = mutableListOf<String>()
        var work = raw
        when {
            work.startsWith("*") -> {
                flags += "PK"
                work = work.removePrefix("*").trim()
            }
            work.startsWith("+") -> {
                flags += "FK"
                work = work.removePrefix("+").trim()
            }
            work.startsWith("#") -> {
                flags += "UK"
                work = work.removePrefix("#").trim()
            }
        }
        val stereotypeFlags = Regex("<<([^>]+)>>").findAll(work).map { it.groupValues[1].trim().uppercase() }.toList()
        if (stereotypeFlags.isNotEmpty()) {
            flags += stereotypeFlags
            work = work.replace(Regex("\\s*<<[^>]+>>"), "").trim()
        }
        work = work.replace(Regex("""\s*\{[^}]+\}"""), "").trim()
        val parsed = parseAttributeSpec(work)
            ?: return errorBatch("Invalid attribute line (expected: [*|+|#]name [: type] [<<PK/FK/UK>>])")
        val name = parsed.first
        val type = parsed.second
        val patches = ArrayList<IrPatch>()
        registerAttribute(entity, type, name, flags.distinct(), patches)
        return IrPatchBatch(seq, patches)
    }

    private fun parseRelationshipLine(line: String): IrPatchBatch {
        val pattern = Regex("""^([A-Za-z0-9_.:-]+)\s+([|}{o.\-]+)\s+([A-Za-z0-9_.:-]+)(?:\s*:\s*(.+))?$""")
        val match = pattern.matchEntire(line) ?: return errorBatch("Invalid relationship line (expected: A <crowfoot> B [: label])")
        val a = NodeId(match.groupValues[1])
        val op = match.groupValues[2]
        val b = NodeId(match.groupValues[3])
        val label = match.groupValues.getOrNull(4)?.trim().orEmpty().takeIf { it.isNotEmpty() }
        val relation = parseRelationshipOperator(op)
            ?: return errorBatch("Invalid relationship operator (expected crowfoot connector with -- or ..)")
        val patches = ArrayList<IrPatch>()
        registerEntity(a, a.value, patches)
        registerEntity(b, b.value, patches)
        val edge = Edge(
            from = a,
            to = b,
            label = label?.let(RichLabel::Plain),
            arrow = ArrowEnds.None,
            style = relationshipStyleFor(op),
            payload = mapOf(
                ER_RELATION_OP_KEY to op,
                ER_RELATION_LEFT_KEY to relation.left,
                ER_RELATION_RIGHT_KEY to relation.right,
                ER_RELATION_LINE_KEY to relation.line,
            ),
        )
        edges += edge
        patches += IrPatch.AddEdge(edge)
        return IrPatchBatch(seq, patches)
    }

    private fun parseRelationshipOperator(op: String): RelationshipOperator? {
        val dashed = op.indexOf("..")
        val solid = op.indexOf("--")
        val connectorIndex = when {
            dashed >= 0 && solid >= 0 -> minOf(dashed, solid)
            dashed >= 0 -> dashed
            solid >= 0 -> solid
            else -> return null
        }
        val connector = op.substring(connectorIndex, connectorIndex + 2)
        return RelationshipOperator(
            left = op.substring(0, connectorIndex),
            right = op.substring(connectorIndex + 2),
            line = if (connector == "..") "dashed" else "solid",
        )
    }

    private fun parseNoteLine(line: String): IrPatchBatch {
        val match = ANCHORED_NOTE.matchEntire(line)
            ?: return errorBatch("Invalid erd note syntax (expected: note <side> of Entity : text)")
        val placement = match.groupValues[1].lowercase()
        val target = NodeId(match.groupValues[2])
        val text = match.groupValues[3].trim()
        if (text.isEmpty()) return errorBatch("Invalid erd note syntax (note text cannot be empty)")
        val patches = ArrayList<IrPatch>()
        registerEntity(target, target.value, patches)
        registerNote(target, placement, text, patches)
        return IrPatchBatch(seq, patches)
    }

    private fun registerEntity(id: NodeId, label: String, out: MutableList<IrPatch>) {
        val normalized = label.ifEmpty { id.value }
        if (id in knownNodes) {
            val index = nodes.indexOfFirst { it.id == id }
            if (index >= 0) {
                val existing = nodes[index]
                val current = (existing.label as? RichLabel.Plain)?.text.orEmpty()
                if (current.isBlank() || current == id.value) {
                    nodes[index] = existing.copy(
                        label = RichLabel.Plain(normalized),
                        payload = existing.payload + mapOf(ER_KIND_KEY to ER_ENTITY_KIND),
                    )
                }
            }
            return
        }
        knownNodes += id
        val n = Node(
            id = id,
            label = RichLabel.Plain(normalized),
            shape = NodeShape.Box,
            style = NodeStyle(
                fill = ArgbColor(0xFFE8F5E9.toInt()),
                stroke = ArgbColor(0xFF2E7D32.toInt()),
                strokeWidth = 1.5f,
                textColor = ArgbColor(0xFF1B5E20.toInt()),
            ),
            payload = mapOf(ER_KIND_KEY to ER_ENTITY_KIND),
        )
        nodes += n
        out += IrPatch.AddNode(n)
    }

    private fun registerNote(target: NodeId, placement: String, text: String, out: MutableList<IrPatch>) {
        val noteId = NodeId("${target.value}::note:${noteSeq++}")
        if (noteId !in knownNodes) {
            knownNodes += noteId
            val note = Node(
                id = noteId,
                label = RichLabel.Plain(text),
                shape = NodeShape.Note,
                style = NodeStyle(
                    fill = ArgbColor(0xFFFFFDE7.toInt()),
                    stroke = ArgbColor(0xFFF9A825.toInt()),
                    strokeWidth = 1.25f,
                    textColor = ArgbColor(0xFF5D4037.toInt()),
                ),
                payload = mapOf(
                    ER_KIND_KEY to ER_NOTE_KIND,
                    ER_NOTE_TARGET_KEY to target.value,
                    ER_NOTE_PLACEMENT_KEY to placement,
                ),
            )
            nodes += note
            out += IrPatch.AddNode(note)
        }
        val edge = Edge(
            from = noteId,
            to = target,
            label = null,
            arrow = ArrowEnds.None,
            style = EdgeStyle(
                color = ArgbColor(0xFFF9A825.toInt()),
                width = 1f,
                dash = listOf(4f, 4f),
            ),
        )
        edges += edge
        out += IrPatch.AddEdge(edge)
    }

    private fun registerAttribute(entity: NodeId, type: String, name: String, flags: List<String>, out: MutableList<IrPatch>) {
        val attrId = NodeId("${entity.value}::${sanitizeId(name)}")
        if (attrId !in knownNodes) {
            knownNodes += attrId
            val normalizedFlags = flags.map { it.uppercase() }
            val suffix = if (normalizedFlags.isEmpty()) "" else " " + normalizedFlags.joinToString(" ")
            val label = if (type.isNotBlank()) "$name: $type$suffix" else "$name$suffix"
            val n = Node(
                id = attrId,
                label = RichLabel.Plain(label),
                shape = NodeShape.RoundedBox,
                style = attributeStyleFor(normalizedFlags),
                payload = buildMap {
                    put(ER_KIND_KEY, ER_ATTRIBUTE_KIND)
                    put(ER_ATTRIBUTE_NAME_KEY, name)
                    if (type.isNotBlank()) put(ER_ATTRIBUTE_TYPE_KEY, type)
                    if (normalizedFlags.isNotEmpty()) put(ER_ATTRIBUTE_FLAGS_KEY, normalizedFlags.joinToString(","))
                },
            )
            nodes += n
            out += IrPatch.AddNode(n)
        }
        val e = Edge(
            from = entity,
            to = attrId,
            label = null,
            arrow = ArrowEnds.None,
            style = EdgeStyle(
                color = ArgbColor(0xFFB0BEC5.toInt()),
                width = 1f,
                dash = listOf(5f, 5f),
            ),
        )
        edges += e
        out += IrPatch.AddEdge(e)
    }

    private fun parseAliasSpec(body: String): AliasSpec? {
        val normalized = body.replace(Regex("\\s*<<[^>]+>>"), "").trim()
        val quotedAs = Regex("^\"([^\"]+)\"\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(normalized)
        if (quotedAs != null) return AliasSpec(quotedAs.groupValues[2], quotedAs.groupValues[1])
        val simpleAs = Regex("^([A-Za-z0-9_.:-]+)\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(normalized)
        if (simpleAs != null) return AliasSpec(simpleAs.groupValues[2], simpleAs.groupValues[1])
        val aliasQuoted = Regex("^([A-Za-z0-9_.:-]+)\\s+as\\s+\"([^\"]+)\"$").matchEntire(normalized)
        if (aliasQuoted != null) return AliasSpec(aliasQuoted.groupValues[1], aliasQuoted.groupValues[2])
        val quotedOnly = Regex("^\"([^\"]+)\"$").matchEntire(normalized)
        if (quotedOnly != null) {
            val label = quotedOnly.groupValues[1]
            return AliasSpec(sanitizeId(label), label)
        }
        val simple = IDENT.matchEntire(normalized)
        if (simple != null) return AliasSpec(simple.value, simple.value)
        return null
    }

    private fun splitInlineAttributes(inner: String): List<String> {
        val parts = inner.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isNotEmpty()) parts else listOf(inner)
    }

    private fun parseAttributeSpec(text: String): Pair<String, String>? {
        val colonIndex = text.indexOf(':')
        if (colonIndex >= 0) {
            val name = normalizeToken(text.substring(0, colonIndex))
            val type = text.substring(colonIndex + 1).trim()
            return name.takeIf { it.isNotEmpty() }?.let { it to type }
        }
        val tokens = ATTRIBUTE_TOKEN.findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) return normalizeToken(tokens.single()).takeIf { it.isNotEmpty() }?.let { it to "" }
        val first = tokens.first()
        val last = tokens.last()
        val typeFirst = looksLikeTypeToken(first) && !looksLikeTypeToken(last)
        return if (typeFirst) {
            val type = normalizeToken(first)
            val name = normalizeToken(tokens.drop(1).joinToString(" "))
            name.takeIf { it.isNotEmpty() }?.let { it to type }
        } else {
            val name = normalizeToken(tokens.dropLast(1).joinToString(" "))
            val type = normalizeToken(last)
            name.takeIf { it.isNotEmpty() }?.let { it to type }
        }
    }

    private fun normalizeToken(text: String): String =
        text.trim().removePrefix("\"").removeSuffix("\"").trim()

    private fun looksLikeTypeToken(token: String): Boolean {
        val normalized = normalizeToken(token).lowercase()
        val base = normalized.substringBefore('(').substringBefore('[')
        return normalized in COMMON_ATTRIBUTE_TYPES ||
            base in COMMON_ATTRIBUTE_TYPES ||
            normalized.endsWith("[]")
    }

    private fun sanitizeId(text: String): String =
        text.replace(Regex("[^A-Za-z0-9_.:-]+"), "_").trim('_').ifEmpty { "entity_$seq" }

    private fun attributeStyleFor(flags: List<String>): NodeStyle = when {
        "PK" in flags -> NodeStyle(
            fill = ArgbColor(0xFFFFF8E1.toInt()),
            stroke = ArgbColor(0xFFF9A825.toInt()),
            strokeWidth = 1.25f,
            textColor = ArgbColor(0xFF795548.toInt()),
        )
        "FK" in flags -> NodeStyle(
            fill = ArgbColor(0xFFE3F2FD.toInt()),
            stroke = ArgbColor(0xFF1E88E5.toInt()),
            strokeWidth = 1.25f,
            textColor = ArgbColor(0xFF0D47A1.toInt()),
        )
        "UK" in flags -> NodeStyle(
            fill = ArgbColor(0xFFF3E5F5.toInt()),
            stroke = ArgbColor(0xFF8E24AA.toInt()),
            strokeWidth = 1.25f,
            textColor = ArgbColor(0xFF6A1B9A.toInt()),
        )
        else -> NodeStyle(
            fill = ArgbColor(0xFFF1F8E9.toInt()),
            stroke = ArgbColor(0xFF7CB342.toInt()),
            strokeWidth = 1.25f,
            textColor = ArgbColor(0xFF33691E.toInt()),
        )
    }

    private fun relationshipStyleFor(op: String): EdgeStyle =
        EdgeStyle(
            color = ArgbColor(0xFF455A64.toInt()),
            width = 1.5f,
            dash = if (op.contains("..")) listOf(6f, 4f) else null,
            labelBg = ArgbColor(0xFFF5F5F5.toInt()),
        )

    private fun errorBatch(message: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.ERROR, message, "PLANTUML-E010"))))

    private fun addDiagnostic(diagnostic: Diagnostic): IrPatch {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }

    private data class AliasSpec(val id: String, val label: String)
    private data class RelationshipOperator(val left: String, val right: String, val line: String)
}
