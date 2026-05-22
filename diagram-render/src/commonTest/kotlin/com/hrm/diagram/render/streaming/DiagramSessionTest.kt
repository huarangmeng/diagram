package com.hrm.diagram.render.streaming

import com.hrm.diagram.core.draw.DrawCommand
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiagramSessionTest {

    @Test
    fun source_accumulates_across_appends() {
        val s = DiagramSession.create(SourceLanguage.MERMAID, pipeline = NoopSessionPipeline())
        s.append("flowchart TD\n")
        s.append("A --> B\n")
        assertEquals("flowchart TD\nA --> B\n", s.source.toString())
    }

    @Test
    fun seq_strictly_increases_each_advance() {
        val s = DiagramSession.create(SourceLanguage.MERMAID, pipeline = NoopSessionPipeline())
        assertEquals(0L, s.state.value.seq)
        s.append("a"); assertEquals(1L, s.state.value.seq)
        s.append("b"); assertEquals(2L, s.state.value.seq)
        s.finish();    assertEquals(3L, s.state.value.seq)
    }

    @Test
    fun finish_marks_snapshot_final() {
        val s = DiagramSession.create(SourceLanguage.MERMAID, pipeline = NoopSessionPipeline())
        assertEquals(false, s.state.value.isFinal)
        s.finish()
        assertTrue(s.state.value.isFinal)
    }

    @Test
    fun close_blocks_further_writes() {
        val s = DiagramSession.create(SourceLanguage.MERMAID, pipeline = NoopSessionPipeline())
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
    fun noop_pipeline_keeps_snapshot_empty_but_versioned() {
        val s = DiagramSession.create(SourceLanguage.MERMAID, pipeline = NoopSessionPipeline())
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

    @Test
    fun default_language_pipelines_measure_all_draw_text_bounds() {
        val cases = listOf(
            SourceLanguage.MERMAID to "flowchart TD\nA[Alpha] --> B[Beta]\n",
            SourceLanguage.PLANTUML to "@startuml\nAlice -> Bob: hello\n@enduml\n",
            SourceLanguage.DOT to "digraph {\n  a [label=\"Alpha\"];\n  a -> b [label=\"edge\"];\n}\n",
        )
        for ((language, source) in cases) {
            val session = Diagram.session(language)
            try {
                session.append(source)
                session.finish()
                val texts = session.state.value.drawCommands.flatMapTextCommands()
                assertTrue(texts.isNotEmpty(), "$language should render text commands")
                for (text in texts) {
                    assertNotNull(text.measuredBounds, "$language DrawText '${text.text}' must carry measuredBounds")
                }
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun default_language_pipelines_do_not_replay_full_frame_on_idle_append() {
        val cases = listOf(
            SourceLanguage.MERMAID to "flowchart TD\nA[Alpha] --> B[Beta]\n",
            SourceLanguage.MERMAID to "sequenceDiagram\nAlice ->> Bob: hello\n",
            SourceLanguage.MERMAID to "classDiagram\nA <|-- B\n",
            SourceLanguage.MERMAID to "stateDiagram-v2\n[*] --> A\nA --> [*]\n",
            SourceLanguage.MERMAID to "erDiagram\nCUSTOMER ||--o{ ORDER : places\n",
            SourceLanguage.MERMAID to "pie\n  title Pets\n  \"Dogs\" : 3\n  \"Cats\" : 2\n",
            SourceLanguage.MERMAID to "gauge\n  title Coverage\n  value 72\n",
            SourceLanguage.MERMAID to "timeline\n  title Release\n  section Build\n    Parser : done\n",
            SourceLanguage.MERMAID to "gantt\ndateFormat YYYY-MM-DD\nsection S\nTask :a1, 2024-01-01, 1d\n",
            SourceLanguage.MERMAID to "journey\ntitle Day\nsection Work\nTask: 5: Me\n",
            SourceLanguage.MERMAID to "mindmap\n  root((Root))\n    Child\n",
            SourceLanguage.MERMAID to "kanban\n  Todo\n    [Task]\n",
            SourceLanguage.MERMAID to "xychart-beta\n  title \"Sales\"\n  x-axis [Jan, Feb]\n  y-axis \"Revenue\" 0 --> 10\n  line [1, 5]\n",
            SourceLanguage.MERMAID to "quadrantChart\n  title Priority\n  quadrant-1 Do\n  A: [0.7, 0.8]\n",
            SourceLanguage.MERMAID to "sankey-beta\nA,B,3\nB,C,2\n",
            SourceLanguage.MERMAID to "gitGraph\n  commit id: \"a\"\n  branch feature\n  checkout feature\n  commit id: \"b\"\n",
            SourceLanguage.MERMAID to "requirementDiagram\nrequirement req1 {\nid: 1\ntext: must work\nrisk: low\nverifymethod: test\n}\n",
            SourceLanguage.MERMAID to "architecture-beta\nservice api(server)[API]\nservice db(database)[DB]\napi:R --> L:db\n",
            SourceLanguage.MERMAID to "C4Context\ntitle System\nPerson(user, \"User\")\nSystem(app, \"App\")\nRel(user, app, \"Uses\")\n",
            SourceLanguage.MERMAID to "block-beta\ncolumns 2\nA B\nA --> B\n",
            SourceLanguage.MERMAID to "packet-beta\ntitle Header\n0-7: \"Version\"\n8-15: \"Type\"\n",
            SourceLanguage.PLANTUML to "@startuml\nAlice -> Bob: hello\n@enduml\n",
            SourceLanguage.PLANTUML to "@startuml\nclass Animal\nclass Dog\nAnimal <|-- Dog\n@enduml\n",
            SourceLanguage.PLANTUML to "@startuml\nstart\n:Work;\nstop\n@enduml\n",
            SourceLanguage.PLANTUML to "@startuml\n[Web] --> [API]\n@enduml\n",
            SourceLanguage.PLANTUML to "@startuml\nactor User\nUser --> (Login)\n@enduml\n",
            SourceLanguage.PLANTUML to "@startuml\n[*] --> Ready\nReady --> [*]\n@enduml\n",
            SourceLanguage.PLANTUML to "@startuml\nobject User\nUser : name = Bob\n@enduml\n",
            SourceLanguage.PLANTUML to "@startuml\nnode Server\nartifact App\nServer --> App\n@enduml\n",
            SourceLanguage.PLANTUML to "@startuml\nentity CUSTOMER\nentity ORDER\nCUSTOMER ||--o{ ORDER\n@enduml\n",
            SourceLanguage.PLANTUML to "@startmindmap\n* Root\n** Child\n@endmindmap\n",
            SourceLanguage.PLANTUML to "@startwbs\n* Root\n** Child\n@endwbs\n",
            SourceLanguage.PLANTUML to "@startjson\n{\"name\":\"Bob\",\"age\":1}\n@endjson\n",
            SourceLanguage.PLANTUML to "@startyaml\nname: Bob\nage: 1\n@endyaml\n",
            SourceLanguage.PLANTUML to "@startnwdiag\nnwdiag {\n  network dmz {\n    web\n  }\n}\n@endnwdiag\n",
            SourceLanguage.PLANTUML to "@startgantt\n[Task] lasts 1 day\n@endgantt\n",
            SourceLanguage.PLANTUML to "@startuml\nrobust \"User\" as U\n@0\nU is Idle\n@10\nU is Active\n@enduml\n",
            SourceLanguage.PLANTUML to "@startsalt\n{+\n  Login | \"user\"\n}\n@endsalt\n",
            SourceLanguage.PLANTUML to "@startuml\narchimate #Technology \"Service\" as S\n@enduml\n",
            SourceLanguage.PLANTUML to "@startuml\n!include <C4/C4_Context>\nPerson(user, \"User\")\nSystem(app, \"App\")\nRel(user, app, \"Uses\")\n@enduml\n",
            SourceLanguage.PLANTUML to "@startditaa\n+---+\n| A |\n+---+\n@endditaa\n",
            SourceLanguage.PLANTUML to "@startpie\n\"Dogs\" : 3\n\"Cats\" : 2\n@endpie\n",
            SourceLanguage.PLANTUML to "@startbar\nseries S\n1: 2\n2: 4\n@endbar\n",
            SourceLanguage.PLANTUML to "@startline\nseries S\n1: 2\n2: 4\n@endline\n",
            SourceLanguage.PLANTUML to "@startscatter\nseries S\n1: 2\n2: 4\n@endscatter\n",
            SourceLanguage.DOT to "digraph {\n  a [label=\"Alpha\"];\n  a -> b [label=\"edge\"];\n}\n",
        )
        for ((language, source) in cases) {
            val session = Diagram.session(language)
            try {
                session.append(source)
                val frameSize = session.state.value.drawCommands.size
                val idlePatch = session.append("\n")
                assertTrue(frameSize > 0, "$language should render draw commands for:\n$source")
                assertEquals(0, idlePatch.addedDrawCommands.size, "$language idle append must not replay full frame for:\n$source")
            } finally {
                session.close()
            }
        }
    }

    private fun List<DrawCommand>.flatMapTextCommands(): List<DrawCommand.DrawText> =
        flatMap { it.textCommands() }

    private fun DrawCommand.textCommands(): List<DrawCommand.DrawText> =
        when (this) {
            is DrawCommand.DrawText -> listOf(this)
            is DrawCommand.Group -> children.flatMapTextCommands()
            is DrawCommand.Clip -> children.flatMapTextCommands()
            else -> emptyList()
        }
}
