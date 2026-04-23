package com.hrm.diagram.render.streaming

import com.hrm.diagram.core.ir.SourceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiagramSessionTest {

    @Test
    fun source_accumulates_across_appends() {
        val s = DiagramSession.create(SourceLanguage.MERMAID)
        s.append("flowchart TD\n")
        s.append("A --> B\n")
        assertEquals("flowchart TD\nA --> B\n", s.source.toString())
    }

    @Test
    fun seq_strictly_increases_each_advance() {
        val s = DiagramSession.create(SourceLanguage.MERMAID)
        assertEquals(0L, s.state.value.seq)
        s.append("a"); assertEquals(1L, s.state.value.seq)
        s.append("b"); assertEquals(2L, s.state.value.seq)
        s.finish();    assertEquals(3L, s.state.value.seq)
    }

    @Test
    fun finish_marks_snapshot_final() {
        val s = DiagramSession.create(SourceLanguage.MERMAID)
        assertEquals(false, s.state.value.isFinal)
        s.finish()
        assertTrue(s.state.value.isFinal)
    }

    @Test
    fun close_blocks_further_writes() {
        val s = DiagramSession.create(SourceLanguage.MERMAID)
        s.append("hi")
        s.close()
        assertFailsWith<IllegalStateException> { s.append("x") }
        assertFailsWith<IllegalStateException> { s.finish() }
    }

    @Test
    fun pipeline_receives_correct_offset() {
        val recorded = mutableListOf<Triple<Int, Long, Boolean>>()
        val capture = object : SessionPipeline {
            override fun advance(
                previousSnapshot: DiagramSnapshot,
                chunk: CharSequence,
                absoluteOffset: Int,
                seq: Long,
                isFinal: Boolean,
            ): PipelineAdvance {
                recorded += Triple(absoluteOffset, seq, isFinal)
                return PipelineAdvance(
                    snapshot = previousSnapshot.copy(seq = seq, isFinal = isFinal),
                    patch = SessionPatch.empty(seq, isFinal),
                )
            }
        }
        val s = DiagramSession.create(SourceLanguage.MERMAID, pipeline = capture)
        s.append("abc")   // offset 0
        s.append("de")    // offset 3
        s.finish()        // offset 5, isFinal
        assertEquals(
            listOf(
                Triple(0, 1L, false),
                Triple(3, 2L, false),
                Triple(5, 3L, true),
            ),
            recorded,
        )
    }

    @Test
    fun stub_pipeline_keeps_snapshot_empty_but_versioned() {
        val s = DiagramSession.create(SourceLanguage.MERMAID)
        s.append("anything")
        val snap = s.state.value
        assertEquals(1L, snap.seq)
        assertTrue(snap.drawCommands.isEmpty())
        assertTrue(snap.diagnostics.isEmpty())
    }

    @Test
    fun session_patch_isEmpty_reports_correctly() {
        assertTrue(SessionPatch.empty(0L).isEmpty)
    }

    @Test
    fun facade_session_factory_returns_session() {
        val s = com.hrm.diagram.render.Diagram.session(SourceLanguage.DOT)
        assertEquals(SourceLanguage.DOT, s.language)
        assertSame(s.state.value.sourceLanguage, SourceLanguage.DOT)
    }
}
