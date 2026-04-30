package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.ActivityBlock
import com.hrm.diagram.core.ir.ActivityIR
import com.hrm.diagram.core.ir.RichLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlActivityParserTest {
    private fun parse(src: String, chunkSize: Int? = null): PlantUmlActivityParser {
        val parser = PlantUmlActivityParser()
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
    fun start_action_stop_are_parsed() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                start
                :Load data;
                stop
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.HAS_START_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.HAS_STOP_KEY])
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Load data")), ir.blocks.single())
    }

    @Test
    fun if_else_block_is_parsed() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                if (ok?) then (yes)
                  :Done;
                else (no)
                  :Retry;
                endif
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val block = assertIs<ActivityBlock.IfElse>(ir.blocks.single())
        assertEquals(RichLabel.Plain("ok?"), block.cond)
        assertEquals(1, block.thenBranch.size)
        assertEquals(1, block.elseBranch.size)
    }

    @Test
    fun while_block_is_parsed() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                while (pending?)
                  :Work;
                endwhile
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val block = assertIs<ActivityBlock.While>(ir.blocks.single())
        assertEquals(RichLabel.Plain("pending?"), block.cond)
        assertEquals(1, block.body.size)
    }

    @Test
    fun note_is_parsed() {
        val ir = parse("note right: explanation\n").snapshot()
        assertTrue(ir.blocks.single() is ActivityBlock.Note)
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            start
            :Step 1;
            if (ok?) then (yes)
              :Done;
            else
              :Retry;
            endif
            stop
            """.trimIndent() + "\n"
        assertEquals(parse(src).snapshot(), parse(src, chunkSize = 1).snapshot())
    }
}
