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
    fun quoted_alias_declaration_is_parsed() {
        val ir = parse("class \"User Service\" as UserService\n").snapshot()
        val c = ir.classes.single()
        assertEquals(NodeId("UserService"), c.id)
        assertEquals("User Service", c.name)
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
            Service --|> Api
            House o-- Room
            Order *-- Line
            Service ..> Client
            Client <.. Http
            Api --> Db
            """.trimIndent() + "\n"
        val rels = parse(src).snapshot().relations
        assertEquals(
            listOf(
                ClassRelationKind.Inheritance,
                ClassRelationKind.Inheritance,
                ClassRelationKind.Aggregation,
                ClassRelationKind.Composition,
                ClassRelationKind.Dependency,
                ClassRelationKind.Dependency,
                ClassRelationKind.Association,
            ),
            rels.map { it.kind },
        )
        assertEquals(NodeId("Dog"), rels[0].from)
        assertEquals(NodeId("Animal"), rels[0].to)
        assertEquals(NodeId("Service"), rels[1].from)
        assertEquals(NodeId("Api"), rels[1].to)
        assertEquals(NodeId("Room"), rels[2].from)
        assertEquals(NodeId("House"), rels[2].to)
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
    fun multiline_note_block_and_package_are_parsed() {
        val ir = parse(
            """
            package Domain {
              class "User Service" as UserService
              class Repository
            }
            note left of UserService
              first line
              second line
            end note
            """.trimIndent() + "\n",
        ).snapshot()
        assertEquals(1, ir.namespaces.size)
        assertEquals("Domain", ir.namespaces.single().id)
        assertEquals(2, ir.namespaces.single().members.size)
        assertEquals(1, ir.notes.size)
        assertEquals(NodeId("UserService"), ir.notes.single().targetClass)
        assertEquals(RichLabel.Plain("first line\nsecond line"), ir.notes.single().text)
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

    @Test
    fun class_skinparam_entries_are_stored_in_style_hints() {
        val ir = parse(
            """
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
            """.trimIndent() + "\n",
        ).snapshot()
        assertEquals("LightYellow", ir.styleHints.extras[PlantUmlClassParser.STYLE_CLASS_FILL_KEY])
        assertEquals("Orange", ir.styleHints.extras[PlantUmlClassParser.STYLE_CLASS_STROKE_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlClassParser.STYLE_CLASS_TEXT_KEY])
        assertEquals("Ivory", ir.styleHints.extras[PlantUmlClassParser.STYLE_NOTE_FILL_KEY])
        assertEquals("Peru", ir.styleHints.extras[PlantUmlClassParser.STYLE_NOTE_STROKE_KEY])
        assertEquals("Red", ir.styleHints.extras[PlantUmlClassParser.STYLE_NOTE_TEXT_KEY])
        assertEquals("15", ir.styleHints.extras[PlantUmlClassParser.STYLE_NOTE_FONT_SIZE_KEY])
        assertEquals("serif", ir.styleHints.extras[PlantUmlClassParser.STYLE_NOTE_FONT_NAME_KEY])
        assertEquals("2", ir.styleHints.extras[PlantUmlClassParser.STYLE_NOTE_LINE_THICKNESS_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlClassParser.STYLE_NOTE_SHADOWING_KEY])
        assertEquals("LightGray", ir.styleHints.extras[PlantUmlClassParser.STYLE_PACKAGE_FILL_KEY])
        assertEquals("Silver", ir.styleHints.extras[PlantUmlClassParser.STYLE_PACKAGE_STROKE_KEY])
        assertEquals("Green", ir.styleHints.extras[PlantUmlClassParser.STYLE_PACKAGE_TEXT_KEY])
        assertEquals("17", ir.styleHints.extras[PlantUmlClassParser.STYLE_CLASS_FONT_SIZE_KEY])
        assertEquals("monospace", ir.styleHints.extras[PlantUmlClassParser.STYLE_CLASS_FONT_NAME_KEY])
        assertEquals("2.5", ir.styleHints.extras[PlantUmlClassParser.STYLE_CLASS_LINE_THICKNESS_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlClassParser.STYLE_CLASS_SHADOWING_KEY])
        assertEquals("16", ir.styleHints.extras[PlantUmlClassParser.STYLE_PACKAGE_FONT_SIZE_KEY])
        assertEquals("sans-serif", ir.styleHints.extras[PlantUmlClassParser.STYLE_PACKAGE_FONT_NAME_KEY])
        assertEquals("2.25", ir.styleHints.extras[PlantUmlClassParser.STYLE_PACKAGE_LINE_THICKNESS_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlClassParser.STYLE_PACKAGE_SHADOWING_KEY])
        assertEquals("Blue", ir.styleHints.extras[PlantUmlClassParser.STYLE_EDGE_COLOR_KEY])
    }
}
