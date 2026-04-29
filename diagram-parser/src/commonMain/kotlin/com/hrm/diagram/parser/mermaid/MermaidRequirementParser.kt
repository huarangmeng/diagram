package com.hrm.diagram.parser.mermaid

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
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for Mermaid `requirementDiagram`.
 *
 * Supported subset:
 * - `requirementDiagram`
 * - `direction TB|BT|LR|RL`
 * - requirement blocks:
 *   `requirement|functionalRequirement|interfaceRequirement|performanceRequirement|physicalRequirement|designConstraint name { ... }`
 * - element blocks:
 *   `element name { type: ..., docRef: ... }`
 * - relationships:
 *   `a - satisfies -> b`
 *   `b <- copies - a`
 *
 * Styling (`classDef` / `class` / `style` / `:::`) is handled in the session preprocessor, same
 * as other GraphIR-based Mermaid diagrams.
 */
class MermaidRequirementParser {
    companion object {
        const val REQUIREMENT_KIND_KEY = "mermaid.requirement.kind"
        const val REQUIREMENT_TYPE_KEY = "mermaid.requirement.type"
        const val REQUIREMENT_RISK_KEY = "mermaid.requirement.risk"
        const val REQUIREMENT_VERIFY_KEY = "mermaid.requirement.verify"
        const val REQUIREMENT_ELEMENT_TYPE_KEY = "mermaid.requirement.elementType"
        const val REQUIREMENT_DOCREF_KEY = "mermaid.requirement.docRef"
    }

    private val knownNodes: LinkedHashMap<NodeId, Node> = LinkedHashMap()
    private val edges: MutableList<Edge> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val pendingRelations: MutableList<ParsedRelation> = ArrayList()
    private var seq: Long = 0
    private var headerSeen = false
    private var direction: Direction? = null

    private data class OpenBlock(
        val kind: String,
        val id: NodeId,
        val displayName: String,
        val properties: LinkedHashMap<String, String> = LinkedHashMap(),
    )

    private data class ParsedRelation(
        val from: NodeId,
        val to: NodeId,
        val relationType: String,
    )

