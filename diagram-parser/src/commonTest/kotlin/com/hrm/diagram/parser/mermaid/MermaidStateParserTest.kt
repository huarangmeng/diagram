package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NotePlacement
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.StateKind
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MermaidStateParserTest {

    private fun feed(src: String): MermaidStateParser {
        val parser = MermaidStateParser()
        val lexer = MermaidLexer()
        val toks = lexer.feed(lexer.initialState(), src, 0, eos = true).tokens
        val lines = ArrayList<MutableList<Token>>()
        var cur = ArrayList<Token>()
        for (t in toks) {
            if (t.kind == MermaidTokenKind.NEWLINE) {
                if (cur.isNotEmpty()) { lines += cur; cur = ArrayList() }
            } else cur.add(t)
        }
        if (cur.isNotEmpty()) lines += cur
        for (l in lines) parser.acceptLine(l)
        return parser
    }

    @Test fun header_required() {
        val p = MermaidStateParser()
        val batch = p.acceptLine(listOf(Token(MermaidTokenKind.IDENT, 0, 1, "X")))
        assertTrue(batch.patches.isNotEmpty())
        assertTrue(p.diagnosticsSnapshot().any { it.message.contains("stateDiagram") })
    }

    @Test fun header_v2_accepted() {
        val p = feed("stateDiagram-v2\nstate A\n")
        assertEquals(1, p.snapshot().states.size)
    }

    @Test fun header_v1_accepted() {
        val p = feed("stateDiagram\nstate A\n")
        assertEquals(1, p.snapshot().states.size)
    }

    @Test fun simple_state_decl() {
        val p = feed("stateDiagram-v2\nstate Idle\n")
        val s = p.snapshot().states.single()
        assertEquals(NodeId("Idle"), s.id)
        assertEquals("Idle", s.name)
        assertEquals(StateKind.Simple, s.kind)
        assertNull(s.description)
    }

    @Test fun state_with_description() {
        val p = feed("stateDiagram-v2\nstate \"Long description\" as Idle\n")
        val s = p.snapshot().states.single()
        assertEquals("Idle", s.name)
        assertEquals("Long description", s.description)
    }

    @Test fun initial_pseudo_state_synthesised() {
        val p = feed("stateDiagram-v2\n[*] --> A\n")
        val ir = p.snapshot()
        val initial = ir.states.first { it.kind == StateKind.Initial }
        assertTrue(initial.id.value.startsWith("__init__"))
        assertEquals(NodeId("A"), ir.transitions.single().to)
    }

    @Test fun final_pseudo_state_synthesised() {
        val p = feed("stateDiagram-v2\nA --> [*]\n")
        val ir = p.snapshot()
        val finalNode = ir.states.first { it.kind == StateKind.Final }
        assertTrue(finalNode.id.value.startsWith("__final__"))
        assertEquals(NodeId("A"), ir.transitions.single().from)
    }

    @Test fun simple_transition_implicit_states() {
        val p = feed("stateDiagram-v2\nA --> B\n")
        val ir = p.snapshot()
        assertEquals(2, ir.states.size)
        assertEquals(1, ir.transitions.size)
    }

    @Test fun transition_with_event_label() {
        val p = feed("stateDiagram-v2\nA --> B : start\n")
        val t = p.snapshot().transitions.single()
        assertEquals("start", t.event)
        assertNull(t.guard)
        assertNull(t.action)
        assertEquals(RichLabel.Plain("start"), t.label)
    }

    @Test fun transition_with_guard_and_action() {
        val p = feed("stateDiagram-v2\nA --> B : open [ready] / log\n")
        val t = p.snapshot().transitions.single()
        assertEquals("open", t.event)
        assertEquals("ready", t.guard)
        assertEquals("log", t.action)
    }

    @Test fun transition_only_action() {
        val p = feed("stateDiagram-v2\nA --> B : / fire\n")
        val t = p.snapshot().transitions.single()
        assertEquals("fire", t.action)
    }

    @Test fun stereotype_choice() {
        val p = feed("stateDiagram-v2\nstate C <<choice>>\n")
        assertEquals(StateKind.Choice, p.snapshot().states.single().kind)
    }

    @Test fun stereotype_fork_join() {
        val p = feed("stateDiagram-v2\nstate F <<fork>>\nstate J <<join>>\n")
        val ir = p.snapshot()
        assertEquals(StateKind.Fork, ir.states.first { it.id == NodeId("F") }.kind)
        assertEquals(StateKind.Join, ir.states.first { it.id == NodeId("J") }.kind)
    }

    @Test fun stereotype_history() {
        val p = feed("stateDiagram-v2\nstate H <<history>>\nstate D <<deep_history>>\n")
        val ir = p.snapshot()
        assertEquals(StateKind.History, ir.states.first { it.id == NodeId("H") }.kind)
        assertEquals(StateKind.DeepHistory, ir.states.first { it.id == NodeId("D") }.kind)
    }

    @Test fun composite_state_collects_children() {
        val src = """
            stateDiagram-v2
            state Active {
              [*] --> Loading
              Loading --> Ready
            }
        """.trimIndent() + "\n"
        val ir = feed(src).snapshot()
        val active = ir.states.first { it.id == NodeId("Active") }
        assertEquals(StateKind.Composite, active.kind)
        assertTrue(active.children.contains(NodeId("Loading")))
        assertTrue(active.children.contains(NodeId("Ready")))
    }

    @Test fun nested_composite_supported() {
        val src = """
            stateDiagram-v2
            state Outer {
              state Inner {
                A --> B
              }
            }
        """.trimIndent() + "\n"
        val ir = feed(src).snapshot()
        val outer = ir.states.first { it.id == NodeId("Outer") }
        val inner = ir.states.first { it.id == NodeId("Inner") }
        assertEquals(StateKind.Composite, outer.kind)
        assertEquals(StateKind.Composite, inner.kind)
        assertTrue(outer.children.contains(NodeId("Inner")))
        assertTrue(inner.children.contains(NodeId("A")))
    }

    @Test fun unmatched_brace_diagnostic() {
        val p = feed("stateDiagram-v2\n}\n")
        assertTrue(p.diagnosticsSnapshot().any { it.message.contains("Unmatched") })
    }

    @Test fun direction_tb() {
        val p = feed("stateDiagram-v2\ndirection LR\nA --> B\n")
        val ir = p.snapshot()
        assertEquals(com.hrm.diagram.core.ir.Direction.LR, ir.styleHints.direction)
    }

    @Test fun note_right_of_state() {
        val p = feed("stateDiagram-v2\nstate A\nnote right of A : hello\n")
        val n = p.snapshot().notes.single()
        assertEquals(NodeId("A"), n.targetState)
        assertEquals(NotePlacement.RightOf, n.placement)
        assertEquals(RichLabel.Plain("hello"), n.text)
    }

    @Test fun note_left_of_state() {
        val p = feed("stateDiagram-v2\nstate A\nnote left of A : world\n")
        val n = p.snapshot().notes.single()
        assertEquals(NotePlacement.LeftOf, n.placement)
    }

    @Test fun note_freestanding_string() {
        val p = feed("stateDiagram-v2\nnote \"global\"\n")
        val n = p.snapshot().notes.single()
        assertNull(n.targetState)
        assertEquals(NotePlacement.Standalone, n.placement)
    }

    @Test fun multi_initial_pseudo_states_distinct_ids() {
        val p = feed("stateDiagram-v2\n[*] --> A\nstate Outer {\n[*] --> B\n}\n")
        val initials = p.snapshot().states.filter { it.kind == StateKind.Initial }
        assertEquals(2, initials.size)
    }

    @Test fun chained_transitions() {
        val p = feed("stateDiagram-v2\nA --> B\nB --> C\nC --> D\n")
        val ir = p.snapshot()
        assertEquals(4, ir.states.size)
        assertEquals(3, ir.transitions.size)
    }

    @Test fun initial_to_final_full_lifecycle() {
        val src = """
            stateDiagram-v2
            [*] --> Idle
            Idle --> Working : start
            Working --> Idle : done
            Idle --> [*]
        """.trimIndent() + "\n"
        val ir = feed(src).snapshot()
        assertEquals(4, ir.transitions.size)
        // Idle, Working + 1 init + 1 final = 4
        assertEquals(4, ir.states.size)
    }

    @Test fun streaming_chunked_equivalent() {
        val src = "stateDiagram-v2\nA --> B\nB --> C\n"
        val pOne = feed(src)
        // tiny chunk
        val parser = MermaidStateParser()
        val lexer = MermaidLexer()
        var st = lexer.initialState()
        var off = 0
        val all = ArrayList<Token>()
        for (ch in src) {
            val step = lexer.feed(st, ch.toString(), off, eos = false)
            st = step.newState
            all += step.tokens
            off++
        }
        val finalStep = lexer.feed(st, "", off, eos = true)
        all += finalStep.tokens
        val lines = ArrayList<MutableList<Token>>()
        var cur = ArrayList<Token>()
        for (t in all) {
            if (t.kind == MermaidTokenKind.NEWLINE) {
                if (cur.isNotEmpty()) { lines += cur; cur = ArrayList() }
            } else cur.add(t)
        }
        if (cur.isNotEmpty()) lines += cur
        for (l in lines) parser.acceptLine(l)

        assertEquals(pOne.snapshot().states.map { it.id }, parser.snapshot().states.map { it.id })
        assertEquals(pOne.snapshot().transitions.size, parser.snapshot().transitions.size)
    }

    @Test fun composite_with_stereotype_kept_as_composite() {
        val src = "stateDiagram-v2\nstate Active <<choice>> {\nA --> B\n}\n"
        val ir = feed(src).snapshot()
        val active = ir.states.first { it.id == NodeId("Active") }
        // Composite body wins over stereotype since braces follow.
        assertEquals(StateKind.Composite, active.kind)
    }

    @Test fun transition_label_with_no_event_just_guard() {
        val p = feed("stateDiagram-v2\nA --> B : [done]\n")
        val t = p.snapshot().transitions.single()
        assertNull(t.event)
        assertEquals("done", t.guard)
    }

    @Test fun snapshot_reports_correct_state_kinds() {
        val src = """
            stateDiagram-v2
            [*] --> A
            state C <<choice>>
            A --> C
            C --> [*]
        """.trimIndent() + "\n"
        val ir = feed(src).snapshot()
        val kinds = ir.states.associate { it.id to it.kind }
        assertEquals(StateKind.Choice, kinds[NodeId("C")])
        assertEquals(StateKind.Simple, kinds[NodeId("A")])
        assertNotNull(kinds.entries.firstOrNull { it.value == StateKind.Initial })
        assertNotNull(kinds.entries.firstOrNull { it.value == StateKind.Final })
    }

    @Test fun comment_lines_ignored() {
        val p = feed("stateDiagram-v2\n%% a comment\nA --> B\n")
        assertEquals(1, p.snapshot().transitions.size)
    }
}
