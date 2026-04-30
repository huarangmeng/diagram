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
        val RELATION_OPERATORS = listOf("<|--", "*--", "o--", "-->", "<--", "..>", "<..", "--", "..")
        val IDENTIFIER = Regex("[A-Za-z0-9_.:-]+")
    }

    private data class AliasSpec(
        val id: String,
        val label: String,
    )

    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val nodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val edges: MutableList<Edge> = ArrayList()

    private var currentObject: NodeId? = null
    private var seq: Long = 0
    private var direction: Direction = Direction.LR

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            return IrPatchBatch(seq, emptyList())
        }
        currentObject?.let { current ->
            if (trimmed == "}") {
                currentObject = null
                return IrPatchBatch(seq, emptyList())
            }
            return parseMemberInto(current, trimmed)
        }

        return when {
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
            trimmed.startsWith("object ", ignoreCase = true) -> parseObjectDecl(trimmed)
            findRelationOperator(trimmed) != null -> parseRelation(trimmed)
            isDottedMember(trimmed) -> parseDottedMember(trimmed)
            else -> errorBatch("Unsupported PlantUML object statement: $trimmed")
        }
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
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
        sourceLanguage = SourceLanguage.PLANTUML,
        styleHints = StyleHints(direction = direction),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseObjectDecl(line: String): IrPatchBatch {
        var body = line.removePrefix("object").trim()
        val opens = body.endsWith("{")
        if (opens) body = body.removeSuffix("{").trim()
        val spec = parseAliasSpec(body) ?: return errorBatch("Invalid PlantUML object declaration: $line")
        ensureNode(spec.id, spec.label)
        if (opens) currentObject = NodeId(spec.id)
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseDottedMember(line: String): IrPatchBatch {
        val idx = line.indexOf(':')
        if (idx <= 0 || idx >= line.lastIndex) return errorBatch("Invalid object property syntax: $line")
        val objectName = line.substring(0, idx).trim()
        val member = line.substring(idx + 1).trim()
        if (objectName.isEmpty() || member.isEmpty()) return errorBatch("Invalid object property syntax: $line")
        ensureNode(objectName, objectName)
        return parseMemberInto(NodeId(objectName), member)
    }

    private fun parseMemberInto(id: NodeId, line: String): IrPatchBatch {
        val member = line.removePrefix(":").removeSuffix(";").trim()
        if (member.isEmpty()) return errorBatch("Invalid empty object property")
        val existing = nodes[id] ?: Node(id = id, label = RichLabel.Plain(id.value), shape = NodeShape.Box, style = objectStyle())
        val members = existing.payload[MEMBERS_KEY].orEmpty().split('\n').filter { it.isNotEmpty() }.toMutableList()
        members += member
        nodes[id] = existing.copy(
            payload = existing.payload + mapOf(
                KIND_KEY to "object",
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
        ensureNode(fromRaw, fromRaw)
        ensureNode(toRaw, toRaw)
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

    private fun ensureNode(idText: String, label: String) {
        val id = NodeId(idText)
        val existing = nodes[id]
        val base = Node(
            id = id,
            label = RichLabel.Plain(label),
            shape = NodeShape.Box,
            style = objectStyle(),
            payload = mapOf(KIND_KEY to "object"),
        )
        nodes[id] = if (existing == null) base else existing.copy(
            label = if (label.isNotEmpty()) base.label else existing.label,
            payload = existing.payload + base.payload,
        )
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

    private fun objectStyle(): NodeStyle = NodeStyle(
        fill = ArgbColor(0xFFF3E5F5.toInt()),
        stroke = ArgbColor(0xFF6A1B9A.toInt()),
        strokeWidth = 1.5f,
        textColor = ArgbColor(0xFF4A148C.toInt()),
    )

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
}
