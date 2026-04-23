package com.hrm.diagram.parser.mermaid

import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.NotePlacement
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.Visibility
import com.hrm.diagram.core.streaming.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MermaidClassParserTest {

    private fun feed(src: String): MermaidClassParser {
        val parser = MermaidClassParser()
        val lexer = MermaidLexer()
        val toks = lexer.feed(lexer.initialState(), src, 0, eos = true).tokens
        // Group tokens into lines.
        val lines = ArrayList<MutableList<Token>>()
        var cur = ArrayList<Token>()
        for (t in toks) {
            if (t.kind == MermaidTokenKind.NEWLINE) {
                if (cur.isNotEmpty()) { lines += cur; cur = ArrayList() }
            } else cur.add(t)
        }
        if (cur.isNotEmpty()) lines += cur
        for (l in lines) parser.acceptLine(l)
        return parser
    }

    @Test fun header_required() {
        val p = MermaidClassParser()
        val batch = p.acceptLine(listOf(Token(MermaidTokenKind.IDENT, 0, 1, "X")))
        assertTrue(batch.patches.isNotEmpty())
        assertTrue(p.diagnosticsSnapshot().any { it.message.contains("classDiagram") })
    }

    @Test fun header_then_class_decl() {
        val p = feed("classDiagram\nclass Foo\n")
        val ir = p.snapshot()
        assertEquals(1, ir.classes.size)
        assertEquals("Foo", ir.classes[0].name)
    }

    @Test fun class_with_inline_empty_body() {
        val p = feed("classDiagram\nclass Foo { }\n")
        assertEquals(1, p.snapshot().classes.size)
    }

    @Test fun class_with_multiline_body_attribute() {
        val p = feed("classDiagram\nclass Foo {\n+int age\n}\n")
        val c = p.snapshot().classes[0]
        assertEquals(1, c.members.size)
        assertEquals("age", c.members[0].name)
        assertEquals(Visibility.PUBLIC, c.members[0].visibility)
        assertEquals("int", c.members[0].type)
        assertTrue(!c.members[0].isMethod)
    }

    @Test fun class_with_method_member() {
        val p = feed("classDiagram\nclass Foo {\n+greet() String\n}\n")
        val c = p.snapshot().classes[0]
        assertEquals(1, c.members.size)
        assertTrue(c.members[0].isMethod)
        assertEquals("greet", c.members[0].name)
        assertEquals("String", c.members[0].type)
    }

    @Test fun class_with_method_params() {
        val p = feed("classDiagram\nclass Foo {\n+add(a: Int, b: Int) Int\n}\n")
        val c = p.snapshot().classes[0]
        val m = c.members[0]
        assertEquals(2, m.params.size)
        assertEquals("a", m.params[0].name)
        assertEquals("Int", m.params[0].type)
    }

    @Test fun class_static_member_dollar() {
        val p = feed("classDiagram\nclass Foo {\n+count() Int $\n}\n")
        val m = p.snapshot().classes[0].members[0]
        assertTrue(m.isStatic)
    }

    @Test fun class_abstract_member_asterisk() {
        val p = feed("classDiagram\nclass Foo {\n+render() void *\n}\n")
        val m = p.snapshot().classes[0].members[0]
        assertTrue(m.isAbstract)
    }

    @Test fun stereotype_in_body() {
        val p = feed("classDiagram\nclass Foo {\n<<interface>>\n}\n")
        assertEquals("interface", p.snapshot().classes[0].stereotype)
    }

    @Test fun generics_parsed() {
        val p = feed("classDiagram\nclass List~T~\n")
        assertEquals("T", p.snapshot().classes[0].generics)
    }

    @Test fun css_class_assignment_triple_colon() {
        val p = feed("classDiagram\nclass Foo:::warn\n")
        assertEquals("warn", p.snapshot().classes[0].cssClass)
    }

    @Test fun relation_inheritance() {
        val p = feed("classDiagram\nA <|-- B\n")
        val ir = p.snapshot()
        assertEquals(1, ir.relations.size)
        assertEquals(ClassRelationKind.Inheritance, ir.relations[0].kind)
    }

    @Test fun relation_composition() {
        val p = feed("classDiagram\nA *-- B\n")
        assertEquals(ClassRelationKind.Composition, p.snapshot().relations[0].kind)
    }

    @Test fun relation_aggregation() {
        val p = feed("classDiagram\nA o-- B\n")
        assertEquals(ClassRelationKind.Aggregation, p.snapshot().relations[0].kind)
    }

    @Test fun relation_association() {
        val p = feed("classDiagram\nA --> B\n")
        assertEquals(ClassRelationKind.Association, p.snapshot().relations[0].kind)
    }

    @Test fun relation_dependency() {
        val p = feed("classDiagram\nA ..> B\n")
        assertEquals(ClassRelationKind.Dependency, p.snapshot().relations[0].kind)
    }

    @Test fun relation_realization() {
        val p = feed("classDiagram\nA ..|> B\n")
        assertEquals(ClassRelationKind.Realization, p.snapshot().relations[0].kind)
    }

    @Test fun relation_link_solid() {
        val p = feed("classDiagram\nA -- B\n")
        assertEquals(ClassRelationKind.Link, p.snapshot().relations[0].kind)
    }

    @Test fun relation_link_dashed() {
        val p = feed("classDiagram\nA .. B\n")
        assertEquals(ClassRelationKind.LinkDashed, p.snapshot().relations[0].kind)
    }

    @Test fun relation_with_cardinalities() {
        val p = feed("classDiagram\nA \"1\" --> \"many\" B\n")
        val r = p.snapshot().relations[0]
        assertEquals("1", r.fromCardinality)
        assertEquals("many", r.toCardinality)
    }

    @Test fun relation_with_label() {
        val p = feed("classDiagram\nA --> B : uses\n")
        val r = p.snapshot().relations[0]
        val label = r.label as RichLabel.Plain
        assertTrue(label.text.contains("uses"))
    }

    @Test fun dotted_member_form() {
        val p = feed("classDiagram\nFoo : +greet() String\n")
        val c = p.snapshot().classes[0]
        assertEquals("Foo", c.name)
        assertEquals(1, c.members.size)
        assertTrue(c.members[0].isMethod)
    }

    @Test fun namespace_groups_classes() {
        val p = feed("classDiagram\nnamespace MyNs {\nclass A\nclass B\n}\n")
        val ir = p.snapshot()
        assertEquals(2, ir.classes.size)
        assertEquals(1, ir.namespaces.size)
        assertEquals(2, ir.namespaces[0].members.size)
    }

    @Test fun note_standalone() {
        val p = feed("classDiagram\nnote \"hello world\"\n")
        val ir = p.snapshot()
        assertEquals(1, ir.notes.size)
        assertNull(ir.notes[0].targetClass)
        assertEquals(NotePlacement.Standalone, ir.notes[0].placement)
    }

    @Test fun note_for_class() {
        val p = feed("classDiagram\nclass Foo\nnote for Foo \"this is foo\"\n")
        val ir = p.snapshot()
        assertEquals(NodeId("Foo"), ir.notes[0].targetClass)
    }

    @Test fun note_left_of_class_uses_left_placement() {
        val p = feed("classDiagram\nclass Foo\nnote left of Foo : on the left\n")
        val ir = p.snapshot()
        assertEquals(1, ir.notes.size)
        assertEquals(NodeId("Foo"), ir.notes[0].targetClass)
        assertEquals(NotePlacement.LeftOf, ir.notes[0].placement)
        assertEquals("on the left", (ir.notes[0].text as com.hrm.diagram.core.ir.RichLabel.Plain).text)
    }

    @Test fun note_right_of_class_uses_right_placement() {
        val p = feed("classDiagram\nclass Foo\nnote right of Foo : right side\n")
        val ir = p.snapshot()
        assertEquals(NotePlacement.RightOf, ir.notes[0].placement)
    }

    @Test fun note_top_of_class_uses_top_placement() {
        val p = feed("classDiagram\nclass Foo\nnote top of Foo : up top\n")
        val ir = p.snapshot()
        assertEquals(NotePlacement.TopOf, ir.notes[0].placement)
    }

    @Test fun note_bottom_of_class_uses_bottom_placement() {
        val p = feed("classDiagram\nclass Foo\nnote bottom of Foo : down low\n")
        val ir = p.snapshot()
        assertEquals(NotePlacement.BottomOf, ir.notes[0].placement)
    }

    @Test fun note_directional_with_quoted_text() {
        val p = feed("classDiagram\nclass Foo\nnote left of Foo \"quoted body\"\n")
        val ir = p.snapshot()
        assertEquals(NotePlacement.LeftOf, ir.notes[0].placement)
        assertEquals("quoted body", (ir.notes[0].text as com.hrm.diagram.core.ir.RichLabel.Plain).text)
    }

    @Test fun css_class_definition() {
        val p = feed("classDiagram\ncssClass \"A,B\" warn\n")
        val ir = p.snapshot()
        assertEquals(1, ir.cssClasses.size)
        assertEquals("A,B", ir.cssClasses[0].name)
        assertEquals("warn", ir.cssClasses[0].style)
    }

    @Test fun direction_lr() {
        val p = feed("classDiagram\ndirection LR\nclass A\n")
        val d = p.snapshot().styleHints.direction
        assertNotNull(d)
    }

    @Test fun classes_auto_created_from_relation() {
        val p = feed("classDiagram\nA --> B\n")
        assertEquals(2, p.snapshot().classes.size)
    }
}
