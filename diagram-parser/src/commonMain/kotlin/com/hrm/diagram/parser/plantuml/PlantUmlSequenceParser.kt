package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.FragmentKind
import com.hrm.diagram.core.ir.MessageKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.Participant
import com.hrm.diagram.core.ir.ParticipantKind
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SequenceFragment
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Line-driven streaming parser for a small but useful subset of PlantUML `@startuml` sequence
 * diagrams. The parser is intentionally append-only and lenient: it keeps every successfully
 * parsed participant/message and reports mismatches as diagnostics instead of throwing.
 */
class PlantUmlSequenceParser {
    companion object {
        const val REF_PREFIX = "__plantuml_sequence_ref__::"
        const val BOXES_KEY = "plantuml.sequence.boxes"
        const val DECORATIONS_KEY = "plantuml.sequence.decorations"
        val ARROWS = listOf("-->>", "<<--", "->>", "<<-", "-->", "<--", "->", "<-")
        private val DECORATED_ARROW = Regex("""([ox]?)(-->>|<<--|->>|<<-|-->|<--|->|<-)([ox]?)""", RegexOption.IGNORE_CASE)
    }

    private data class BoxBuilder(
        val title: String?,
        val color: String?,
        val participants: LinkedHashSet<NodeId> = LinkedHashSet(),
    )

    private data class ParsedArrow(
        val core: String,
        val index: Int,
        val prefixDecoration: String?,
        val suffixDecoration: String?,
    )

    private data class MessageDecoration(
        val tail: String? = null,
        val head: String? = null,
        val headStyle: String? = null,
    )

    private val participantOrder: LinkedHashMap<NodeId, Participant> = LinkedHashMap()
    private val messages: MutableList<com.hrm.diagram.core.ir.SequenceMessage> = ArrayList()
    private val fragments: MutableList<SequenceFragment> = ArrayList()
    private val fragmentStack: ArrayDeque<FragmentBuilder> = ArrayDeque()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val boxes: MutableList<BoxBuilder> = ArrayList()
    private val boxStack: ArrayDeque<BoxBuilder> = ArrayDeque()
    private val decorationsByMessageIndex: MutableMap<Int, MessageDecoration> = LinkedHashMap()

