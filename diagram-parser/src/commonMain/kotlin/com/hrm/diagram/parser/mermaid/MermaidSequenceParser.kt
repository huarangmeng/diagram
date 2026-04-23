package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.Diagnostic
import com.hrm.diagram.core.ir.FragmentKind
import com.hrm.diagram.core.ir.MessageKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.Participant
import com.hrm.diagram.core.ir.ParticipantKind
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SequenceFragment
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.ir.SequenceMessage
import com.hrm.diagram.core.ir.Severity
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.StyleHints
import com.hrm.diagram.core.streaming.IrPatch
import com.hrm.diagram.core.streaming.IrPatchBatch
import com.hrm.diagram.core.streaming.Token

/**
 * Streaming parser for the Mermaid `sequenceDiagram` Phase-2 subset.
 *
 * The parser is fed one logical line of tokens at a time (mirrors [MermaidFlowchartParser]).
 * Recognised statements:
 * - `sequenceDiagram` header (must be first non-blank line)
 * - `participant <id> [as <alias>]` / `actor <id> [as <alias>]`
 * - `<A> <arrow> [+|-]<B> [: label]` (six arrow kinds)
 * - `note left of <P> [: label]` / `note right of <P> [: label]` / `note over <A>[, <B>] [: label]`
 * - `activate <P>` / `deactivate <P>`
 * - `loop <title>` / `alt <title>` / `else <title>` / `opt <title>` /
 *   `par <title>` / `and <title>` / `critical <title>` / `option <title>` /
 *   `break <title>` / `end`
 * - `autonumber [start [step]]`
 *
 * Note that messages produced inside fragments are appended to **both** the top-level
 * [SequenceIR.messages] (for layout ordering) and the enclosing fragment's branch (for
 * structural fidelity).
 */
class MermaidSequenceParser {
    private val participantOrder: LinkedHashMap<NodeId, Participant> = LinkedHashMap()
    private val messages: MutableList<SequenceMessage> = ArrayList()
    private val fragments: MutableList<SequenceFragment> = ArrayList()
    private val fragmentStack: ArrayDeque<FragmentBuilder> = ArrayDeque()
    private val diagnostics: MutableList<Diagnostic> = ArrayList()

    private var headerSeen: Boolean = false
    private var autonumberStart: Int? = null
    private var autonumberStep: Int = 1
    private var seq: Long = 0

    fun acceptLine(line: List<Token>): IrPatchBatch {
        seq++
        if (line.isEmpty()) return IrPatchBatch(seq, emptyList())
        val toks = line.filter { it.kind != MermaidTokenKind.COMMENT }
        if (toks.isEmpty()) return IrPatchBatch(seq, emptyList())

        val errs = toks.firstOrNull { it.kind == MermaidTokenKind.ERROR }
        if (errs != null) {
            return errorBatch("Lex error at ${errs.start}: ${errs.text}")
        }

        if (!headerSeen) {
            if (toks.first().kind == MermaidTokenKind.SEQUENCE_HEADER) {
                headerSeen = true
                return IrPatchBatch(seq, emptyList())
            }
            return errorBatch("Expected 'sequenceDiagram' header")
        }

        return parseStatement(toks)
    }

    /** Build a fresh [SequenceIR] snapshot. */
    fun snapshot(): SequenceIR {
        val extras = HashMap<String, String>()
        autonumberStart?.let {
            extras["mermaid.autonumber"] = "$it,$autonumberStep"
        }
        return SequenceIR(
            participants = participantOrder.values.toList(),
            messages = messages.toList(),
            fragments = fragments.toList(),
            sourceLanguage = SourceLanguage.MERMAID,
            styleHints = StyleHints(extras = extras),
        )
    }

    fun diagnosticsSnapshot(): List<Diagnostic> = diagnostics.toList()

    // --- internals ---

    private fun parseStatement(toks: List<Token>): IrPatchBatch {
        val first = toks.first()
        return when (first.kind) {
            MermaidTokenKind.PARTICIPANT_KW -> parseParticipantDecl(toks, ParticipantKind.Participant)
            MermaidTokenKind.ACTOR_KW -> parseParticipantDecl(toks, ParticipantKind.Actor)
            MermaidTokenKind.NOTE_KW -> parseNote(toks)
            MermaidTokenKind.ACTIVATE_KW -> parseActivate(toks, activate = true)
            MermaidTokenKind.DEACTIVATE_KW -> parseActivate(toks, activate = false)
            MermaidTokenKind.LOOP_KW -> pushFragment(FragmentKind.Loop, restAsTitle(toks, 1))
            MermaidTokenKind.ALT_KW -> pushFragment(FragmentKind.Alt, restAsTitle(toks, 1))
            MermaidTokenKind.OPT_KW -> pushFragment(FragmentKind.Opt, restAsTitle(toks, 1))
            MermaidTokenKind.PAR_KW -> pushFragment(FragmentKind.Par, restAsTitle(toks, 1))
            MermaidTokenKind.CRITICAL_KW -> pushFragment(FragmentKind.Critical, restAsTitle(toks, 1))
            MermaidTokenKind.BREAK_KW -> pushFragment(FragmentKind.Break, restAsTitle(toks, 1))
            MermaidTokenKind.ELSE_KW, MermaidTokenKind.AND_KW, MermaidTokenKind.OPTION_KW ->
                addBranch()
            MermaidTokenKind.END_KW -> popFragment()
            MermaidTokenKind.AUTONUMBER_KW -> parseAutonumber(toks)
            MermaidTokenKind.IDENT -> parseMessage(toks)
            else -> errorBatch("Unexpected token '${first.text}' at start of statement")
        }
    }

