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
    fun create_destroy_and_autonumber_resume_are_parsed() {
        val ir = parse(
            """
            autonumber 10 5
            Alice -> Bob: first
            autonumber stop
            create Carol
            Alice -> Carol: born
            autonumber resume 30 3
            Carol -> Bob: sync
            destroy Bob
            Carol -> Bob: bye
            """.trimIndent() + "\n",
        )
        assertEquals("10 first", (ir.messages[0].label as RichLabel.Plain).text)
        assertEquals(MessageKind.Create, ir.messages[1].kind)
        assertEquals("born", (ir.messages[1].label as RichLabel.Plain).text)
        assertEquals("30 sync", (ir.messages[2].label as RichLabel.Plain).text)
        assertEquals(MessageKind.Destroy, ir.messages[3].kind)
        assertEquals("33 bye", (ir.messages[3].label as RichLabel.Plain).text)
    }

    @Test
    fun ref_and_box_are_stored_in_style_hints() {
        val ir = parse(
            """
            box "Clients" LightBlue
            participant Alice
            participant Bob
            end box
            ref over Alice,Bob : Shared flow
            """.trimIndent() + "\n",
        )
        assertTrue(ir.styleHints.extras[PlantUmlSequenceParser.BOXES_KEY].orEmpty().contains("Clients"))
        assertTrue(((ir.messages.single().label as RichLabel.Plain).text).startsWith(PlantUmlSequenceParser.REF_PREFIX))
    }

    @Test
    fun complex_arrow_decorations_are_stored() {
        val ir = parse(
            """
            Alice o-> Bob: tail
            Bob -->>o Carol: stream
            Alice x<- Carol: reject
            """.trimIndent() + "\n",
        )
        val decorations = sequenceDecorations(ir)
        assertEquals(MessageKind.Sync, ir.messages[0].kind)
        assertEquals(MessageKind.Reply, ir.messages[1].kind)
        assertEquals(NodeId("Carol"), ir.messages[2].from)
        assertEquals(NodeId("Alice"), ir.messages[2].to)
        assertEquals(SequenceDecoration("o", null, "filled"), decorations[0])
        assertEquals(SequenceDecoration(null, "o", null), decorations[1])
        assertEquals(SequenceDecoration(null, "x", null), decorations[2])
    }

    @Test
    fun sequence_skinparam_entries_are_stored_in_style_hints() {
        val ir = parse(
            """
            skinparam sequence {
              BackgroundColor LightGray
              BorderColor Peru
              FontColor Navy
              FontSize 15
              FontName serif
              LineThickness 2.5
              Shadowing true
            }
            skinparam participant {
              BackgroundColor LightYellow
              BorderColor Orange
              FontColor Navy
              FontSize 17
              FontName monospace
              LineThickness 2
              Shadowing yes
            }
            skinparam actor {
              BackgroundColor Ivory
              BorderColor SaddleBrown
              FontColor Navy
              FontSize 18
              FontName fantasy
              LineThickness 3
              Shadowing on
            }
            skinparam note {
              BackgroundColor Ivory
              BorderColor Orange
              FontColor SaddleBrown
              FontSize 14
              FontName cursive
              LineThickness 1.75
              Shadowing 1
            }
            skinparam box {
              BackgroundColor PaleGreen
              BorderColor Green
              FontColor Navy
              FontSize 13
              FontName system-ui
              LineThickness 1.5
              Shadowing true
            }
            skinparam ArrowColor Red
            actor Alice
            participant Bob
            Alice -> Bob: hi
            note over Alice,Bob: sync
            """.trimIndent() + "\n",
        )
        assertEquals("LightGray", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_SEQUENCE_FILL_KEY])
        assertEquals("Peru", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_SEQUENCE_STROKE_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_SEQUENCE_TEXT_KEY])
        assertEquals("15", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_SEQUENCE_FONT_SIZE_KEY])
        assertEquals("serif", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_SEQUENCE_FONT_NAME_KEY])
        assertEquals("2.5", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_SEQUENCE_LINE_THICKNESS_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_SEQUENCE_SHADOWING_KEY])
        assertEquals("LightYellow", ir.styleHints.extras[PlantUmlSequenceParser.styleFillKey("participant")])
        assertEquals("Orange", ir.styleHints.extras[PlantUmlSequenceParser.styleStrokeKey("participant")])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlSequenceParser.styleTextKey("participant")])
        assertEquals("17", ir.styleHints.extras[PlantUmlSequenceParser.styleFontSizeKey("participant")])
        assertEquals("monospace", ir.styleHints.extras[PlantUmlSequenceParser.styleFontNameKey("participant")])
        assertEquals("2", ir.styleHints.extras[PlantUmlSequenceParser.styleLineThicknessKey("participant")])
        assertEquals("yes", ir.styleHints.extras[PlantUmlSequenceParser.styleShadowingKey("participant")])
        assertEquals("Ivory", ir.styleHints.extras[PlantUmlSequenceParser.styleFillKey("actor")])
        assertEquals("SaddleBrown", ir.styleHints.extras[PlantUmlSequenceParser.styleStrokeKey("actor")])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlSequenceParser.styleTextKey("actor")])
        assertEquals("18", ir.styleHints.extras[PlantUmlSequenceParser.styleFontSizeKey("actor")])
        assertEquals("fantasy", ir.styleHints.extras[PlantUmlSequenceParser.styleFontNameKey("actor")])
        assertEquals("3", ir.styleHints.extras[PlantUmlSequenceParser.styleLineThicknessKey("actor")])
        assertEquals("on", ir.styleHints.extras[PlantUmlSequenceParser.styleShadowingKey("actor")])
        assertEquals("Ivory", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_NOTE_FILL_KEY])
        assertEquals("Orange", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_NOTE_STROKE_KEY])
        assertEquals("SaddleBrown", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_NOTE_TEXT_KEY])
        assertEquals("14", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_NOTE_FONT_SIZE_KEY])
        assertEquals("cursive", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_NOTE_FONT_NAME_KEY])
        assertEquals("1.75", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_NOTE_LINE_THICKNESS_KEY])
        assertEquals("1", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_NOTE_SHADOWING_KEY])
        assertEquals("PaleGreen", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_BOX_FILL_KEY])
        assertEquals("Green", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_BOX_STROKE_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_BOX_TEXT_KEY])
        assertEquals("13", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_BOX_FONT_SIZE_KEY])
        assertEquals("system-ui", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_BOX_FONT_NAME_KEY])
        assertEquals("1.5", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_BOX_LINE_THICKNESS_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_BOX_SHADOWING_KEY])
        assertEquals("Red", ir.styleHints.extras[PlantUmlSequenceParser.STYLE_EDGE_COLOR_KEY])
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

    private fun sequenceDecorations(ir: SequenceIR): Map<Int, SequenceDecoration> {
        val raw = ir.styleHints.extras[PlantUmlSequenceParser.DECORATIONS_KEY].orEmpty()
        if (raw.isEmpty()) return emptyMap()
        return Regex("""(\d+)\|([^|]*)\|([^|]*)\|([^|]*)""").findAll(raw).associate { match ->
            match.groupValues[1].toInt() to SequenceDecoration(
                tail = match.groupValues[2].ifEmpty { null },
                head = match.groupValues[3].ifEmpty { null },
                headStyle = match.groupValues[4].ifEmpty { null },
            )
        }
    }

    private data class SequenceDecoration(
        val tail: String?,
        val head: String?,
        val headStyle: String?,
    )
}
