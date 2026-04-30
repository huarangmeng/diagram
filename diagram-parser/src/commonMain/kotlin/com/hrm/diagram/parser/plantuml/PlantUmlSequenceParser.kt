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
    private val participantOrder: LinkedHashMap<NodeId, Participant> = LinkedHashMap()
    private val messages: MutableList<com.hrm.diagram.core.ir.SequenceMessage> = ArrayList()
    private val fragments: MutableList<SequenceFragment> = ArrayList()
    private val fragmentStack: ArrayDeque<FragmentBuilder> = ArrayDeque()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()

    private var seq: Long = 0L
    private var finalized: Boolean = false
    private var autonumberStart: Int? = null
    private var autonumberStep: Int = 1

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
            lower.startsWith("activate ") -> parseActivate(line, activate = true)
            lower.startsWith("deactivate ") -> parseActivate(line, activate = false)
            lower == "autonumber" || lower.startsWith("autonumber ") -> parseAutonumber(line)
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
        autonumberStart = 1
        autonumberStep = 1
        parts.getOrNull(0)?.toIntOrNull()?.let { autonumberStart = it }
        parts.getOrNull(1)?.toIntOrNull()?.let { autonumberStep = it }
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
        val arrowIndex = line.indexOf(arrow)
        if (arrowIndex <= 0) return errorBatch("Missing message source in: $line")

        val rawLeft = line.substring(0, arrowIndex).trim()
        val rawTail = line.substring(arrowIndex + arrow.length).trim()
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

        val reversed = arrow.startsWith("<")
        val from = if (reversed) NodeId(targetText) else NodeId(rawLeft)
        val to = if (reversed) NodeId(rawLeft) else NodeId(targetText)
        ensureParticipant(from)
        ensureParticipant(to)
        addMessage(
            com.hrm.diagram.core.ir.SequenceMessage(
                from = from,
                to = to,
                label = label,
                kind = arrowKindFor(arrow),
                activate = activate,
                deactivate = deactivate,
            ),
        )
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
    }

    private fun addMessage(message: com.hrm.diagram.core.ir.SequenceMessage) {
        messages += message
        fragmentStack.lastOrNull()?.currentBranch?.add(message)
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

    private fun findArrow(line: String): String? {
        for (candidate in ARROWS) {
            if (line.contains(candidate)) return candidate
        }
        return null
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

    private companion object {
        val ARROWS = listOf("-->>", "<<--", "->>", "<<-", "-->", "<--", "->", "<-")
    }
}
