package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.ir.GraphIR
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidStyleIntegrationTest {
    @Test
    fun frontmatter_and_classDef_do_not_break_streaming_pipeline() {
        val s = com.hrm.diagram.render.Diagram.session(SourceLanguage.MERMAID)
        s.append(
            """
            ---
            config:
              theme: 'base'
              themeVariables:
                primaryColor: red
                fontSize: 16px
            ---
            flowchart LR
              A --> B
              classDef warn fill:#f96,stroke:#333,stroke-width:4px;
            """.trimIndent() + "\n",
        )
        s.finish()
        val snap = s.state.value
        // Ensure the diagram still rendered something (i.e. lexer/parser wasn't derailed).
        assertTrue(snap.drawCommands.isNotEmpty())
        // Named CSS colors are supported (no warning expected).
        assertTrue(snap.diagnostics.none { it.code == "MERMAID-W011" })

        // Style extras should be injected into IR styleHints.
        val ir = assertIs<GraphIR>(snap.ir)
        val extras = ir.styleHints.extras
        assertTrue(extras["mermaid.styleModelVersion"] == "1")
        assertTrue(extras["mermaid.themeTokens"]?.contains("fontSizePx=16") == true)
        assertTrue(extras["mermaid.classDefs"]?.contains("warn|") == true)
    }

    @Test
    fun class_style_linkStyle_affect_flowchart_drawcommands() {
        val s = com.hrm.diagram.render.Diagram.session(SourceLanguage.MERMAID)
        s.append(
            """
            flowchart LR
              A -->|hi| B
              classDef warn fill:#ff0000,stroke:#00ff00,color:#0000ff,stroke-width:3px;
              class A warn
              style B fill:#000000,stroke:#ffffff,stroke-width:4px;
              linkStyle 1 stroke:#123456,stroke-width:2px,stroke-dasharray:5 5,fill:#f5f5f5;
            """.trimIndent() + "\n",
        )
        s.finish()
        val snap = s.state.value
        val ir = assertIs<GraphIR>(snap.ir)
        assertTrue(ir.nodes.any { it.id.value == "A" })
        assertTrue(ir.nodes.any { it.id.value == "B" })
        assertTrue(ir.edges.isNotEmpty())

        val fills = snap.drawCommands
            .filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.FillRect>()
            .map { it.color.argb.toLong() and 0xFFFFFFFFL }
            .toSet()
        // Node A from classDef warn.
        assertTrue(fills.contains(0xFFFF0000L), "expected #ff0000 fill from classDef (got: $fills)")
        // Node B from inline style.
        assertTrue(fills.contains(0xFF000000L), "expected #000000 fill from style (got: $fills)")
        // Edge label chip from linkStyle fill -> EdgeStyle.labelBg.
        assertTrue(fills.contains(0xFFF5F5F5L), "expected #f5f5f5 chip fill from linkStyle (got: $fills)")

        val strokes = snap.drawCommands
            .filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.StrokePath>()
        assertTrue(strokes.any { it.color.argb.toLong() and 0xFFFFFFFFL == 0xFF123456L }, "expected edge stroke #123456")
        assertTrue(strokes.any { it.stroke.dash == listOf(5f, 5f) }, "expected edge dash [5,5]")
    }
}
