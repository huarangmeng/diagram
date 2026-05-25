package com.hrm.diagram.render

import com.hrm.diagram.core.ir.SourceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagramSourceDetectorTest {
    @Test
    fun code_fence_hint_wins_before_body_exists() {
        val detection = Diagram.detectSource("", hint = "mermaid")
        assertTrue(detection.isDiagram)
        assertEquals(SourceLanguage.MERMAID, detection.language)
    }

    @Test
    fun detects_headers_without_hint() {
        assertEquals(SourceLanguage.MERMAID, Diagram.detectSource("flowchart LR\nA --> B").language)
        assertEquals(SourceLanguage.PLANTUML, Diagram.detectSource("@startuml\nA -> B\n@enduml").language)
        assertEquals(SourceLanguage.DOT, Diagram.detectSource("digraph G {\n  a -> b\n}").language)
    }

    @Test
    fun does_not_route_arbitrary_markdown_to_diagram() {
        val detection = Diagram.detectSource("# Title\n\nThis is prose.")
        assertFalse(detection.shouldRouteToDiagram)
        assertEquals(null, detection.language)

        val prose = Diagram.detectSource("digraphy is not a DOT keyword")
        assertFalse(prose.shouldRouteToDiagram)
        assertEquals(null, prose.language)
    }

    @Test
    fun pending_dot_graph_prefix_can_be_reserved_by_streaming_containers() {
        val detection = Diagram.detectSource("graph G")
        assertTrue(detection.isPending)
        assertTrue(detection.shouldRouteToDiagram)
        assertEquals(SourceLanguage.DOT, detection.language)
    }
}
