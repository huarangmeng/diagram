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
        const val ER_ATTRIBUTE_TYPE_KEY = "plantuml.erd.attribute.type"
        const val ER_ATTRIBUTE_FLAGS_KEY = "plantuml.erd.attribute.flags"
        private val IDENT = Regex("[A-Za-z0-9_.:-]+")
        private val REL_OP = Regex("[|}{o.\\-]+")
    }

    private val knownNodes: MutableSet<NodeId> = LinkedHashSet()
    private val nodes: MutableList<Node> = ArrayList()
    private val edges: MutableList<Edge> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()

    private var seq: Long = 0
    private var currentEntity: NodeId? = null
    private var direction: Direction? = null

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
        val name = work.substringBefore(':').trim()
        val type = work.substringAfter(':', "").trim()
        if (name.isEmpty()) return errorBatch("Invalid attribute line (expected: [*|+|#]name [: type] [<<PK/FK/UK>>])")
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
        val patches = ArrayList<IrPatch>()
        registerEntity(a, a.value, patches)
        registerEntity(b, b.value, patches)
        val fullLabel = buildString {
            append(op)
            if (!label.isNullOrBlank()) {
                append(' ')
                append(label)
            }
        }
        val edge = Edge(
            from = a,
            to = b,
            label = RichLabel.Plain(fullLabel),
            arrow = ArrowEnds.None,
            style = relationshipStyleFor(op),
        )
        edges += edge
        patches += IrPatch.AddEdge(edge)
        return IrPatchBatch(seq, patches)
    }

    private fun registerEntity(id: NodeId, label: String, out: MutableList<IrPatch>) {
        if (id in knownNodes) return
        knownNodes += id
        val n = Node(
            id = id,
            label = RichLabel.Plain(label.ifEmpty { id.value }),
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

    private fun registerAttribute(entity: NodeId, type: String, name: String, flags: List<String>, out: MutableList<IrPatch>) {
        val attrId = NodeId("${entity.value}::${name}")
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
        val quotedAs = Regex("^\"([^\"]+)\"\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (quotedAs != null) return AliasSpec(quotedAs.groupValues[2], quotedAs.groupValues[1])
        val simple = IDENT.matchEntire(body)
        if (simple != null) return AliasSpec(simple.value, simple.value)
        return null
    }

    private fun splitInlineAttributes(inner: String): List<String> {
        val parts = inner.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isNotEmpty()) parts else listOf(inner)
    }

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
}
