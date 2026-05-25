package com.hrm.diagram.render.compose

import com.hrm.diagram.core.ir.SourceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagramViewSourceLanguageTest {
    @Test
    fun detects_plantuml_start_directive() {
        assertEquals(
            SourceLanguage.PLANTUML,
            detectSourceLanguage("@startuml\nAlice -> Bob: hello\n@enduml"),
        )
        assertEquals(
            SourceLanguage.PLANTUML,
            detectSourceLanguage("' header comment\n@startuml\nAlice -> Bob: hello\n@enduml"),
        )
    }

    @Test
    fun detects_dot_graph_headers_without_confusing_mermaid_graph() {
        assertEquals(SourceLanguage.DOT, detectSourceLanguage("digraph G {\n  a -> b\n}"))
        assertEquals(SourceLanguage.DOT, detectSourceLanguage("// comment\ndigraph G {\n  a -> b\n}"))
        assertEquals(SourceLanguage.DOT, detectSourceLanguage("strict graph {\n  a -- b\n}"))
        assertEquals(SourceLanguage.MERMAID, detectSourceLanguage("graph TD\n  A --> B"))
    }

    @Test
    fun defaults_to_mermaid_for_empty_or_mermaid_headers() {
        assertEquals(SourceLanguage.MERMAID, detectSourceLanguage(""))
        assertEquals(SourceLanguage.MERMAID, detectSourceLanguage("flowchart LR\nA --> B"))
        assertEquals(SourceLanguage.MERMAID, detectSourceLanguage("sequenceDiagram\nA->>B: hi"))
    }
}
