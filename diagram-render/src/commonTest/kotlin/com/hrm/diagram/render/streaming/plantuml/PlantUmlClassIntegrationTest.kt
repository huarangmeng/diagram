package com.hrm.diagram.render.streaming.plantuml

import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
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
