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
        // Non-hex themeVariables color should be surfaced as a warning.
        assertTrue(snap.diagnostics.any { it.code == "MERMAID-W011" })

        // Style extras should be injected into IR styleHints.
        val ir = assertIs<GraphIR>(snap.ir)
        val extras = ir.styleHints.extras
        assertTrue(extras["mermaid.styleModelVersion"] == "1")
        assertTrue(extras["mermaid.themeTokens"]?.contains("fontSizePx=16") == true)
        assertTrue(extras["mermaid.classDefs"]?.contains("warn|") == true)
    }
}
