package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.FragmentKind
import com.hrm.diagram.core.ir.MessageKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.ParticipantKind
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MermaidSequenceParserTest {

    private fun parse(src: String, chunkSize: Int? = null): SequenceIR {
        val lexer = MermaidLexer()
        var state = lexer.initialState()
        val parser = MermaidSequenceParser()
        val all = ArrayList<Token>()
        if (chunkSize == null) {
            all += lexer.feed(state, src, 0, eos = true).tokens
        } else {
            var i = 0
            while (i < src.length) {
                val end = (i + chunkSize).coerceAtMost(src.length)
                val isLast = end == src.length
                val step = lexer.feed(state, src.substring(i, end), i, eos = isLast)
                state = step.newState
                all += step.tokens
                i = end
            }
        }
        // Split on newlines into logical lines.
        var start = 0
        for (i in all.indices) {
            if (all[i].kind == MermaidTokenKind.NEWLINE) {
                if (i > start) parser.acceptLine(all.subList(start, i).toList())
                else parser.acceptLine(emptyList())
                start = i + 1
            }
        }
        if (start < all.size) parser.acceptLine(all.subList(start, all.size).toList())
        return parser.snapshot()
    }

    @Test fun simple_message_auto_declares_participants() {
        val ir = parse("sequenceDiagram\nA ->> B: hi\n")
        assertEquals(listOf(NodeId("A"), NodeId("B")), ir.participants.map { it.id })
        assertEquals(1, ir.messages.size)
        assertEquals(MessageKind.Sync, ir.messages[0].kind)
        assertEquals(RichLabel.Plain("hi"), ir.messages[0].label)
    }

    @Test fun explicit_participant_declaration() {
        val ir = parse("sequenceDiagram\nparticipant A\nparticipant B\nA ->> B\n")
        assertEquals(2, ir.participants.size)
        assertEquals(ParticipantKind.Participant, ir.participants[0].kind)
    }

    @Test fun actor_declaration() {
        val ir = parse("sequenceDiagram\nactor Alice\nAlice ->> Bob\n")
        assertEquals(ParticipantKind.Actor, ir.participants.first { it.id.value == "Alice" }.kind)
    }

    @Test fun participant_with_alias() {
        val ir = parse("sequenceDiagram\nparticipant A as Alice\nA ->> A: x\n")
        val p = ir.participants.first()
        assertEquals(RichLabel.Plain("Alice"), p.label)
    }

    @Test fun all_six_arrow_kinds() {
        val ir = parse(
            """
            sequenceDiagram
            A ->> B: 1
            A -> B: 2
            A -->> B: 3
            A --> B: 4
            A -x B: 5
            A --x B: 6
            
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                MessageKind.Sync, MessageKind.Async, MessageKind.Reply,
                MessageKind.Reply, MessageKind.Destroy, MessageKind.Destroy,
            ),
            ir.messages.map { it.kind },
        )
    }

    @Test fun note_left_of() {
        val ir = parse("sequenceDiagram\nA ->> B\nnote left of A: hello\n")
        val n = ir.messages.last()
        assertEquals(MessageKind.Note, n.kind)
        assertEquals(NodeId("A"), n.from); assertEquals(NodeId("A"), n.to)
        assertEquals(RichLabel.Plain("hello"), n.label)
    }

    @Test fun note_right_of() {
        val ir = parse("sequenceDiagram\nA ->> B\nnote right of B: bye\n")
        val n = ir.messages.last()
        assertEquals(MessageKind.Note, n.kind)
        assertEquals(NodeId("B"), n.from); assertEquals(NodeId("B"), n.to)
    }

    @Test fun note_over_single() {
        val ir = parse("sequenceDiagram\nA ->> B\nnote over A: solo\n")
        val n = ir.messages.last()
        assertEquals(NodeId("A"), n.from); assertEquals(NodeId("A"), n.to)
    }

    @Test fun note_over_two_participants() {
        val ir = parse("sequenceDiagram\nA ->> B\nnote over A,B: shared\n")
        val n = ir.messages.last()
        assertEquals(NodeId("A"), n.from); assertEquals(NodeId("B"), n.to)
    }

    @Test fun activate_deactivate_explicit() {
        val ir = parse("sequenceDiagram\nA ->> B\nactivate B\nB ->> A\ndeactivate B\n")
        val activates = ir.messages.filter { it.activate && !it.deactivate && it.kind == MessageKind.Note }
        val deactivates = ir.messages.filter { it.deactivate && !it.activate && it.kind == MessageKind.Note }
        assertEquals(1, activates.size)
        assertEquals(1, deactivates.size)
    }

    @Test fun activate_deactivate_shorthand_plus_minus() {
        val ir = parse("sequenceDiagram\nA ->> +B: go\nB -->> -A: done\n")
        assertTrue(ir.messages[0].activate)
        assertTrue(ir.messages[1].deactivate)
    }

    @Test fun loop_fragment() {
        val ir = parse("sequenceDiagram\nloop forever\nA ->> B: tick\nend\n")
        assertEquals(1, ir.fragments.size)
        assertEquals(FragmentKind.Loop, ir.fragments[0].kind)
        assertEquals(1, ir.fragments[0].branches[0].size)
    }

    @Test fun alt_with_else() {
        val ir = parse("sequenceDiagram\nalt yes\nA ->> B: y\nelse no\nA ->> B: n\nend\n")
        assertEquals(FragmentKind.Alt, ir.fragments[0].kind)
        assertEquals(2, ir.fragments[0].branches.size)
    }

    @Test fun par_with_and() {
        val ir = parse("sequenceDiagram\npar one\nA ->> B: a\nand two\nA ->> B: b\nend\n")
        assertEquals(FragmentKind.Par, ir.fragments[0].kind)
        assertEquals(2, ir.fragments[0].branches.size)
    }

    @Test fun opt_fragment() {
        val ir = parse("sequenceDiagram\nopt maybe\nA ->> B\nend\n")
        assertEquals(FragmentKind.Opt, ir.fragments[0].kind)
    }

    @Test fun nested_fragments() {
        val ir = parse("sequenceDiagram\nloop outer\nalt inner\nA ->> B\nend\nend\n")
        assertEquals(2, ir.fragments.size)
        // Inner alt closed first (LIFO).
        assertEquals(FragmentKind.Alt, ir.fragments[0].kind)
        assertEquals(FragmentKind.Loop, ir.fragments[1].kind)
    }

    @Test fun autonumber_sets_style_hint() {
        val ir = parse("sequenceDiagram\nautonumber\nA ->> B\n")
        assertNotNull(ir.styleHints.extras["mermaid.autonumber"])
    }

    @Test fun no_autonumber_when_absent() {
        val ir = parse("sequenceDiagram\nA ->> B\n")
        assertNull(ir.styleHints.extras["mermaid.autonumber"])
    }

    @Test fun streaming_equivalence() {
        val src = """
            sequenceDiagram
            participant Alice
            participant Bob
            Alice ->> +Bob: request
            Bob -->> -Alice: response
            note over Alice,Bob: handshake
            
        """.trimIndent()
        val oneShot = parse(src)
        val streamed = parse(src, chunkSize = 1)
        assertEquals(oneShot.participants.map { it.id }, streamed.participants.map { it.id })
        assertEquals(oneShot.messages.size, streamed.messages.size)
        for (i in oneShot.messages.indices) {
            assertEquals(oneShot.messages[i].kind, streamed.messages[i].kind)
            assertEquals(oneShot.messages[i].label, streamed.messages[i].label)
            assertEquals(oneShot.messages[i].from, streamed.messages[i].from)
            assertEquals(oneShot.messages[i].to, streamed.messages[i].to)
        }
    }

    @Test fun unknown_participant_auto_declared_in_order() {
        val ir = parse("sequenceDiagram\nB ->> A\n")
        assertEquals(listOf(NodeId("B"), NodeId("A")), ir.participants.map { it.id })
    }

    @Test fun deactivate_shorthand_on_reply() {
        val ir = parse("sequenceDiagram\nA ->> +B\nB -->> -A: done\n")
        assertTrue(ir.messages[0].activate)
        assertTrue(ir.messages[1].deactivate)
        assertEquals(MessageKind.Reply, ir.messages[1].kind)
    }
}
