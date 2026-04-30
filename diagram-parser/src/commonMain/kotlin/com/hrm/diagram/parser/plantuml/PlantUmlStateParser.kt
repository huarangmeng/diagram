package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.DiagramApi
import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NotePlacement
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StateIR
import com.hrm.diagram.core.ir.StateKind
import com.hrm.diagram.core.ir.StateNode
import com.hrm.diagram.core.ir.StateNote
import com.hrm.diagram.core.ir.StateTransition
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch

/**
 * Streaming parser for the Phase-4 PlantUML `state` MVP.
 *
 * Example:
 * ```kotlin
 * val parser = PlantUmlStateParser()
 * parser.acceptLine("state Ready")
 * parser.acceptLine("Ready --> Busy : start")
 * val ir = parser.snapshot()
 * ```
 */
@DiagramApi
class PlantUmlStateParser {
    private val states: LinkedHashMap<NodeId, StateNode> = LinkedHashMap()
    private val transitions: MutableList<StateTransition> = ArrayList()
    private val notes: MutableList<StateNote> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private val compositeStack: ArrayDeque<NodeId> = ArrayDeque()

    private var seq: Long = 0
    private var initCounter: Int = 0
    private var finalCounter: Int = 0
    private var historyCounter: Int = 0
    private var deepHistoryCounter: Int = 0
    private var direction: Direction? = null

    fun acceptLine(line: String): IrPatchBatch {
        seq++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("'") || trimmed.startsWith("//")) {
            return IrPatchBatch(seq, emptyList())
        }

        if (trimmed == "}") {
            if (compositeStack.isEmpty()) return errorBatch("Unmatched '}' in PlantUML state body")
            compositeStack.removeLast()
            return IrPatchBatch(seq, emptyList())
        }
        if (trimmed == "--") {
            // PlantUML parallel region separator. Region-aware IR is not modeled yet, but the
            // line is accepted so a minimal subset keeps flowing instead of erroring.
            return IrPatchBatch(seq, emptyList())
        }

