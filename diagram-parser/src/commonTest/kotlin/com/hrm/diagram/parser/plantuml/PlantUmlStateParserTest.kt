package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.Direction
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NotePlacement
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.StateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlantUmlStateParserTest {
    private fun parse(src: String, chunkSize: Int? = null): PlantUmlStateParser {
        val parser = PlantUmlStateParser()
        if (chunkSize == null) {
            src.lines().forEach { parser.acceptLine(it) }
        } else {
            var pending = ""
            var index = 0
            while (index < src.length) {
                val end = (index + chunkSize).coerceAtMost(src.length)
                val merged = pending + src.substring(index, end)
                var start = 0
                for (i in merged.indices) {
                    if (merged[i] == '\n') {
                        parser.acceptLine(merged.substring(start, i))
                        start = i + 1
                    }
                }
                pending = if (start < merged.length) merged.substring(start) else ""
                index = end
            }
            if (pending.isNotEmpty()) parser.acceptLine(pending)
        }
        parser.finish(blockClosed = true)
        return parser
    }

    @Test
    fun simple_state_declaration() {
        val ir = parse("state Ready\n").snapshot()
        val s = ir.states.single()
        assertEquals(NodeId("Ready"), s.id)
        assertEquals(StateKind.Simple, s.kind)
    }

    @Test
    fun alias_description_form() {
        val ir = parse("state \"Long description\" as Ready\n").snapshot()
        assertEquals("Long description", ir.states.single().description)
    }

    @Test
    fun shorthand_description_form() {
        val ir = parse("state Ready : long text\n").snapshot()
        assertEquals("long text", ir.states.single().description)
    }

    @Test
    fun pseudo_states_are_synthesized() {
        val ir = parse(
            """
            [*] --> A
            A --> [H]
            [H*] --> B
            B --> [*]
            """.trimIndent() + "\n",
        ).snapshot()
        assertTrue(ir.states.any { it.kind == StateKind.Initial })
        assertTrue(ir.states.any { it.kind == StateKind.Final })
        assertTrue(ir.states.any { it.kind == StateKind.History })
        assertTrue(ir.states.any { it.kind == StateKind.DeepHistory })
    }

    @Test
    fun stereotype_state_kinds() {
        val ir = parse(
            """
            state C <<choice>>
            state F <<fork>>
            state J <<join>>
            """.trimIndent() + "\n",
        ).snapshot()
        assertEquals(StateKind.Choice, ir.states.first { it.id == NodeId("C") }.kind)
        assertEquals(StateKind.Fork, ir.states.first { it.id == NodeId("F") }.kind)
        assertEquals(StateKind.Join, ir.states.first { it.id == NodeId("J") }.kind)
    }

    @Test
    fun transition_parses_event_guard_action() {
        val t = parse("A --> B : open [ready] / log\n").snapshot().transitions.single()
        assertEquals("open", t.event)
        assertEquals("ready", t.guard)
        assertEquals("log", t.action)
    }

    @Test
    fun composite_state_collects_children() {
        val ir = parse(
            """
            state Active {
              [*] --> Loading
              Loading --> Ready
            }
            """.trimIndent() + "\n",
        ).snapshot()
        val active = ir.states.first { it.id == NodeId("Active") }
        assertEquals(StateKind.Composite, active.kind)
        assertTrue(active.children.contains(NodeId("Loading")))
        assertTrue(active.children.contains(NodeId("Ready")))
    }

    @Test
    fun nested_composite_supported() {
        val ir = parse(
            """
            state Outer {
              state Inner {
                A --> B
              }
            }
            """.trimIndent() + "\n",
        ).snapshot()
        val outer = ir.states.first { it.id == NodeId("Outer") }
        val inner = ir.states.first { it.id == NodeId("Inner") }
        assertTrue(outer.children.contains(NodeId("Inner")))
        assertTrue(inner.children.contains(NodeId("A")))
    }

    @Test
    fun note_right_of_state() {
        val note = parse("state A\nnote right of A : hello\n").snapshot().notes.single()
        assertEquals(NodeId("A"), note.targetState)
        assertEquals(NotePlacement.RightOf, note.placement)
        assertEquals(RichLabel.Plain("hello"), note.text)
    }

    @Test
    fun free_note_supported() {
        val note = parse("note \"global\"\n").snapshot().notes.single()
        assertNull(note.targetState)
        assertEquals(NotePlacement.Standalone, note.placement)
    }

    @Test
    fun direction_supported() {
        val ir = parse("left to right direction\nA --> B\n").snapshot()
        assertEquals(Direction.LR, ir.styleHints.direction)
    }

    @Test
    fun parallel_separator_is_accepted() {
        val ir = parse(
            """
            state Parent {
              A --> B
              --
              B --> C
            }
            """.trimIndent() + "\n",
        ).snapshot()
        assertEquals(2, ir.transitions.size)
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            state Working {
              [*] --> A
              A --> [H]
            }
            note bottom of Working : flow
            """.trimIndent() + "\n"
        assertEquals(parse(src).snapshot(), parse(src, chunkSize = 1).snapshot())
    }
}