    private var seq: Long = 0L
    private var finalized: Boolean = false
    private var autonumberStart: Int? = null
    private var autonumberStep: Int = 1
    private var autonumberCurrent: Int = 1
    private var autonumberActive: Boolean = false
    private var pendingCreate: NodeId? = null
    private var pendingDestroy: NodeId? = null

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            return IrPatchBatch(seq, emptyList())
        }
        return parseStatement(trimmed)
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        if (finalized) return IrPatchBatch(seq, emptyList())
        finalized = true
        val out = ArrayList<IrPatch>()
        if (!blockClosed) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Missing '@enduml' terminator",
                    code = "PLANTUML-E001",
                ),
            )
        }
        while (fragmentStack.isNotEmpty()) {
            fragments += fragmentStack.removeLast().build()
        }
        return IrPatchBatch(seq, out)
    }

    fun snapshot(): SequenceIR {
        val extras = HashMap<String, String>()
        autonumberStart?.let { extras["plantuml.autonumber"] = "$it,$autonumberStep" }
        if (boxes.isNotEmpty()) {
            extras[BOXES_KEY] = boxes.joinToString("||") { box ->
                listOf(
                    box.title.orEmpty().replace("|", "\\|"),
                    box.color.orEmpty(),
                    box.participants.joinToString(",") { it.value },
                ).joinToString("|")
            }
        }
        if (decorationsByMessageIndex.isNotEmpty()) {
            extras[DECORATIONS_KEY] = decorationsByMessageIndex.entries.joinToString("||") { (index, decoration) ->
                listOf(
                    index.toString(),
                    decoration.tail.orEmpty(),
                    decoration.head.orEmpty(),
                    decoration.headStyle.orEmpty(),
                ).joinToString("|")
            }
        }
        return SequenceIR(
            participants = participantOrder.values.toList(),
            messages = messages.toList(),
            fragments = fragments.toList(),
            sourceLanguage = SourceLanguage.PLANTUML,
            styleHints = StyleHints(extras = extras),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseStatement(line: String): IrPatchBatch {
        val lower = line.lowercase()
        return when {
            lower.startsWith("participant ") -> parseParticipant(line, ParticipantKind.Participant, "participant")
            lower.startsWith("actor ") -> parseParticipant(line, ParticipantKind.Actor, "actor")
            lower.startsWith("boundary ") -> parseParticipant(line, ParticipantKind.Boundary, "boundary")
            lower.startsWith("control ") -> parseParticipant(line, ParticipantKind.Control, "control")
            lower.startsWith("entity ") -> parseParticipant(line, ParticipantKind.Entity, "entity")
            lower.startsWith("database ") -> parseParticipant(line, ParticipantKind.Database, "database")
            lower.startsWith("collections ") -> parseParticipant(line, ParticipantKind.Collections, "collections")
            lower.startsWith("queue ") -> parseParticipant(line, ParticipantKind.Queue, "queue")
            lower.startsWith("note ") -> parseNote(line)
            lower.startsWith("ref ") -> parseRef(line)
            lower.startsWith("activate ") -> parseActivate(line, activate = true)
            lower.startsWith("deactivate ") -> parseActivate(line, activate = false)
            lower == "autonumber" || lower.startsWith("autonumber ") -> parseAutonumber(line)
            lower == "newpage" -> IrPatchBatch(seq, emptyList())
            lower.startsWith("create ") -> parseCreateDestroy(line, create = true)
            lower.startsWith("destroy ") -> parseCreateDestroy(line, create = false)
            lower.startsWith("box") -> parseBoxStart(line)
            lower == "end box" || lower == "endbox" -> parseBoxEnd()
            lower == "end" -> popFragment()
            lower == "else" || lower.startsWith("else ") -> addBranch()
            lower == "and" || lower.startsWith("and ") -> addBranch()
            lower == "option" || lower.startsWith("option ") -> addBranch()
            lower.startsWith("loop ") || lower == "loop" -> pushFragment(FragmentKind.Loop, tailAfterKeyword(line))
            lower.startsWith("alt ") || lower == "alt" -> pushFragment(FragmentKind.Alt, tailAfterKeyword(line))
            lower.startsWith("opt ") || lower == "opt" -> pushFragment(FragmentKind.Opt, tailAfterKeyword(line))
            lower.startsWith("par ") || lower == "par" -> pushFragment(FragmentKind.Par, tailAfterKeyword(line))
            lower.startsWith("critical ") || lower == "critical" -> pushFragment(FragmentKind.Critical, tailAfterKeyword(line))
            lower.startsWith("break ") || lower == "break" -> pushFragment(FragmentKind.Break, tailAfterKeyword(line))
            lower.startsWith("group ") || lower == "group" -> pushFragment(FragmentKind.Group, tailAfterKeyword(line))
            lower == "return" || lower.startsWith("return ") -> parseReturn(line)
            findArrow(line) != null -> parseMessage(line)
            else -> errorBatch("Unsupported PlantUML sequence statement: $line")
        }
    }

    private fun parseParticipant(
        line: String,
        kind: ParticipantKind,
        keyword: String,
    ): IrPatchBatch {
        val body = line.substring(keyword.length).trim()
        if (body.isEmpty()) return errorBatch("Expected participant identifier after '$keyword'")

        val aliasQuoted = Regex("^\"([^\"]+)\"\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (aliasQuoted != null) {
            return upsertParticipant(
                id = NodeId(aliasQuoted.groupValues[2]),
                label = RichLabel.Plain(aliasQuoted.groupValues[1]),
                kind = kind,
            )
        }

        val identQuoted = Regex("^([A-Za-z0-9_.:-]+)\\s+as\\s+\"([^\"]+)\"$").matchEntire(body)
        if (identQuoted != null) {
            return upsertParticipant(
                id = NodeId(identQuoted.groupValues[1]),
                label = RichLabel.Plain(identQuoted.groupValues[2]),
                kind = kind,
            )
        }

        val simple = Regex("^([A-Za-z0-9_.:-]+)$").matchEntire(body)
        if (simple != null) {
            return upsertParticipant(id = NodeId(simple.groupValues[1]), label = RichLabel.Empty, kind = kind)
        }

        return errorBatch("Invalid $keyword declaration: $line")
    }

    private fun upsertParticipant(id: NodeId, label: RichLabel, kind: ParticipantKind): IrPatchBatch {
        val existing = participantOrder[id]
        participantOrder[id] = Participant(
            id = id,
            label = if (!label.isEmpty) label else existing?.label ?: RichLabel.Empty,
            kind = kind,
        )
        boxStack.lastOrNull()?.participants?.add(id)
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseNote(line: String): IrPatchBatch {
        val side = Regex("^note\\s+(left|right)\\s+of\\s+([A-Za-z0-9_.:-]+)(?:\\s*:\\s*(.*))?$", RegexOption.IGNORE_CASE)
            .matchEntire(line)
        if (side != null) {
            val id = NodeId(side.groupValues[2])
            ensureParticipant(id)
            addMessage(
                com.hrm.diagram.core.ir.SequenceMessage(
                    from = id,
                    to = id,
                    kind = MessageKind.Note,
                    label = side.groupValues[3].toRichLabel(),
                ),
            )
            return IrPatchBatch(seq, emptyList())
        }

        val over = Regex(
            "^note\\s+over\\s+([A-Za-z0-9_.:-]+)(?:\\s*,\\s*([A-Za-z0-9_.:-]+))?(?:\\s*:\\s*(.*))?$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (over != null) {
            val from = NodeId(over.groupValues[1])
            val to = over.groupValues[2].takeIf { it.isNotEmpty() }?.let(::NodeId) ?: from
            ensureParticipant(from)
            ensureParticipant(to)
            addMessage(
                com.hrm.diagram.core.ir.SequenceMessage(
                    from = from,
                    to = to,
                    kind = MessageKind.Note,
                    label = over.groupValues[3].toRichLabel(),
                ),
            )
            return IrPatchBatch(seq, emptyList())
        }

        return errorBatch("Invalid note statement: $line")
    }

    private fun parseRef(line: String): IrPatchBatch {
        val over = Regex(
            "^ref\\s+over\\s+([A-Za-z0-9_.:-]+)(?:\\s*,\\s*([A-Za-z0-9_.:-]+))?(?:\\s*:\\s*(.*))?$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line) ?: return errorBatch("Invalid ref statement: $line")
        val from = NodeId(over.groupValues[1])
        val to = over.groupValues[2].takeIf { it.isNotEmpty() }?.let(::NodeId) ?: from
        val label = over.groupValues[3].trim()
        ensureParticipant(from)
        ensureParticipant(to)
        addMessage(
            com.hrm.diagram.core.ir.SequenceMessage(
                from = from,
                to = to,
                kind = MessageKind.Note,
                label = RichLabel.Plain("$REF_PREFIX$label"),
            ),
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseActivate(line: String, activate: Boolean): IrPatchBatch {
        val id = line.substringAfter(' ').trim()
        if (id.isEmpty()) return errorBatch("Expected identifier after ${if (activate) "activate" else "deactivate"}")
        val nodeId = NodeId(id)
        ensureParticipant(nodeId)
        addMessage(
            com.hrm.diagram.core.ir.SequenceMessage(
                from = nodeId,
                to = nodeId,
                kind = MessageKind.Note,
                activate = activate,
                deactivate = !activate,
            ),
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseAutonumber(line: String): IrPatchBatch {
        val parts = line.split(Regex("\\s+")).drop(1)
        if (parts.firstOrNull()?.equals("stop", ignoreCase = true) == true) {
            autonumberActive = false
            return IrPatchBatch(seq, emptyList())
        }
        if (parts.firstOrNull()?.equals("resume", ignoreCase = true) == true) {
            autonumberActive = true
            parts.getOrNull(1)?.toIntOrNull()?.let {
                autonumberCurrent = it
                autonumberStart = it
            }
            parts.getOrNull(2)?.toIntOrNull()?.let { autonumberStep = it }
            return IrPatchBatch(seq, emptyList())
        }
        autonumberStart = 1
        autonumberStep = 1
        parts.getOrNull(0)?.toIntOrNull()?.let { autonumberStart = it }
        parts.getOrNull(1)?.toIntOrNull()?.let { autonumberStep = it }
        autonumberCurrent = autonumberStart ?: 1
        autonumberActive = true
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseCreateDestroy(line: String, create: Boolean): IrPatchBatch {
        val idText = line.substringAfter(' ').trim()
        if (idText.isEmpty()) return errorBatch("Expected identifier after ${if (create) "create" else "destroy"}")
        val id = NodeId(idText)
        ensureParticipant(id)
        if (create) {
            pendingCreate = id
        } else {
            pendingDestroy = id
        }
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseBoxStart(line: String): IrPatchBatch {
        val body = line.substringAfter("box", "").trim()
        val colorMatch = Regex("(#[A-Za-z0-9]{3,8}|[A-Za-z]+)$").find(body)
        val color = colorMatch?.value
        val titleRaw = if (colorMatch != null) body.removeSuffix(colorMatch.value).trim() else body
        val title = titleRaw.removePrefix("\"").removeSuffix("\"").trim().ifEmpty { null }
        boxStack.addLast(BoxBuilder(title = title, color = color))
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseBoxEnd(): IrPatchBatch {
        val box = boxStack.removeLastOrNull() ?: return errorBatch("'end box' without matching 'box'")
        boxes += box
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseReturn(line: String): IrPatchBatch {
        val previous = messages.lastOrNull { it.kind != MessageKind.Note }
            ?: return errorBatch("'return' requires a preceding message")
        val label = line.substringAfter("return", "").trim().toRichLabel()
        addMessage(
            com.hrm.diagram.core.ir.SequenceMessage(
                from = previous.to,
                to = previous.from,
                kind = MessageKind.Reply,
                label = label,
            ),
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseMessage(line: String): IrPatchBatch {
        val arrow = findArrow(line) ?: return errorBatch("Invalid PlantUML sequence arrow: $line")
        val arrowIndex = arrow.index
        if (arrowIndex <= 0) return errorBatch("Missing message source in: $line")

        val rawLeft = line.substring(0, arrowIndex).trim()
        val rawTail = line.substring(arrowIndex + arrow.core.length + (arrow.prefixDecoration?.length ?: 0) + (arrow.suffixDecoration?.length ?: 0)).trim()
        val colonIndex = rawTail.indexOf(':')
        val rawTarget = if (colonIndex >= 0) rawTail.substring(0, colonIndex).trim() else rawTail
        val label = if (colonIndex >= 0) rawTail.substring(colonIndex + 1).trim().toRichLabel() else RichLabel.Empty

        if (rawLeft.isEmpty() || rawTarget.isEmpty()) return errorBatch("Invalid message statement: $line")

        var activate = false
        var deactivate = false
        var targetText = rawTarget
        if (targetText.startsWith("+")) {
            activate = true
            targetText = targetText.removePrefix("+").trim()
        } else if (targetText.startsWith("-")) {
            deactivate = true
            targetText = targetText.removePrefix("-").trim()
        }
        if (targetText.isEmpty()) return errorBatch("Missing message target in: $line")

        val reversed = arrow.core.startsWith("<")
        val from = if (reversed) NodeId(targetText) else NodeId(rawLeft)
        val to = if (reversed) NodeId(rawLeft) else NodeId(targetText)
        ensureParticipant(from)
        ensureParticipant(to)
        val explicitCreate = pendingCreate?.let { it == to } == true
        val explicitDestroy = pendingDestroy?.let { it == to } == true
        if (explicitCreate) pendingCreate = null
        if (explicitDestroy) pendingDestroy = null
        val kind = when {
            explicitCreate -> MessageKind.Create
            explicitDestroy -> MessageKind.Destroy
            else -> arrowKindFor(arrow.core)
        }
        addMessage(
            com.hrm.diagram.core.ir.SequenceMessage(
                from = from,
                to = to,
                label = applyAutonumber(label),
                kind = kind,
                activate = activate,
                deactivate = deactivate,
            ),
        )
        decorationFor(arrow, reversed, kind)?.let { decorationsByMessageIndex[messages.lastIndex] = it }
        return IrPatchBatch(seq, emptyList())
    }

    private fun arrowKindFor(arrow: String): MessageKind = when (arrow) {
        "->>", "<<-" -> MessageKind.Async
        "-->", "<--", "-->>", "<<--" -> MessageKind.Reply
        else -> MessageKind.Sync
    }

    private fun pushFragment(kind: FragmentKind, title: String?): IrPatchBatch {
        fragmentStack.addLast(FragmentBuilder(kind = kind, title = title))
        return IrPatchBatch(seq, emptyList())
    }

    private fun addBranch(): IrPatchBatch {
        val top = fragmentStack.lastOrNull() ?: return errorBatch("'else'/'and'/'option' outside fragment")
        top.newBranch()
        return IrPatchBatch(seq, emptyList())
    }

    private fun popFragment(): IrPatchBatch {
        val top = fragmentStack.removeLastOrNull() ?: return errorBatch("'end' without matching fragment")
        fragments += top.build()
        return IrPatchBatch(seq, emptyList())
    }

    private fun ensureParticipant(id: NodeId) {
        if (id !in participantOrder) {
            participantOrder[id] = Participant(id = id)
        }
        boxStack.lastOrNull()?.participants?.add(id)
    }

    private fun addMessage(message: com.hrm.diagram.core.ir.SequenceMessage) {
        messages += message
        fragmentStack.lastOrNull()?.currentBranch?.add(message)
    }

    private fun applyAutonumber(label: RichLabel): RichLabel {
        if (!autonumberActive) return label
        val prefix = autonumberCurrent.toString()
        autonumberCurrent += autonumberStep
        val text = (label as? RichLabel.Plain)?.text.orEmpty()
        return RichLabel.Plain(if (text.isEmpty()) prefix else "$prefix $text")
    }

    private fun errorBatch(message: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.ERROR, message, "PLANTUML-E002"))))

    private fun addDiagnostic(diagnostic: Diagnostic): IrPatch {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }

    private fun tailAfterKeyword(line: String): String? =
        line.substringAfter(' ', "").trim().ifEmpty { null }

    private fun String.toRichLabel(): RichLabel =
        if (isEmpty()) RichLabel.Empty else RichLabel.Plain(this)

    private fun findArrow(line: String): ParsedArrow? =
        DECORATED_ARROW.find(line)?.let { match ->
            ParsedArrow(
                core = match.groupValues[2],
                index = match.range.first,
                prefixDecoration = match.groupValues[1].ifEmpty { null }?.lowercase(),
                suffixDecoration = match.groupValues[3].ifEmpty { null }?.lowercase(),
            )
        }

    private fun decorationFor(arrow: ParsedArrow, reversed: Boolean, kind: MessageKind): MessageDecoration? {
        val tail = if (reversed) arrow.suffixDecoration else arrow.prefixDecoration
        val head = if (reversed) arrow.prefixDecoration else arrow.suffixDecoration
        val headStyle = when {
            head != null || kind == MessageKind.Destroy -> null
            arrow.core == "-->>" || arrow.core == "<<--" -> "open"
            kind == MessageKind.Async -> "open"
            kind == MessageKind.Sync || kind == MessageKind.Reply || kind == MessageKind.Create -> "filled"
            else -> null
        }
        return if (tail == null && head == null && headStyle == null) {
            null
        } else {
            MessageDecoration(tail = tail, head = head, headStyle = headStyle)
        }
    }

    private class FragmentBuilder(
        val kind: FragmentKind,
        val title: String?,
    ) {
        val branches: MutableList<MutableList<com.hrm.diagram.core.ir.SequenceMessage>> = mutableListOf(mutableListOf())
        val currentBranch: MutableList<com.hrm.diagram.core.ir.SequenceMessage> get() = branches.last()
        fun newBranch() {
            branches.add(mutableListOf())
        }

        fun build(): SequenceFragment = SequenceFragment(
            kind = kind,
            title = title?.toRichLabel(),
            branches = branches.map { it.toList() },
        )

        private fun String.toRichLabel(): RichLabel = RichLabel.Plain(this)
    }
}
