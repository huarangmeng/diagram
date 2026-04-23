package com.hrm.diagram.parser.mermaid

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
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for the Mermaid `stateDiagram` / `stateDiagram-v2` subset.
 *
 * Supported forms:
 *  - `[*] --> X` / `X --> [*]`        : initial / final pseudo-state transitions
 *  - `state X`                          : declare a simple state
 *  - `state "Long description" as X`   : state with description
 *  - `state X <<choice>>` / `<<fork>>` / `<<join>>` / `<<history>>` / `<<deep_history>>`
 *  - `state X { ... }`                  : composite state body
 *  - `A --> B`                          : simple transition
 *  - `A --> B : event [guard] / action` : transition with label (split into parts)
 *  - `note left|right|top|bottom of X : text` and `note "free text"`
 *  - `direction TB|BT|LR|RL`
 */
class MermaidStateParser {
    private val states: LinkedHashMap<NodeId, StateNode> = LinkedHashMap()
    private val transitions: MutableList<StateTransition> = ArrayList()
    private val notes: MutableList<StateNote> = ArrayList()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()
    private var direction: Direction? = null

    private var headerSeen: Boolean = false
    private var seq: Long = 0
    private var initCounter: Int = 0
    private var finalCounter: Int = 0

    /** Stack of composite state contexts (id of the parent composite). */
    private val compositeStack: ArrayDeque<NodeId> = ArrayDeque()

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())

        val errs = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errs != null) return errorBatch("Lex error at ${errs.start}: ${errs.text}")

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.STATE_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'stateDiagram' header")
        }

        // Closing brace pops composite context.
        if (toks.size == 1 && toks[0].kind == MermaidTokenKind.RBRACE) {
            if (compositeStack.isEmpty()) return errorBatch("Unmatched '}'")
            compositeStack.removeLast()
            return IrPatchBatch(seq, emptyList())
        }

        return parseStatement(toks)
    }

    fun snapshot(): StateIR = StateIR(
        states = states.values.toList(),
        transitions = transitions.toList(),
        notes = notes.toList(),
        sourceLanguage = SourceLanguage.MERMAID,
        styleHints = StyleHints(direction = direction),
    )

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    // --- statements ---

    private fun parseStatement(toks: List<Token>): IrPatchBatch {
        val first = toks.first()
        return when (first.kind) {
            MermaidTokenKind.STATE_KW -> parseStateDecl(toks)
            MermaidTokenKind.NOTE_KW -> parseNote(toks)
            MermaidTokenKind.DIRECTION_KW -> parseDirection(toks)
            MermaidTokenKind.START_END_TOKEN, MermaidTokenKind.IDENT -> parseTransition(toks)
            else -> errorBatch("Unexpected token '${first.text}' at start of statement")
        }
    }

    private fun parseStateDecl(toks: List<Token>): IrPatchBatch {
        // Forms:
        //   state X
        //   state X <<choice|fork|join|history|deep_history>>
        //   state "Long description" as X
        //   state X { ... }       (open composite body)
        //   state "desc" as X { ... }
        if (toks.size < 2) return errorBatch("Expected state name after 'state'")
        var idx = 1

        // Description string form: state "desc" as X
        var description: String? = null
        var name: String
        if (toks[idx].kind == MermaidTokenKind.STRING) {
            description = toks[idx].text.toString()
            idx++
            if (idx >= toks.size || toks[idx].kind != MermaidTokenKind.AS_KW) {
                return errorBatch("Expected 'as' after state description string")
            }
            idx++
            if (idx >= toks.size || toks[idx].kind != MermaidTokenKind.IDENT) {
                return errorBatch("Expected identifier after 'as'")
            }
            name = toks[idx].text.toString()
            idx++
        } else if (toks[idx].kind == MermaidTokenKind.IDENT) {
            name = toks[idx].text.toString()
            idx++
            // Optional 'as' description: state X as "desc" — be lenient.
            if (idx < toks.size && toks[idx].kind == MermaidTokenKind.AS_KW) {
                idx++
                if (idx < toks.size && toks[idx].kind == MermaidTokenKind.STRING) {
                    description = toks[idx].text.toString(); idx++
                }
            }
        } else {
            return errorBatch("Expected identifier or string after 'state'")
        }

        val id = NodeId(name)

        // Stereotype: <<choice>> etc.
        var kind: StateKind = StateKind.Simple
        if (idx < toks.size && toks[idx].kind == MermaidTokenKind.STEREOTYPE_OPEN) {
            idx++
            val sb = StringBuilder()
            while (idx < toks.size && toks[idx].kind != MermaidTokenKind.STEREOTYPE_CLOSE) {
                sb.append(toks[idx].text); idx++
            }
            if (idx < toks.size) idx++ // consume close
            kind = stereoToKind(sb.toString().trim()) ?: StateKind.Simple
        }

        val opensBody = idx < toks.size && toks[idx].kind == MermaidTokenKind.LBRACE
        val effectiveKind = if (opensBody) StateKind.Composite else kind
        ensureState(id, name = name, description = description, kind = effectiveKind)

        if (opensBody) {
            // Push composite context — body lines until matching '}'.
            compositeStack.addLast(id)
        }
        return IrPatchBatch(seq, emptyList())
    }

    private fun stereoToKind(s: String): StateKind? = when (s.lowercase()) {
        "choice" -> StateKind.Choice
        "fork" -> StateKind.Fork
        "join" -> StateKind.Join
        "history" -> StateKind.History
        "deep_history", "deephistory" -> StateKind.DeepHistory
        else -> null
    }

    private fun parseTransition(toks: List<Token>): IrPatchBatch {
        // [*] --> X : label  |  X --> [*]  |  X --> Y  |  X --> Y : event [guard] / action
        val (fromId, fromInitial, idxAfter1) = parseEndpoint(toks, 0, asTarget = false)
            ?: return errorBatch("Expected state identifier or '[*]'")
        if (idxAfter1 >= toks.size || toks[idxAfter1].kind != MermaidTokenKind.STATE_ARROW) {
            return errorBatch("Expected '-->' after '${toks[0].text}'")
        }
        val (toId, toFinal, idxAfter2) = parseEndpoint(toks, idxAfter1 + 1, asTarget = true)
            ?: return errorBatch("Expected target state identifier or '[*]'")

        // Optional label: : event [guard] / action
        var labelText = ""
        var event: String? = null
        var guard: String? = null
        var action: String? = null
        if (idxAfter2 < toks.size && toks[idxAfter2].kind == MermaidTokenKind.COLON) {
            val sb = StringBuilder()
            for (i in (idxAfter2 + 1) until toks.size) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(toks[i].text)
            }
            labelText = sb.toString().trim()
            val parts = splitEventGuardAction(labelText)
            event = parts.event
            guard = parts.guard
            action = parts.action
        }

        // Ensure both states exist (auto-create simple). Initial/Final are synthetic.
        if (!fromInitial) ensureState(fromId, name = fromId.value)
        if (!toFinal) ensureState(toId, name = toId.value)

        // If inside a composite, also register the new state as a child of the parent composite.
        compositeStack.lastOrNull()?.let { parent ->
            if (parent != fromId && parent != toId) {
                addChildIfMissing(parent, fromId)
                addChildIfMissing(parent, toId)
            }
        }

        transitions += StateTransition(
            from = fromId, to = toId,
            event = event, guard = guard, action = action,
            label = if (labelText.isEmpty()) RichLabel.Empty else RichLabel.Plain(labelText),
        )
        return IrPatchBatch(seq, emptyList())
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
        val event = rest.trim().ifEmpty { null }
        return EventGuardAction(event, guard, action)
    }

    /**
     * Parses an endpoint of a transition: either an IDENT (state id) or `[*]`. When `[*]` is
     * encountered we synthesise an Initial (source) or Final (target) pseudo-state node.
     */
    private fun parseEndpoint(toks: List<Token>, idx: Int, asTarget: Boolean): Triple<NodeId, Boolean, Int>? {
        if (idx >= toks.size) return null
        val t = toks[idx]
        return when (t.kind) {
            MermaidTokenKind.START_END_TOKEN -> {
                val pseudoId: NodeId
                val kind: StateKind
                if (asTarget) {
                    finalCounter++
                    pseudoId = NodeId("__final__$finalCounter")
                    kind = StateKind.Final
                } else {
                    initCounter++
                    pseudoId = NodeId("__init__$initCounter")
                    kind = StateKind.Initial
                }
                ensureState(pseudoId, name = "", kind = kind)
                Triple(pseudoId, true, idx + 1)
            }
            MermaidTokenKind.IDENT -> Triple(NodeId(t.text.toString()), false, idx + 1)
            else -> null
        }
    }

    private fun parseNote(toks: List<Token>): IrPatchBatch {
        var idx = 1
        var target: NodeId? = null
        var placement: NotePlacement = NotePlacement.Standalone

        val dirKind = toks.getOrNull(idx)?.kind
        if (dirKind == MermaidTokenKind.LEFT_KW || dirKind == MermaidTokenKind.RIGHT_KW ||
            dirKind == MermaidTokenKind.TOP_KW || dirKind == MermaidTokenKind.BOTTOM_KW
        ) {
            placement = when (dirKind) {
                MermaidTokenKind.LEFT_KW -> NotePlacement.LeftOf
                MermaidTokenKind.RIGHT_KW -> NotePlacement.RightOf
                MermaidTokenKind.TOP_KW -> NotePlacement.TopOf
                MermaidTokenKind.BOTTOM_KW -> NotePlacement.BottomOf
                else -> NotePlacement.RightOf
            }
            idx++
            if (toks.getOrNull(idx)?.kind != MermaidTokenKind.OF_KW) {
                return errorBatch("Expected 'of' after note direction")
            }
            idx++
            if (idx >= toks.size || toks[idx].kind != MermaidTokenKind.IDENT) {
                return errorBatch("Expected identifier after 'note <dir> of'")
            }
            target = NodeId(toks[idx].text.toString())
            ensureState(target, name = target.value)
            idx++
        }

        val text: String = when {
            idx < toks.size && toks[idx].kind == MermaidTokenKind.STRING ->
                toks[idx].text.toString()
            idx < toks.size && toks[idx].kind == MermaidTokenKind.COLON -> {
                idx++
                toks.subList(idx, toks.size).joinToString(" ") { it.text.toString() }.trim()
            }
            else -> return errorBatch("Expected note text (quoted string or ': text')")
        }

        if (target == null) placement = NotePlacement.Standalone
        notes += StateNote(
            text = RichLabel.of(text),
            targetState = target,
            placement = placement,
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseDirection(toks: List<Token>): IrPatchBatch {
        if (toks.size < 2 || toks[1].kind != MermaidTokenKind.IDENT) {
            return errorBatch("Expected direction (TB/LR/RL/BT)")
        }
        val d = when (toks[1].text.toString()) {
            "TB", "TD" -> Direction.TB
            "BT" -> Direction.BT
            "LR" -> Direction.LR
            "RL" -> Direction.RL
            else -> return errorBatch("Unknown direction '${toks[1].text}'")
        }
        direction = d
        return IrPatchBatch(seq, emptyList())
    }

    private fun ensureState(id: NodeId, name: String, description: String? = null, kind: StateKind = StateKind.Simple) {
        val existing = states[id]
        if (existing == null) {
            states[id] = StateNode(id = id, name = name, description = description, kind = kind)
            // Track parent composite child list.
            compositeStack.lastOrNull()?.let { parent ->
                if (parent != id) addChildIfMissing(parent, id)
            }
        } else {
            // Upgrade simple state to a more specific kind / description if newly known.
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

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MMD-S001")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }
}
