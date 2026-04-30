package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NotePlacement
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlantUmlClassParserTest {
    private fun parse(src: String, chunkSize: Int? = null) = PlantUmlClassParser().also { parser ->
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
    }

    @Test
    fun class_and_interface_declarations_are_parsed() {
        val ir = parse("class Animal\ninterface Pet\n").snapshot()
        assertEquals(2, ir.classes.size)
        assertEquals("interface", ir.classes.first { it.id == NodeId("Pet") }.stereotype)
    }

    @Test
    fun abstract_class_with_generics_is_parsed() {
        val ir = parse("abstract class Repository<T>\n").snapshot()
        val c = ir.classes.single()
        assertEquals("Repository", c.name)
        assertEquals("T", c.generics)
        assertEquals("abstract", c.stereotype)
    }

    @Test
    fun multiline_body_members_are_parsed() {
        val ir = parse(
            """
            class User {
              +name: String
              +greet(message: String): Int
            }
            """.trimIndent() + "\n",
        ).snapshot()
        val user = ir.classes.single()
        assertEquals(2, user.members.size)
        assertEquals(Visibility.PUBLIC, user.members[0].visibility)
        assertTrue(user.members[1].isMethod)
        assertEquals("greet", user.members[1].name)
    }

    @Test
    fun dotted_member_form_is_parsed() {
        val ir = parse("User : +age: Int\n").snapshot()
        assertEquals(1, ir.classes.size)
        assertEquals("age", ir.classes[0].members[0].name)
        assertEquals("Int", ir.classes[0].members[0].type)
    }

    @Test
    fun core_relation_kinds_are_parsed() {
        val src =
            """
            Animal <|-- Dog
            House o-- Room
            Order *-- Line
            Service ..> Client
            Api --> Db
            """.trimIndent() + "\n"
        val kinds = parse(src).snapshot().relations.map { it.kind }
        assertEquals(
            listOf(
                ClassRelationKind.Inheritance,
                ClassRelationKind.Aggregation,
                ClassRelationKind.Composition,
                ClassRelationKind.Dependency,
                ClassRelationKind.Association,
            ),
            kinds,
        )
    }

    @Test
    fun note_right_of_class_is_parsed() {
        val ir = parse("class Foo\nnote right of Foo : hello\n").snapshot()
        assertEquals(1, ir.notes.size)
        assertEquals(NodeId("Foo"), ir.notes[0].targetClass)
        assertEquals(NotePlacement.RightOf, ir.notes[0].placement)
        assertEquals(RichLabel.Plain("hello"), ir.notes[0].text)
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            class Animal
            class Dog {
              +bark(): Unit
            }
            Animal <|-- Dog : inherits
            note left of Dog : pet
            """.trimIndent() + "\n"
        val one = parse(src).snapshot()
        val chunked = parse(src, chunkSize = 1).snapshot()
        assertEquals(one, chunked)
    }
}