    private fun parseParticipantDecl(toks: List<Token>, kind: ParticipantKind): IrPatchBatch {
        if (toks.size < 2 || toks[1].kind != MermaidTokenKind.IDENT) {
            return errorBatch("Expected identifier after '${toks[0].text}'")
        }
        val id = NodeId(toks[1].text.toString())
        var label: RichLabel = RichLabel.Empty
        if (toks.size >= 4 && toks[2].kind == MermaidTokenKind.AS_KW) {
            val aliasTok = toks[3]
            val text = if (aliasTok.kind == MermaidTokenKind.LABEL || aliasTok.kind == MermaidTokenKind.IDENT) {
                aliasTok.text.toString()
            } else aliasTok.text.toString()
            label = RichLabel.Plain(text)
        } else if (toks.size > 2) {
            return errorBatch("Trailing tokens after participant declaration")
        }
        // Replace existing or insert.
        val existing = participantOrder[id]
        val resolvedLabel = if (label.isEmpty && existing != null) existing.label else label
        participantOrder[id] = Participant(id = id, label = resolvedLabel, kind = kind)
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseNote(toks: List<Token>): IrPatchBatch {
        // note left of P [: label]
        // note right of P [: label]
        // note over P[, Q] [: label]
        if (toks.size < 2) return errorBatch("Truncated 'note' statement")
        val second = toks[1]
        return when (second.kind) {
            MermaidTokenKind.LEFT_KW, MermaidTokenKind.RIGHT_KW -> {
                if (toks.size < 4 || toks[2].kind != MermaidTokenKind.OF_KW || toks[3].kind != MermaidTokenKind.IDENT) {
                    return errorBatch("Expected 'note (left|right) of <ident>'")
                }
                val pid = NodeId(toks[3].text.toString())
                ensureParticipant(pid)
                val label = parseTrailingLabel(toks, 4)
                addMessage(SequenceMessage(from = pid, to = pid, kind = MessageKind.Note, label = label))
                IrPatchBatch(seq, emptyList())
            }
            MermaidTokenKind.OVER_KW -> {
                if (toks.size < 3 || toks[2].kind != MermaidTokenKind.IDENT) {
                    return errorBatch("Expected 'note over <ident>[, <ident>]'")
                }
                val a = NodeId(toks[2].text.toString())
                ensureParticipant(a)
                var b = a
                var idx = 3
                if (idx < toks.size && toks[idx].kind == MermaidTokenKind.COMMA) {
                    if (idx + 1 >= toks.size || toks[idx + 1].kind != MermaidTokenKind.IDENT) {
                        return errorBatch("Expected identifier after ',' in note over")
                    }
                    b = NodeId(toks[idx + 1].text.toString())
                    ensureParticipant(b)
                    idx += 2
                }
                val label = parseTrailingLabel(toks, idx)
                addMessage(SequenceMessage(from = a, to = b, kind = MessageKind.Note, label = label))
                IrPatchBatch(seq, emptyList())
            }
            else -> errorBatch("Expected 'left', 'right', or 'over' after 'note'")
        }
    }

    private fun parseActivate(toks: List<Token>, activate: Boolean): IrPatchBatch {
        if (toks.size < 2 || toks[1].kind != MermaidTokenKind.IDENT) {
            return errorBatch("Expected identifier after activate/deactivate")
        }
        val id = NodeId(toks[1].text.toString())
        ensureParticipant(id)
        addMessage(
            SequenceMessage(
                from = id,
                to = id,
                kind = MessageKind.Note,
                activate = activate,
                deactivate = !activate,
                label = RichLabel.Empty,
            ),
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseMessage(toks: List<Token>): IrPatchBatch {
        // IDENT <ARROW> [PLUS|MINUS]? IDENT [COLON LABEL]
        val from = NodeId(toks[0].text.toString())
        if (toks.size < 3) return errorBatch("Truncated message statement")
        val arrowTok = toks[1]
        val arrowKind = arrowKindFor(arrowTok.kind)
            ?: return errorBatch("Expected message arrow after '${toks[0].text}', got '${arrowTok.text}'")
        var idx = 2
        var activate = false
        var deactivate = false
        if (toks[idx].kind == MermaidTokenKind.PLUS) {
            activate = true; idx++
        } else if (toks[idx].kind == MermaidTokenKind.MINUS) {
            deactivate = true; idx++
        }
        if (idx >= toks.size || toks[idx].kind != MermaidTokenKind.IDENT) {
            return errorBatch("Expected target identifier in message")
        }
        val to = NodeId(toks[idx].text.toString())
        idx++
        val label = parseTrailingLabel(toks, idx)
        ensureParticipant(from)
        ensureParticipant(to)
        addMessage(
            SequenceMessage(
                from = from,
                to = to,
                label = label,
                kind = arrowKind,
                activate = activate,
                deactivate = deactivate,
            ),
        )
        return IrPatchBatch(seq, emptyList())
    }

    private fun parseTrailingLabel(toks: List<Token>, startIdx: Int): RichLabel {
        if (startIdx >= toks.size) return RichLabel.Empty
        if (toks[startIdx].kind != MermaidTokenKind.COLON) {
            // Tolerate trailing tokens but ignore (parser is lenient by design here).
            return RichLabel.Empty
        }
        if (startIdx + 1 >= toks.size) return RichLabel.Empty
        val labelTok = toks[startIdx + 1]
        if (labelTok.kind != MermaidTokenKind.LABEL) return RichLabel.Empty
        val text = labelTok.text.toString()
        return if (text.isEmpty()) RichLabel.Empty else RichLabel.Plain(text)
    }

    private fun parseAutonumber(toks: List<Token>): IrPatchBatch {
        // autonumber                           → start=1, step=1
        // autonumber <start>                   → start=N, step=1
        // autonumber <start> <step>            → start=N, step=M
        autonumberStart = 1
        autonumberStep = 1
        if (toks.size >= 2 && toks[1].kind == MermaidTokenKind.IDENT) {
            val n = toks[1].text.toString().toIntOrNull()
            if (n != null) autonumberStart = n
        }
        if (toks.size >= 3 && toks[2].kind == MermaidTokenKind.IDENT) {
            val s = toks[2].text.toString().toIntOrNull()
            if (s != null) autonumberStep = s
        }
        return IrPatchBatch(seq, emptyList())
    }

    private fun restAsTitle(toks: List<Token>, fromIdx: Int): String? {
        if (fromIdx >= toks.size) return null
        val sb = StringBuilder()
        for (i in fromIdx until toks.size) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(toks[i].text)
        }
        val s = sb.toString().trim()
        return s.ifEmpty { null }
    }

    private fun pushFragment(kind: FragmentKind, title: String?): IrPatchBatch {
        fragmentStack.addLast(FragmentBuilder(kind, title))
        return IrPatchBatch(seq, emptyList())
    }

    private fun addBranch(): IrPatchBatch {
        val top = fragmentStack.lastOrNull() ?: return errorBatch("'else'/'and'/'option' outside any fragment")
        top.newBranch()
        return IrPatchBatch(seq, emptyList())
    }

    private fun popFragment(): IrPatchBatch {
        val top = fragmentStack.removeLastOrNull()
            ?: return errorBatch("'end' without matching fragment")
        fragments += top.build()
        return IrPatchBatch(seq, emptyList())
    }

    private fun ensureParticipant(id: NodeId) {
        if (id !in participantOrder) {
            participantOrder[id] = Participant(id = id, label = RichLabel.Empty, kind = ParticipantKind.Participant)
        }
    }

    private fun addMessage(msg: SequenceMessage) {
        messages += msg
        fragmentStack.lastOrNull()?.currentBranch?.add(msg)
    }

    private fun errorBatch(message: String): IrPatchBatch {
        val d = Diagnostic(Severity.ERROR, message, "MMD-S001")
        diagnostics += d
        return IrPatchBatch(seq, listOf(IrPatch.AddDiagnostic(d)))
    }

    private fun arrowKindFor(kind: Int): MessageKind? = when (kind) {
        MermaidTokenKind.MSG_ARROW_SYNC -> MessageKind.Sync
        MermaidTokenKind.MSG_ARROW_ASYNC -> MessageKind.Async
        MermaidTokenKind.MSG_ARROW_REPLY_SYNC -> MessageKind.Reply
        MermaidTokenKind.MSG_ARROW_REPLY_DASH -> MessageKind.Reply
        MermaidTokenKind.MSG_ARROW_LOST -> MessageKind.Destroy
        MermaidTokenKind.MSG_ARROW_LOST_DASH -> MessageKind.Destroy
        else -> null
    }

    private class FragmentBuilder(val kind: FragmentKind, val title: String?) {
        val branches: MutableList<MutableList<SequenceMessage>> = mutableListOf(mutableListOf())
        val currentBranch: MutableList<SequenceMessage> get() = branches.last()
        fun newBranch() { branches.add(mutableListOf()) }
        fun build(): SequenceFragment = SequenceFragment(
            kind = kind,
            title = title?.let { RichLabel.Plain(it) },
            branches = branches.map { it.toList() },
        )
    }
}
