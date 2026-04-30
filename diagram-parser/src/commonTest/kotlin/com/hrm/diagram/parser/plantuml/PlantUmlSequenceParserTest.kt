package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.FragmentKind
import com.hrm.diagram.core.ir.MessageKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.ParticipantKind
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SequenceIR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlantUmlSequenceParserTest {
    private fun parse(src: String, chunkSize: Int? = null): SequenceIR {
        val parser = PlantUmlSequenceParser()
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
        return parser.snapshot()
    }

    @Test
    fun message_auto_declares_participants() {
        val ir = parse("Alice -> Bob: hello\n")
        assertEquals(listOf(NodeId("Alice"), NodeId("Bob")), ir.participants.map { it.id })
        assertEquals(MessageKind.Sync, ir.messages.single().kind)
        assertEquals(RichLabel.Plain("hello"), ir.messages.single().label)
    }

    @Test
    fun declarations_support_alias_forms() {
        val ir = parse(
            """
            actor "Alice Smith" as Alice
            participant Bob as "Bob Jones"
            Alice -> Bob
            """.trimIndent() + "\n",
        )
        assertEquals(ParticipantKind.Actor, ir.participants.first { it.id.value == "Alice" }.kind)
        assertEquals(RichLabel.Plain("Alice Smith"), ir.participants.first { it.id.value == "Alice" }.label)
        assertEquals(RichLabel.Plain("Bob Jones"), ir.participants.first { it.id.value == "Bob" }.label)
    }

    @Test
    fun dashed_arrow_maps_to_reply() {
        val ir = parse("Alice --> Bob: done\n")
        assertEquals(MessageKind.Reply, ir.messages.single().kind)
    }

    @Test
    fun return_creates_reverse_reply() {
        val ir = parse(
            """
            Alice -> Bob: ping
            return pong
            """.trimIndent() + "\n",
        )
        assertEquals(2, ir.messages.size)
        assertEquals(MessageKind.Reply, ir.messages[1].kind)
        assertEquals(NodeId("Bob"), ir.messages[1].from)
        assertEquals(NodeId("Alice"), ir.messages[1].to)
    }

    @Test
    fun note_and_activation_are_preserved() {
        val ir = parse(
            """
            Alice -> Bob
            note over Alice,Bob: sync
            activate Bob
            deactivate Bob
            """.trimIndent() + "\n",
        )
        assertEquals(MessageKind.Note, ir.messages[1].kind)
        assertTrue(ir.messages[2].activate)
        assertTrue(ir.messages[3].deactivate)
    }

    @Test
    fun fragments_build_branches() {
        val ir = parse(
            """
            alt ok
            Alice -> Bob: yes
            else fail
            Alice -> Bob: no
            end
            """.trimIndent() + "\n",
        )
        assertEquals(1, ir.fragments.size)
        assertEquals(FragmentKind.Alt, ir.fragments.single().kind)
        assertEquals(2, ir.fragments.single().branches.size)
    }

    @Test
    fun autonumber_sets_style_hint() {
        val ir = parse(
            """
            autonumber 3 2
            Alice -> Bob
            """.trimIndent() + "\n",
        )
        assertEquals("3,2", ir.styleHints.extras["plantuml.autonumber"])
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            actor Alice
            participant Bob
            Alice -> Bob: request
            Bob --> Alice: response
            loop retry
            Alice -> Bob: again
            end
            """.trimIndent() + "\n"

        val oneShot = parse(src)
        val streamed = parse(src, chunkSize = 1)
        assertEquals(oneShot, streamed)
    }
}