        return when {
            trimmed.startsWith("state ", ignoreCase = true) -> parseStateDecl(trimmed)
            trimmed.startsWith("note ", ignoreCase = true) -> parseNote(trimmed)
            trimmed.equals("left to right direction", ignoreCase = true) -> parseDirection(Direction.LR)
            trimmed.equals("right to left direction", ignoreCase = true) -> parseDirection(Direction.RL)
            trimmed.equals("top to bottom direction", ignoreCase = true) -> parseDirection(Direction.TB)
            trimmed.equals("bottom to top direction", ignoreCase = true) -> parseDirection(Direction.BT)
            trimmed.contains("-->") -> parseTransition(trimmed)
            else -> errorBatch("Unsupported PlantUML state statement: $trimmed")
        }
    }

    fun finish(blockClosed: Boolean): IrPatchBatch {
        val out = ArrayList<IrPatch>()
        if (compositeStack.isNotEmpty()) {
            out += addDiagnostic(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = "Unclosed composite state body before end of PlantUML block",
                    code = "PLANTUML-E004",
                ),
            )
            compositeStack.clear()
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

    fun snapshot(): StateIR = StateIR(
        states = states.values.toList(),
        transitions = transitions.toList(),
        notes = notes.toList(),
        sourceLanguage = SourceLanguage.PLANTUML,
        styleHints = StyleHints(direction = direction),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    private fun parseStateDecl(line: String): IrPatchBatch {
        var body = line.removePrefix("state").trim()
        val opensBody = body.endsWith("{")
        if (opensBody) body = body.removeSuffix("{").trim()
        if (body.isEmpty()) return errorBatch("Expected state identifier after 'state'")

        val stereotype = Regex("<<(.*?)>>").find(body)?.groupValues?.getOrNull(1)?.trim()
        if (stereotype != null) {
            body = body.replace(Regex("\\s*<<.*?>>\\s*"), " ").trim()
        }

        val descAs = Regex("^\"([^\"]+)\"\\s+as\\s+([A-Za-z0-9_.:-]+)$").matchEntire(body)
        val reverseAs = Regex("^([A-Za-z0-9_.:-]+)\\s+as\\s+\"([^\"]+)\"$").matchEntire(body)
        val shorthand = Regex("^([A-Za-z0-9_.:-]+)\\s*:\\s*(.+)$").matchEntire(body)

        val description: String?
        val name: String
        when {
            descAs != null -> {
                description = descAs.groupValues[1]
                name = descAs.groupValues[2]
            }
            reverseAs != null -> {
                name = reverseAs.groupValues[1]
                description = reverseAs.groupValues[2]
            }
            shorthand != null -> {
                name = shorthand.groupValues[1]
                description = shorthand.groupValues[2]
            }
            body.matches(Regex("[A-Za-z0-9_.:-]+")) -> {
                name = body
                description = null
            }
            else -> return errorBatch("Invalid PlantUML state declaration: $line")
        }

        val id = NodeId(name)
        val declaredKind = stereoToKind(stereotype)
        val effectiveKind = if (opensBody) StateKind.Composite else declaredKind ?: StateKind.Simple
        ensureState(id, name = name, description = description, kind = effectiveKind)
        if (opensBody) compositeStack.addLast(id)
        return IrPatchBatch(seq, emptyList())
    }

    private fun stereoToKind(stereo: String?): StateKind? = when (stereo?.lowercase()) {
        "choice" -> StateKind.Choice
        "fork" -> StateKind.Fork
        "join" -> StateKind.Join
        "history" -> StateKind.History
        "deep_history", "deephistory", "deep history" -> StateKind.DeepHistory
        else -> null
    }

    private fun parseTransition(line: String): IrPatchBatch {
        val parts = line.split(":", limit = 2)
        val relationPart = parts[0].trim()
        val labelText = parts.getOrNull(1)?.trim().orEmpty()

        val match = Regex("^(.*?)\\s*-->\\s*(.*?)$").matchEntire(relationPart)
            ?: return errorBatch("Invalid PlantUML state transition: $line")
        val left = match.groupValues[1].trim()
        val right = match.groupValues[2].trim()
        if (left.isEmpty() || right.isEmpty()) return errorBatch("State transition endpoints cannot be empty")

        val from = parseEndpoint(left, asTarget = false)
        val to = parseEndpoint(right, asTarget = true)
        if (from == null || to == null) return errorBatch("Expected state identifier or pseudo-state in transition: $line")

        if (from.kind == StateKind.Simple) ensureState(from.id, from.id.value)
        if (to.kind == StateKind.Simple) ensureState(to.id, to.id.value)
        compositeStack.lastOrNull()?.let { parent ->
            if (parent != from.id) addChildIfMissing(parent, from.id)
            if (parent != to.id) addChildIfMissing(parent, to.id)
        }

        val parsed = splitEventGuardAction(labelText)
        transitions += StateTransition(
            from = from.id,
            to = to.id,
            event = parsed.event,
            guard = parsed.guard,
            action = parsed.action,
            label = if (labelText.isEmpty()) RichLabel.Empty else RichLabel.Plain(labelText),
        )
        return IrPatchBatch(seq, emptyList())
    }

    private data class ParsedEndpoint(val id: NodeId, val kind: StateKind)

    private fun parseEndpoint(raw: String, asTarget: Boolean): ParsedEndpoint? = when (raw) {
        "[*]" -> {
            val id = if (asTarget) NodeId("__final__${++finalCounter}") else NodeId("__init__${++initCounter}")
            val kind = if (asTarget) StateKind.Final else StateKind.Initial
            ensureState(id, name = "", kind = kind)
            ParsedEndpoint(id, kind)
        }
        "[H]" -> {
            val id = NodeId("__history__${++historyCounter}")
            ensureState(id, name = "", kind = StateKind.History)
            ParsedEndpoint(id, StateKind.History)
        }
        "[H*]" -> {
            val id = NodeId("__deep_history__${++deepHistoryCounter}")
            ensureState(id, name = "", kind = StateKind.DeepHistory)
            ParsedEndpoint(id, StateKind.DeepHistory)
        }
        else -> raw.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]+")) }?.let { ParsedEndpoint(NodeId(it), StateKind.Simple) }
    }

    private data class EventGuardAction(val event: String?, val guard: String?, val action: String?)

    private fun splitEventGuardAction(label: String): EventGuardAction {
        if (label.isEmpty()) return EventGuardAction(null, null, null)
        var rest = label
        var action: String? = null
        var guard: String? = null
        val slash = rest.indexOf('/')
        if (slash >= 0) {
            action = rest.substring(slash + 1).trim().ifEmpty { null }
            rest = rest.substring(0, slash).trim()
        }
        val lb = rest.indexOf('[')
        val rb = rest.indexOf(']')
        if (lb >= 0 && rb > lb) {
            guard = rest.substring(lb + 1, rb).trim().ifEmpty { null }
            rest = (rest.substring(0, lb) + rest.substring(rb + 1)).trim()
        }
        return EventGuardAction(rest.ifEmpty { null }, guard, action)
    }

    private fun parseNote(line: String): IrPatchBatch {
        val anchored = Regex(
            "^note\\s+(left|right|top|bottom)\\s+of\\s+([A-Za-z0-9_.:-]+)\\s*:\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(line)
        if (anchored != null) {
            val placement = when (anchored.groupValues[1].lowercase()) {
                "left" -> NotePlacement.LeftOf
                "right" -> NotePlacement.RightOf
                "top" -> NotePlacement.TopOf
                else -> NotePlacement.BottomOf
            }
            val target = NodeId(anchored.groupValues[2])
            ensureState(target, target.value)
            notes += StateNote(
                text = RichLabel.Plain(anchored.groupValues[3].trim()),
                targetState = target,
                placement = placement,
            )
            return IrPatchBatch(seq, emptyList())
        }

        val free = Regex("^note\\s+\"([^\"]+)\"$", RegexOption.IGNORE_CASE).matchEntire(line)
            ?: Regex("^note\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).matchEntire(line)
        if (free != null) {
            val text = free.groupValues.last().trim()
            notes += StateNote(text = RichLabel.Plain(text), placement = NotePlacement.Standalone)
            return IrPatchBatch(seq, emptyList())
        }

        return errorBatch("Invalid PlantUML state note syntax: $line")
    }

    private fun parseDirection(dir: Direction): IrPatchBatch {
        direction = dir
        return IrPatchBatch(seq, emptyList())
    }

    private fun ensureState(
        id: NodeId,
        name: String,
        description: String? = null,
        kind: StateKind = StateKind.Simple,
    ) {
        val existing = states[id]
        if (existing == null) {
            states[id] = StateNode(id = id, name = name, description = description, kind = kind)
            compositeStack.lastOrNull()?.let { parent -> if (parent != id) addChildIfMissing(parent, id) }
        } else {
            val mergedKind = if (existing.kind == StateKind.Simple) kind else existing.kind
            states[id] = existing.copy(
                name = if (existing.name.isEmpty() && name.isNotEmpty()) name else existing.name,
                description = description ?: existing.description,
                kind = mergedKind,
            )
        }
    }

    private fun addChildIfMissing(parent: NodeId, child: NodeId) {
        val p = states[parent] ?: return
        if (p.children.contains(child)) return
        states[parent] = p.copy(children = p.children + child)
    }

    private fun errorBatch(message: String): IrPatchBatch =
        IrPatchBatch(seq, listOf(addDiagnostic(Diagnostic(Severity.ERROR, message, "PLANTUML-E004"))))

    private fun addDiagnostic(diagnostic: Diagnostic): IrPatch {
        diagnostics += diagnostic
        return IrPatch.AddDiagnostic(diagnostic)
    }
}
