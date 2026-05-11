package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.parser.plantuml.PlantUmlClassParser
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlantUmlClassIntegrationTest {
    @Test
    fun class_diagram_renders_and_is_streaming_consistent() {
        val src =
            """
            @startuml
            abstract class Animal
            class Dog {
              +bark(): Unit
            }
            Animal <|-- Dog
            note right of Dog : pet
            @enduml
            """.trimIndent() + "\n"

        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = one.ir as? ClassIR
        val chunkedIr = chunked.ir as? ClassIR
        assertNotNull(oneIr)
        assertNotNull(chunkedIr)
        assertEquals(oneIr, chunkedIr)
        assertTrue(one.drawCommands.isNotEmpty())
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    @Test
    fun class_relation_generates_edge_route() {
        val snapshot = run(
            """
            @startuml
            Animal <|-- Dog
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 5,
        )
        val ir = snapshot.ir as? ClassIR
        assertNotNull(ir)
        assertEquals(ClassRelationKind.Inheritance, ir.relations.single().kind)
        assertEquals(1, snapshot.laidOut?.edgeRoutes?.size)
    }

    @Test
    fun dotted_member_and_note_generate_clusters() {
        val snapshot = run(
            """
            @startuml
            class User
            User : +name: String
            note top of User : highlighted
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 3,
        )
        val ir = snapshot.ir as? ClassIR
        assertNotNull(ir)
        assertEquals(1, ir.classes.single().members.size)
        assertNotNull(snapshot.laidOut?.clusterRects?.get(NodeId("note#0")))
    }

    @Test
    fun package_alias_and_multiline_note_render_without_dispatch_errors() {
        val snapshot = run(
            """
            @startuml
            package Domain {
              class "User Service" as UserService
              class Repository
              UserService --|> Repository
            }
            note left of UserService
              Handles user-facing orchestration
              And keeps service boundary explicit
            end note
            @enduml
            """.trimIndent() + "\n",
            chunkSize = 4,
        )
        val ir = snapshot.ir as? ClassIR
        assertNotNull(ir)
        assertTrue(snapshot.diagnostics.isEmpty(), "diagnostics: ${snapshot.diagnostics}")
        assertEquals("User Service", ir.classes.first { it.id == NodeId("UserService") }.name)
        assertEquals(1, ir.namespaces.size)
        val laidOut = assertNotNull(snapshot.laidOut)
        assertNotNull(laidOut.clusterRects[NodeId("ns#Domain")])
        assertNotNull(laidOut.clusterRects[NodeId("note#0")])
    }

    @Test
    fun class_skinparam_styles_render_consistently() {
        val src =
            """
            @startuml
            skinparam class {
              BackgroundColor LightYellow
              BorderColor Orange
              FontColor Navy
              FontSize 17
              FontName monospace
              LineThickness 2.5
              Shadowing true
            }
            skinparam note {
              BackgroundColor Ivory
              BorderColor Peru
              FontColor Red
              FontSize 15
              FontName serif
              LineThickness 2
              Shadowing true
            }
            skinparam package {
              BackgroundColor LightGray
              BorderColor Silver
              FontColor Green
              FontSize 16
              FontName sans-serif
              LineThickness 2.25
              Shadowing true
            }
            skinparam ArrowColor Blue
            package Domain {
              class User
            }
            note right of User : hello
            User --> User : self
            @enduml
            """.trimIndent() + "\n"
        val one = run(src, src.length)
        val chunked = run(src, 4)
        val oneIr = one.ir as? ClassIR
        val chunkedIr = chunked.ir as? ClassIR
        assertNotNull(oneIr)
        assertNotNull(chunkedIr)
        assertEquals(oneIr, chunkedIr)
        assertEquals("LightYellow", oneIr.styleHints.extras[PlantUmlClassParser.STYLE_CLASS_FILL_KEY])
        val fillRects = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.FillRect>().map { it.color.argb }
        val strokeRects = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.StrokeRect>()
        val strokePaths = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.StrokePath>()
        val texts = one.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.DrawText>()
        val textColors = texts.map { it.color.argb }
        val textFamilies = texts.map { it.font.family }
        val textSizes = texts.map { it.font.sizeSp }
        assertTrue(fillRects.contains(0xFFFFFFE0.toInt()))
        assertTrue(fillRects.contains(0xFFD3D3D3.toInt()))
        assertTrue(fillRects.contains(0xFFFFFFF0.toInt()))
        assertTrue(fillRects.contains(0x26000000))
        assertTrue(strokeRects.any { it.color.argb == 0xFFFFA500.toInt() && it.stroke.width == 2.5f })
        assertTrue(strokeRects.any { it.color.argb == 0xFFC0C0C0.toInt() && it.stroke.width == 2.25f })
        assertTrue(strokeRects.any { it.color.argb == 0xFFCD853F.toInt() && it.stroke.width == 2f })
        assertTrue(strokePaths.any { it.color.argb == 0xFF0000FF.toInt() && it.stroke.width == 2.5f })
        assertTrue(textColors.contains(0xFF000080.toInt()))
        assertTrue(textColors.contains(0xFFFF0000.toInt()))
        assertTrue(textColors.contains(0xFF008000.toInt()))
        assertTrue(textFamilies.contains("monospace"))
        assertTrue(textFamilies.contains("serif"))
        assertTrue(textFamilies.contains("sans-serif"))
        assertTrue(textSizes.contains(17f))
        assertTrue(textSizes.contains(15f))
        assertTrue(textSizes.contains(16f))
        assertTrue(one.diagnostics.isEmpty(), "one-shot diagnostics: ${one.diagnostics}")
        assertTrue(chunked.diagnostics.isEmpty(), "chunked diagnostics: ${chunked.diagnostics}")
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(SourceLanguage.PLANTUML).let { s ->
        try {
            var i = 0
            while (i < src.length) {
                val end = (i + chunkSize).coerceAtMost(src.length)
                s.append(src.substring(i, end))
                i = end
            }
            s.finish()
        } finally {
            s.close()
        }
    }
}
