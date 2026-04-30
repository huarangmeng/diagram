package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.ClassMember
import com.hrm.diagram.core.ir.ClassNode
import com.hrm.diagram.core.ir.ClassNamespace
import com.hrm.diagram.core.ir.ClassParam
import com.hrm.diagram.core.ir.ClassRelation
import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.ClassNote
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NotePlacement
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.ir.Visibility
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Line-driven PlantUML `class` parser for the Phase-4 MVP.
 *
 * Supported in this slice:
 * - `class` / `abstract class` / `interface` / `enum`
 * - quoted alias form like `class "User Service" as UserService`
 * - `package Foo { ... }`
 * - multiline member blocks
 * - dotted member form: `Foo : +bar(): Int`
 * - relations: `<|--`, `--|>`, `<|..`, `..|>`, `*--`, `o--`, `-->`, `<--`, `..>`, `<..`, `--`, `..`
 * - `note (left|right|top|bottom) of Foo : text`
 * - multiline note blocks terminated by `end note`
 */
@DiagramApi
class PlantUmlClassParser {
    private data class AliasSpec(
        val id: String,
        val label: String,
        val generics: String? = null,
    )

    private data class NamespaceDef(
        val id: String,
        val title: String,
    )

    private data class PendingNote(
        val targetClass: NodeId?,
        val placement: NotePlacement,
        val lines: MutableList<String> = ArrayList(),
    )

    private val classOrder: LinkedHashMap<NodeId, ClassNode> = LinkedHashMap()
    private val relations: MutableList<ClassRelation> = ArrayList()
    private val notes: MutableList<ClassNote> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val namespaces: LinkedHashMap<String, NamespaceDef> = LinkedHashMap()
    private val namespaceMembers: LinkedHashMap<String, LinkedHashSet<NodeId>> = LinkedHashMap()
    private val namespaceStack: ArrayDeque<String> = ArrayDeque()