    private var openBlock: OpenBlock? = null

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())
        val lexErr = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (lexErr != null) return errorBatch("Lex error at ${lexErr.start}: ${lexErr.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.REQUIREMENT_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'requirementDiagram' header")
        }

        val lineText = toks.joinToString(" ") { it.text.toString() }.trim()
        if (lineText.isBlank()) return IrPatchBatch(seq, emptyList())

        val block = openBlock
        if (block != null) {
            if (lineText == "}") {
                val patches = ArrayList<IrPatch>()
                registerNode(block, patches)
                flushPendingRelations(patches)
                openBlock = null
                return IrPatchBatch(seq, patches)
            }
            val property = parseProperty(lineText)
            if (property == null) {
                return errorBatch("Invalid requirementDiagram property line")
            }
            block.properties[property.first.lowercase()] = property.second
            return IrPatchBatch(seq, emptyList())
        }

        if (lineText.startsWith("direction ")) {
            direction = parseDirection(lineText.removePrefix("direction ").trim())
            if (direction == null) {
                return errorBatch("Invalid requirementDiagram direction")
            }
            return IrPatchBatch(seq, emptyList())
        }

        val open = parseOpenBlock(lineText)
        if (open != null) {
            openBlock = open
            return IrPatchBatch(seq, emptyList())
        }

        val relation = parseRelation(lineText)
            ?: return errorBatch("Invalid requirementDiagram relationship line")
        val patches = ArrayList<IrPatch>()
        if (relation.from in knownNodes && relation.to in knownNodes) {
            registerRelation(relation, patches)
        } else {
            pendingRelations += relation
        }
        return IrPatchBatch(seq, patches)
    }

    fun snapshot(): GraphIR = GraphIR(
        nodes = knownNodes.values.toList(),
        edges = edges.toList(),
        sourceLanguage = SourceLanguage.MERMAID,
        styleHints = StyleHints(direction = direction),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseOpenBlock(line: String): OpenBlock? {
        if (!line.endsWith("{")) return null
        val body = line.removeSuffix("{").trimEnd()
        val firstSpace = body.indexOfFirst { it.isWhitespace() }
        if (firstSpace <= 0) return null
        val kind = body.substring(0, firstSpace).trim()
        if (kind !in supportedKinds) return null
        val name = body.substring(firstSpace + 1).trim()
        if (name.isBlank()) return null
        val normalizedName = parseName(name) ?: return null
        return OpenBlock(
            kind = kind,
            id = NodeId(normalizedName),
            displayName = normalizedName,
        )
    }

    private fun parseProperty(line: String): Pair<String, String>? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val key = line.substring(0, colon).trim()
        val value = stripQuotes(line.substring(colon + 1).trim())
        if (key.isBlank()) return null
        return key to value
    }

    private fun parseRelation(line: String): ParsedRelation? {
        val (firstName, afterFirst) = readName(line, 0) ?: return null
        var i = skipSpaces(line, afterFirst)
        if (i >= line.length) return null
        return if (line.startsWith("<-", i)) {
            i += 2
            i = skipSpaces(line, i)
            val relationType = readWord(line, i) ?: return null
            i = skipSpaces(line, relationType.second)
            if (i >= line.length || line[i] != '-') return null
            i++
            i = skipSpaces(line, i)
            val secondName = readName(line, i) ?: return null
            ParsedRelation(
                from = NodeId(secondName.first),
                to = NodeId(firstName),
                relationType = relationType.first,
            )
        } else {
            if (line[i] != '-') return null
            i++
            i = skipSpaces(line, i)
            val relationType = readWord(line, i) ?: return null
            i = skipSpaces(line, relationType.second)
            if (!line.startsWith("->", i)) return null
            i += 2
            i = skipSpaces(line, i)
            val secondName = readName(line, i) ?: return null
            ParsedRelation(
                from = NodeId(firstName),
                to = NodeId(secondName.first),
                relationType = relationType.first,
            )
        }
    }

    private fun registerNode(block: OpenBlock, out: MutableList<IrPatch>) {
        if (block.id in knownNodes) return
        val node = when (block.kind) {
            "element" -> buildElementNode(block)
            else -> buildRequirementNode(block)
        }
        knownNodes[block.id] = node
        out += IrPatch.AddNode(node)
    }

    private fun buildRequirementNode(block: OpenBlock): Node {
        val stereotype = requirementStereotype(block.kind)
        val idText = block.properties["id"].orEmpty()
        val text = block.properties["text"].orEmpty()
        val risk = titleCase(block.properties["risk"])
        val verify = titleCase(block.properties["verifymethod"])
        val lines = buildList {
            add("<<$stereotype>>")
            add(block.displayName)
            if (idText.isNotBlank()) add("ID: $idText")
            if (text.isNotBlank()) add("Text: $text")
            if (risk.isNotBlank()) add("Risk: $risk")
            if (verify.isNotBlank()) add("Verification: $verify")
        }
        return Node(
            id = block.id,
            label = RichLabel.Markdown(lines.joinToString("\n")),
            shape = NodeShape.Box,
            style = requirementStyle(block.kind, block.properties["risk"]),
            payload = buildMap {
                put(REQUIREMENT_KIND_KEY, "requirement")
                put(REQUIREMENT_TYPE_KEY, block.kind)
                if (block.properties["risk"]?.isNotBlank() == true) put(REQUIREMENT_RISK_KEY, block.properties["risk"].orEmpty())
                if (block.properties["verifymethod"]?.isNotBlank() == true) put(REQUIREMENT_VERIFY_KEY, block.properties["verifymethod"].orEmpty())
            },
        )
    }

    private fun buildElementNode(block: OpenBlock): Node {
        val type = block.properties["type"].orEmpty()
        val docRef = block.properties["docref"].orEmpty()
        val lines = buildList {
            add("<<Element>>")
            add(block.displayName)
            if (type.isNotBlank()) add("Type: $type")
            if (docRef.isNotBlank()) add("Doc Ref: $docRef")
        }
        return Node(
            id = block.id,
            label = RichLabel.Markdown(lines.joinToString("\n")),
            shape = NodeShape.RoundedBox,
            style = NodeStyle(
                fill = ArgbColor(0xFFF3E5F5.toInt()),
                stroke = ArgbColor(0xFF7B1FA2.toInt()),
                strokeWidth = 1.5f,
                textColor = ArgbColor(0xFF4A148C.toInt()),
            ),
            payload = buildMap {
                put(REQUIREMENT_KIND_KEY, "element")
                if (type.isNotBlank()) put(REQUIREMENT_ELEMENT_TYPE_KEY, type)
                if (docRef.isNotBlank()) put(REQUIREMENT_DOCREF_KEY, docRef)
            },
        )
    }

    private fun registerRelation(parsed: ParsedRelation, out: MutableList<IrPatch>) {
        val edge = Edge(
            from = parsed.from,
            to = parsed.to,
            label = RichLabel.Plain("<<${parsed.relationType}>>"),
            arrow = ArrowEnds.ToOnly,
            style = relationStyle(parsed.relationType),
        )
        if (edges.any { it.from == edge.from && it.to == edge.to && (it.label as? RichLabel.Plain)?.text == (edge.label as? RichLabel.Plain)?.text }) {
            return
        }
        edges += edge
        out += IrPatch.AddEdge(edge)
    }

    private fun flushPendingRelations(out: MutableList<IrPatch>) {
        val ready = pendingRelations.filter { it.from in knownNodes && it.to in knownNodes }
        if (ready.isEmpty()) return
        pendingRelations.removeAll(ready)
        for (relation in ready) registerRelation(relation, out)
    }

    private fun parseDirection(raw: String): Direction? =
        when (raw.uppercase()) {
            "TB", "TD" -> Direction.TB
            "BT" -> Direction.BT
            "LR" -> Direction.LR
            "RL" -> Direction.RL
            else -> null
        }

    private fun requirementStyle(kind: String, risk: String?): NodeStyle {
        val stroke = when (kind) {
            "functionalRequirement" -> 0xFF2E7D32.toInt()
            "interfaceRequirement" -> 0xFF1565C0.toInt()
            "performanceRequirement" -> 0xFFEF6C00.toInt()
            "physicalRequirement" -> 0xFF6A1B9A.toInt()
            "designConstraint" -> 0xFF6D4C41.toInt()
            else -> 0xFF37474F.toInt()
        }
        val fill = when (risk?.lowercase()) {
            "high" -> 0xFFFFEBEE.toInt()
            "medium" -> 0xFFFFF8E1.toInt()
            "low" -> 0xFFE8F5E9.toInt()
            else -> 0xFFE3F2FD.toInt()
        }
        return NodeStyle(
            fill = ArgbColor(fill),
            stroke = ArgbColor(stroke),
            strokeWidth = 1.5f,
            textColor = ArgbColor(0xFF263238.toInt()),
        )
    }

    private fun relationStyle(kind: String): EdgeStyle {
        val color = when (kind.lowercase()) {
            "contains" -> 0xFF455A64.toInt()
            "copies" -> 0xFF5D4037.toInt()
            "derives" -> 0xFF7B1FA2.toInt()
            "satisfies" -> 0xFF2E7D32.toInt()
            "verifies" -> 0xFF1565C0.toInt()
            "refines" -> 0xFFEF6C00.toInt()
            "traces" -> 0xFF6A1B9A.toInt()
            else -> 0xFF546E7A.toInt()
        }
        return EdgeStyle(color = ArgbColor(color), width = 1.5f, labelBg = ArgbColor(0xF0FFFFFF.toInt()))
    }

    private fun requirementStereotype(kind: String): String = when (kind) {
        "functionalRequirement" -> "Functional Requirement"
        "interfaceRequirement" -> "Interface Requirement"
        "performanceRequirement" -> "Performance Requirement"
        "physicalRequirement" -> "Physical Requirement"
        "designConstraint" -> "Design Constraint"
        else -> "Requirement"
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MERMAID-E211")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }

    private fun parseName(raw: String): String? = readName(raw, 0)?.first

    private fun readName(text: String, start: Int): Pair<String, Int>? {
        var i = skipSpaces(text, start)
        if (i >= text.length) return null
        return if (text[i] == '"' || text[i] == '\'') {
            val quote = text[i++]
            val nameStart = i
            while (i < text.length && text[i] != quote) i++
            if (i >= text.length) return null
            stripQuotes(text.substring(nameStart - 1, i + 1)) to (i + 1)
        } else {
            val nameStart = i
            while (i < text.length && !text[i].isWhitespace() && text[i] != '{') i++
            if (i <= nameStart) return null
            text.substring(nameStart, i).trim() to i
        }
    }

    private fun readWord(text: String, start: Int): Pair<String, Int>? {
        var i = start
        val wordStart = i
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        if (i <= wordStart) return null
        return text.substring(wordStart, i) to i
    }

    private fun skipSpaces(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun stripQuotes(raw: String): String =
        raw.removeSurrounding("\"").removeSurrounding("'")

    private fun titleCase(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        return s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private val supportedKinds = setOf(
        "requirement",
        "functionalRequirement",
        "interfaceRequirement",
        "performanceRequirement",
        "physicalRequirement",
        "designConstraint",
        "element",
    )
}
