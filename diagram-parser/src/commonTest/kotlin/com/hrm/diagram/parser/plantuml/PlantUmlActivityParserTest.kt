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

    private fun visibleBlocks(ir: ActivityIR): List<ActivityBlock> = visibleBlocks(ir.blocks)

    private fun visibleBlocks(blocks: List<ActivityBlock>): List<ActivityBlock> =
        blocks.mapNotNull(::visibleBlock)

    private fun visibleBlock(block: ActivityBlock): ActivityBlock? = when (block) {
        is ActivityBlock.Action -> block
        is ActivityBlock.Note -> {
            val text = (block.text as? RichLabel.Plain)?.text.orEmpty()
            if (text.startsWith(PlantUmlActivityParser.SWIMLANE_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.ACTION_STYLE_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.EDGE_LABEL_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.SYNC_BAR_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.NODE_REF_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.EDGE_SOURCE_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.EDGE_TARGET_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.EDGE_STOP_PREFIX)
            ) {
                null
            } else {
                block
            }
        }
        is ActivityBlock.IfElse -> block.copy(
            thenBranch = visibleBlocks(block.thenBranch),
            elseBranch = visibleBlocks(block.elseBranch),
        )
        is ActivityBlock.While -> block.copy(body = visibleBlocks(block.body))
        is ActivityBlock.ForkJoin -> block.copy(branches = block.branches.map(::visibleBlocks))
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
        val visible = visibleBlocks(ir)
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.HAS_START_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.HAS_STOP_KEY])
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Load data")), visible.single())
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
        val block = assertIs<ActivityBlock.IfElse>(visibleBlocks(ir).single())
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
        val block = assertIs<ActivityBlock.While>(visibleBlocks(ir).single())
        assertEquals(RichLabel.Plain("pending?"), block.cond)
        assertEquals(1, block.body.size)
    }

    @Test
    fun elseif_chain_is_parsed_as_nested_ifelse() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                if (a?) then (yes)
                  :A;
                elseif (b?) then (yes)
                  :B;
                else (no)
                  :C;
                endif
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val root = assertIs<ActivityBlock.IfElse>(visibleBlocks(ir).single())
        assertEquals(RichLabel.Plain("a?"), root.cond)
        val nested = assertIs<ActivityBlock.IfElse>(root.elseBranch.single())
        assertEquals(RichLabel.Plain("b?"), nested.cond)
        assertEquals(1, nested.elseBranch.size)
    }

    @Test
    fun repeat_block_is_encoded_as_repeat_condition() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                repeat
                  :Work;
                repeat while (more?)
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val block = assertIs<ActivityBlock.While>(visibleBlocks(ir).single())
        assertEquals(RichLabel.Plain("${PlantUmlActivityParser.REPEAT_PREFIX}more?"), block.cond)
        assertEquals(1, block.body.size)
    }

    @Test
    fun fork_join_is_parsed() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                fork
                  :A;
                fork again
                  :B;
                end fork
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val block = assertIs<ActivityBlock.ForkJoin>(visibleBlocks(ir).single())
        assertEquals(2, block.branches.size)
        assertEquals(1, block.branches[0].size)
        assertEquals(1, block.branches[1].size)
    }

    @Test
    fun swimlane_markers_are_preserved() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                |Customer|
                :Order;
                |System|
                :Validate;
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.SWIMLANE_PREFIX}Customer")), ir.blocks[0])
        assertTrue(ir.blocks.any { it == ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.SWIMLANE_PREFIX}System")) })
    }

    @Test
    fun note_is_parsed() {
        val ir = parse("note right: explanation\n").snapshot()
        assertTrue(ir.blocks.single() is ActivityBlock.Note)
    }

    @Test
    fun multiline_note_and_partition_color_are_preserved() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                partition Ops #LightSkyBlue {
                note right
                  first line
                  second line
                end note
                :Deploy;
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val visible = visibleBlocks(ir)
        assertEquals(
            ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.SWIMLANE_PREFIX}Ops|||#LightSkyBlue")),
            ir.blocks[0],
        )
        assertEquals(ActivityBlock.Note(RichLabel.Plain("first line\nsecond line")), visible[0])
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Deploy")), visible[1])
    }

    @Test
    fun legacy_arrow_and_legacy_if_are_parsed() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                (*) --> "First Action"
                -->[ok] if "Ready?" then
                  -right-> "Ship"
                else
                  --> "Wait"
                endif
                --> (*)
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val visible = visibleBlocks(ir)
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.HAS_START_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.HAS_STOP_KEY])
        assertEquals(ActivityBlock.Action(RichLabel.Plain("First Action")), visible[0])
        assertTrue(
            ir.blocks.any { it == ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.EDGE_LABEL_PREFIX}ok")) },
        )
        val branch = assertIs<ActivityBlock.IfElse>(visible[1])
        assertEquals(RichLabel.Plain("Ready?"), branch.cond)
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Ship")), branch.thenBranch.single())
    }

    @Test
    fun styled_action_marker_is_preserved() {
        val ir = parse("#palegreen:Done;\n").snapshot()
        assertEquals(ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.ACTION_STYLE_PREFIX}#palegreen")), ir.blocks[0])
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Done")), visibleBlocks(ir).single())
    }

    @Test
    fun sync_bar_marker_is_preserved() {
        val ir = parse("=== phase 1 ===\n").snapshot()
        assertTrue(ir.blocks.any { it == ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.SYNC_BAR_PREFIX}phase 1")) })
    }

    @Test
    fun legacy_alias_and_sync_references_are_preserved() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                (*) --> "First Action" as A1
                --> ===B1===
                ===B1=== --> "Second Action"
                A1 --> (*)
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val texts = ir.blocks.mapNotNull { (it as? ActivityBlock.Note)?.text as? RichLabel.Plain }.map { it.text }
        assertTrue(texts.any { it == "${PlantUmlActivityParser.NODE_REF_PREFIX}name:A1" })
        assertTrue(texts.any { it == "${PlantUmlActivityParser.EDGE_SOURCE_PREFIX}sync:B1" })
        assertTrue(texts.any { it == "${PlantUmlActivityParser.EDGE_STOP_PREFIX}name:A1" })
    }

    @Test
    fun activity_skinparam_block_and_direct_keys_are_stored_in_style_hints() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                skinparam activity {
                  StartColor red
                  BarColor SaddleBrown
                  BackgroundColor Peru
                  BorderColor Peru
                  DiamondBackgroundColor PaleGreen
                  DiamondBorderColor Green
                }
                skinparam ArrowColor Navy
                :Work;
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals("red", ir.styleHints.extras[PlantUmlActivityParser.STYLE_START_FILL_KEY])
        assertEquals("SaddleBrown", ir.styleHints.extras[PlantUmlActivityParser.STYLE_BAR_FILL_KEY])
        assertEquals("Peru", ir.styleHints.extras[PlantUmlActivityParser.STYLE_ACTION_FILL_KEY])
        assertEquals("Green", ir.styleHints.extras[PlantUmlActivityParser.STYLE_DECISION_STROKE_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlActivityParser.STYLE_EDGE_COLOR_KEY])
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