    private var seq: Long = 0L
    private var currentBodyClass: NodeId? = null
    private var pendingNote: PendingNote? = null

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            return IrPatchBatch(seq, emptyList())
        }

        pendingNote?.let { note ->
            if (trimmed.equals("end note", ignoreCase = true)) {
                notes += ClassNote(
                    text = RichLabel.Plain(note.lines.joinToString("\n").trim()),
                    targetClass = note.targetClass,
                    placement = note.placement,
                )
                pendingNote = null
                return IrPatchBatch(seq, emptyList())
            }
            note.lines += trimmed
            return IrPatchBatch(seq, emptyList())
        }

        val current = currentBodyClass
        if (current != null) {
            if (trimmed == "}") {
                currentBodyClass = null
                return IrPatchBatch(seq, emptyList())
            }
            return parseMemberInto(current, trimmed)
        }

        return when {
            trimmed.startsWith("abstract class ", ignoreCase = true) -> parseClassDecl(trimmed, "abstract class", "abstract")
            trimmed.startsWith("class ", ignoreCase = true) -> parseClassDecl(trimmed, "class", null)
            trimmed.startsWith("interface ", ignoreCase = true) -> parseClassDecl(trimmed, "interface", "interface")
            trimmed.startsWith("enum ", ignoreCase = true) -> parseClassDecl(trimmed, "enum", "enum")
            trimmed.startsWith("package ", ignoreCase = true) -> parsePackageDecl(trimmed)
            trimmed == "}" -> closeNamespace()
            trimmed.startsWith("note ", ignoreCase = true) -> parseNote(trimmed)
            isDottedMember(trimmed) -> parseDottedMember(trimmed)
            findRelationOperator(trimmed) != null -> parseRelation(trimmed)
            else -> errorBatch("Unsupported PlantUML class statement: $trimmed")
        }
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (pendingNote != null) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed class note block before end of PlantUML block",
                    code = "PLANTUML-E003",
                ),
            )
            pendingNote = null
        }
        if (currentBodyClass != null) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed class body before end of PlantUML block",
                    code = "PLANTUML-E003",
                ),
            )
            currentBodyClass = null
        }
        if (namespaceStack.isNotEmpty()) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed class package body before end of PlantUML block",
                    code = "PLANTUML-E003",
                ),
            )
            namespaceStack.clear()
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

    fun snapshot(): ClassIR = ClassIR(
        classes = classOrder.values.toList(),
        relations = relations.toList(),
        namespaces = namespaceMembers.entries.map { (id, members) ->
            val title = namespaces[id]?.title ?: id
            ClassNamespace(
                id = title,
                members = members.toList(),
            )
        },
        notes = notes.toList(),
        sourceLanguage = SourceLanguage.PLANTUML,
        styleHints = StyleHints(),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseClassDecl(line: String, keyword: String, stereotype: String?): IrPatchBatch {
        var body = line.substring(keyword.length).trim()
        val opensBody = body.endsWith("{")
        if (opensBody) {
            body = body.removeSuffix("{").trim()
        }
        if (body.isEmpty()) return errorBatch("Expected identifier after '$keyword'")

        val spec = parseAliasSpec(body) ?: return errorBatch("Invalid class declaration: $line")
        val id = NodeId(spec.id)
        ensureClass(id, name = spec.label, generics = spec.generics, stereotype = stereotype)
        if (opensBody) currentBodyClass = id
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseRelation(line: String): IrPatchBatch {
        val op = findRelationOperator(line) ?: return errorBatch("Invalid class relation: $line")
        val parts = line.split(":", limit = 2)
        val relationPart = parts[0].trim()
        val label = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let(RichLabel::Plain) ?: RichLabel.Empty

        val pattern = Regex(
            "^([A-Za-z0-9_.:-]+)\\s*(?:\"([^\"]+)\")?\\s*" +
                Regex.escape(op) +
                "\\s*(?:\"([^\"]+)\")?\\s*([A-Za-z0-9_.:-]+)$",
        )
        val match = pattern.matchEntire(relationPart) ?: return errorBatch("Invalid class relation syntax: $line")
        val left = NodeId(match.groupValues[1])
        val leftCard = match.groupValues[2].ifEmpty { null }
        val rightCard = match.groupValues[3].ifEmpty { null }
        val right = NodeId(match.groupValues[4])
        val info = relationInfo(op)
        val from = if (info.reverseDirection) right else left
        val to = if (info.reverseDirection) left else right
        val fromCard = if (info.reverseDirection) rightCard else leftCard
        val toCard = if (info.reverseDirection) leftCard else rightCard
        ensureClass(from, from.value)
        ensureClass(to, to.value)
        relations += ClassRelation(
            from = from,
            to = to,
            kind = info.kind,
            fromCardinality = fromCard,
            toCardinality = toCard,
            label = label,
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseNote(line: String): IrPatchBatch {
        val inlineAnchored = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*:\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (inlineAnchored != null) {
            val placement = parsePlacement(inlineAnchored.groupValues[1])
            val target = NodeId(inlineAnchored.groupValues[2])
            ensureClass(target, target.value)
            notes += ClassNote(
                text = RichLabel.Plain(inlineAnchored.groupValues[3].trim()),
                targetClass = target,
                placement = placement,
            )
            return IrPatchBatch(seq, emptyList())
        }

        val standaloneQuoted = Regex("^note\\s+\"([^\"]+)\"$", RegexOption.IGNORE_CASE).matchEntire(line)
        if (standaloneQuoted != null) {
            notes += ClassNote(
                text = RichLabel.Plain(standaloneQuoted.groupValues[1]),
                targetClass = null,
                placement = NotePlacement.Standalone,
            )
            return IrPatchBatch(seq, emptyList())
        }

        val blockAnchored = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (blockAnchored != null) {
            val placement = parsePlacement(blockAnchored.groupValues[1])
            val target = NodeId(blockAnchored.groupValues[2])
            ensureClass(target, target.value)
            pendingNote = PendingNote(
                targetClass = target,
                placement = placement,
            )
            return IrPatchBatch(seq, emptyList())
        }

        if (line.equals("note", ignoreCase = true)) {
            pendingNote = PendingNote(targetClass = null, placement = NotePlacement.Standalone)
            return IrPatchBatch(seq, emptyList())
        }

        return errorBatch("Invalid class note syntax: $line")
    }

    private fun parsePlacement(raw: String): NotePlacement = when (raw.lowercase()) {
            "left" -> NotePlacement.LeftOf
            "right" -> NotePlacement.RightOf
            "top" -> NotePlacement.TopOf
            else -> NotePlacement.BottomOf
        }

    private fun parseDottedMember(line: String): IrPatchBatch {
        val className = line.substringBefore(':').trim()
        val member = line.substringAfter(':').trim()
        if (className.isEmpty() || member.isEmpty()) return errorBatch("Invalid dotted member syntax: $line")
        val id = NodeId(className)
        ensureClass(id, id.value)
        return parseMemberInto(id, member)
    }

    private fun parseMemberInto(classId: NodeId, line: String): IrPatchBatch {
        var working = line.trim()
        var isStatic = false
        var isAbstract = false

        while (true) {
            when {
                working.startsWith("{static}", ignoreCase = true) -> {
                    isStatic = true
                    working = working.removePrefix("{static}").trim()
                }
                working.startsWith("{abstract}", ignoreCase = true) -> {
                    isAbstract = true
                    working = working.removePrefix("{abstract}").trim()
                }
                else -> break
            }
        }

        if (working.startsWith("<<") && working.endsWith(">>")) {
            val stereotype = working.removePrefix("<<").removeSuffix(">>").trim()
            updateClass(classId) { it.copy(stereotype = stereotype) }
            return IrPatchBatch(seq, emptyList())
        }

        if (working.isEmpty()) return IrPatchBatch(seq, emptyList())
        var visibility = Visibility.PACKAGE
        when (working.first()) {
            '+' -> {
                visibility = Visibility.PUBLIC
                working = working.drop(1).trim()
            }
            '-' -> {
                visibility = Visibility.PRIVATE
                working = working.drop(1).trim()
            }
            '#' -> {
                visibility = Visibility.PROTECTED
                working = working.drop(1).trim()
            }
            '~' -> {
                visibility = Visibility.PACKAGE
                working = working.drop(1).trim()
            }
        }
        if (working.isEmpty()) return errorBatch("Empty class member declaration")

        if (working.endsWith("{static}", ignoreCase = true)) {
            isStatic = true
            working = working.removeSuffix("{static}").trim()
        }
        if (working.endsWith("{abstract}", ignoreCase = true)) {
            isAbstract = true
            working = working.removeSuffix("{abstract}").trim()
        }

        val member = if ('(' in working && ')' in working) {
            val before = working.substringBefore('(').trim()
            val paramsText = working.substringAfter('(').substringBeforeLast(')').trim()
            val after = working.substringAfterLast(')').trim().removePrefix(":").trim()
            ClassMember(
                visibility = visibility,
                name = before.substringAfterLast(' ').trim(),
                type = after.ifEmpty { null },
                params = parseParams(paramsText),
                isMethod = true,
                isStatic = isStatic,
                isAbstract = isAbstract,
            )
        } else {
            val colonIndex = working.indexOf(':')
            val name: String
            val type: String?
            if (colonIndex >= 0) {
                name = working.substring(0, colonIndex).trim()
                type = working.substring(colonIndex + 1).trim().ifEmpty { null }
            } else {
                val pieces = working.split(Regex("\\s+"))
                if (pieces.size == 1) {
                    name = working
                    type = null
                } else {
                    type = pieces.dropLast(1).joinToString(" ").ifEmpty { null }
                    name = pieces.last()
                }
            }
            ClassMember(
                visibility = visibility,
                name = name,
                type = type,
                isMethod = false,
                isStatic = isStatic,
                isAbstract = isAbstract,
            )
        }
        addMember(classId, member)
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseParams(text: String): List<ClassParam> {
        if (text.isBlank()) return emptyList()
        return text.split(',').map { part ->
            val p = part.trim()
            val colon = p.indexOf(':')
            if (colon >= 0) {
                ClassParam(name = p.substring(0, colon).trim(), type = p.substring(colon + 1).trim())
            } else {
                val bits = p.split(Regex("\\s+"))
                if (bits.size == 1) ClassParam(name = p) else ClassParam(name = bits.last(), type = bits.dropLast(1).joinToString(" "))
            }
        }
    }

    private fun ensureClass(id: NodeId, name: String, generics: String? = null, stereotype: String? = null) {
        val existing = classOrder[id]
        classOrder[id] = if (existing == null) {
            ClassNode(id = id, name = name, generics = generics, stereotype = stereotype)
        } else {
            val mergedName = when {
                name.isBlank() -> existing.name
                name == id.value && existing.name != id.value -> existing.name
                else -> name
            }
            existing.copy(
                name = mergedName,
                generics = generics ?: existing.generics,
                stereotype = stereotype ?: existing.stereotype,
            )
        }
        for (namespaceId in namespaceStack) {
            namespaceMembers.getOrPut(namespaceId) { LinkedHashSet() } += id
        }
    }

    private fun updateClass(id: NodeId, transform: (ClassNode) -> ClassNode) {
        val existing = classOrder[id] ?: ClassNode(id = id, name = id.value)
        classOrder[id] = transform(existing)
    }

    private fun addMember(id: NodeId, member: ClassMember) {
        val existing = classOrder[id] ?: ClassNode(id = id, name = id.value)
        classOrder[id] = existing.copy(members = existing.members + member)
    }

    private fun parsePackageDecl(line: String): IrPatchBatch {
        var body = line.removePrefix("package").trim()
        val opens = body.endsWith("{")
        if (opens) body = body.removeSuffix("{").trim()
        val spec = parsePackageSpec(body) ?: return errorBatch("Invalid class package declaration: $line")
        namespaces[spec.id] = spec
        namespaceMembers.getOrPut(spec.id) { LinkedHashSet() }
        if (opens) namespaceStack.addLast(spec.id)
        return IrPatchBatch(seq, emptyList())
    }

    private fun closeNamespace(): IrPatchBatch {
        if (namespaceStack.isEmpty()) return errorBatch("Unmatched '}' in PlantUML class body")
        namespaceStack.removeLast()
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseAliasSpec(body: String): AliasSpec? {
        val quotedAs = Regex("^\"([^\"]+)\"\\s+as\\s+([A-Za-z0-9_.:-]+(?:<[^>]+>)?)$").matchEntire(body)
        if (quotedAs != null) {
            val idPart = quotedAs.groupValues[2]
            return AliasSpec(id = idPart.substringBefore('<'), label = quotedAs.groupValues[1], generics = parseGenerics(idPart))
        }
        val identAsQuoted = Regex("^([A-Za-z0-9_.:-]+(?:<[^>]+>)?)\\s+as\\s+\"([^\"]+)\"$").matchEntire(body)
        if (identAsQuoted != null) {
            val idPart = identAsQuoted.groupValues[1]
            return AliasSpec(id = idPart.substringBefore('<'), label = identAsQuoted.groupValues[2], generics = parseGenerics(idPart))
        }
        val simple = Regex("^([A-Za-z0-9_.:-]+(?:<[^>]+>)?)$").matchEntire(body)
        if (simple != null) {
            val idPart = simple.groupValues[1]
            return AliasSpec(id = idPart.substringBefore('<'), label = idPart.substringBefore('<'), generics = parseGenerics(idPart))
        }
        return null
    }

    private fun parsePackageSpec(body: String): NamespaceDef? {
        val quotedAs = Regex("^\"([^\"]+)\"\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (quotedAs != null) return NamespaceDef(id = quotedAs.groupValues[2], title = quotedAs.groupValues[1])
        val quoted = Regex("^\"([^\"]+)\"$").matchEntire(body)
        if (quoted != null) {
            val title = quoted.groupValues[1]
            return NamespaceDef(id = sanitizeId(title), title = title)
        }
        val simple = Regex("^([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (simple != null) {
            val id = simple.groupValues[1]
            return NamespaceDef(id = id, title = id)
        }
        return null
    }

    private fun parseGenerics(raw: String): String? =
        Regex("<([^>]+)>").find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun sanitizeId(value: String): String =
        value.map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.') ch else '_' }.joinToString("")

    private fun relationInfo(op: String): RelationInfo = when (op) {
        "<|--" -> RelationInfo(ClassRelationKind.Inheritance, reverseDirection = true)
        "--|>" -> RelationInfo(ClassRelationKind.Inheritance, reverseDirection = false)
        "<|.." -> RelationInfo(ClassRelationKind.Realization, reverseDirection = true)
        "..|>" -> RelationInfo(ClassRelationKind.Realization, reverseDirection = false)
        "*--" -> RelationInfo(ClassRelationKind.Composition, reverseDirection = true)
        "o--" -> RelationInfo(ClassRelationKind.Aggregation, reverseDirection = true)
        "-->" -> RelationInfo(ClassRelationKind.Association, reverseDirection = false)
        "<--" -> RelationInfo(ClassRelationKind.Association, reverseDirection = true)
        "..>" -> RelationInfo(ClassRelationKind.Dependency, reverseDirection = false)
        "<.." -> RelationInfo(ClassRelationKind.Dependency, reverseDirection = true)
        "--" -> RelationInfo(ClassRelationKind.Link, reverseDirection = false)
        ".." -> RelationInfo(ClassRelationKind.LinkDashed, reverseDirection = false)
        else -> RelationInfo(ClassRelationKind.Link, reverseDirection = false)
    }

    private fun isDottedMember(line: String): Boolean {
        val colon = line.indexOf(':')
        if (colon <= 0) return false
        val left = line.substring(0, colon).trim()
        return left.matches(Regex("[A-Za-z0-9_.:-]+")) && !line.contains("--") && !line.contains("..") && !line.contains("->")
    }

    private fun findRelationOperator(line: String): String? {
        for (candidate in RELATION_OPERATORS) {
            if (line.contains(candidate)) return candidate
        }
        return null
    }

    private fun errorBatch(message: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.ERROR, message, "PLANTUML-E003"))))

    private fun addDiagnostic(diagnostic: Diagnostic): IrPatch {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }

    private data class RelationInfo(
        val kind: ClassRelationKind,
        val reverseDirection: Boolean,
    )

    private companion object {
        val RELATION_OPERATORS = listOf("<|--", "--|>", "<|..", "..|>", "*--", "o--", "-->", "<--", "..>", "<..", "--", "..")
    }
}
