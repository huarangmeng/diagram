package com.hrm.diagram.render.streaming.mermaid

import com.hrm.diagram.core.ir.ClassIR
import com.hrm.diagram.core.ir.ClassRelationKind
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.render.Diagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MermaidClassSubPipelineTest {

    @Test fun simple_class_diagram_produces_ir_and_drawcommands() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("classDiagram\nclass Foo\n")
            val snap = s.finish()
            val ir = assertNotNull(snap.ir as? ClassIR)
            assertEquals(1, ir.classes.size)
            assertTrue(snap.drawCommands.isNotEmpty())
        } finally { s.close() }
    }

    @Test fun two_classes_with_inheritance_have_edge_route() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("classDiagram\nA <|-- B\n")
            val snap = s.finish()
            val ir = snap.ir as ClassIR
            assertEquals(ClassRelationKind.Inheritance, ir.relations[0].kind)
            val laid = assertNotNull(snap.laidOut)
            assertEquals(1, laid.edgeRoutes.size)
        } finally { s.close() }
    }

    @Test fun class_with_inline_members_renders_more_text() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("classDiagram\nclass Foo {\n+greet() String\n+age Int\n}\n")
            val snap = s.finish()
            val ir = snap.ir as ClassIR
            assertEquals(2, ir.classes[0].members.size)
            assertTrue(snap.drawCommands.size > 5)
        } finally { s.close() }
    }

    @Test fun streaming_in_chunks_yields_same_classes() {
        val src = "classDiagram\nclass A\nclass B\nA --> B\n"
        val one = run(src, src.length)
        val many = run(src, 3)
        val a = one.ir as ClassIR
        val b = many.ir as ClassIR
        assertEquals(a.classes.map { it.id }, b.classes.map { it.id })
        assertEquals(a.relations.map { it.from to it.to }, b.relations.map { it.from to it.to })
    }

    @Test fun namespace_creates_cluster_rect() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("classDiagram\nnamespace Pkg {\nclass A\nclass B\n}\n")
            val snap = s.finish()
            val laid = assertNotNull(snap.laidOut)
            assertNotNull(laid.clusterRects[NodeId("ns#Pkg")])
        } finally { s.close() }
    }

    @Test fun note_for_class_drawn() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("classDiagram\nclass Foo\nnote for Foo \"hi\"\n")
            val snap = s.finish()
            val laid = assertNotNull(snap.laidOut)
            assertNotNull(laid.clusterRects[NodeId("note#0")])
        } finally { s.close() }
    }

    @Test fun rendered_dividers_and_text_stay_within_class_box() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("classDiagram\nAnimal <|-- Dog\nAnimal : +String name\nDog : +bark()\nclass Cat {\n<<interface>>\n+meow()\n+age Int\n}\n")
            val snap = s.finish()
            val laid = assertNotNull(snap.laidOut)
            val rects = laid.nodePositions
            // Build per-rect bounds for quick containment checks; allow tiny FP slack.
            val slack = 0.5f
            for (cmd in snap.drawCommands) {
                when (cmd) {
                    is com.hrm.diagram.core.draw.DrawCommand.StrokePath -> {
                        for (op in cmd.path.ops) {
                            val pt = when (op) {
                                is com.hrm.diagram.core.draw.PathOp.MoveTo -> op.p
                                is com.hrm.diagram.core.draw.PathOp.LineTo -> op.p
                                else -> null
                            } ?: continue
                            val owner = rects.values.firstOrNull { r ->
                                pt.x >= r.left - slack && pt.x <= r.right + slack &&
                                    pt.y >= r.top - slack && pt.y <= r.bottom + 4f
                            } ?: continue
                            assertTrue(
                                pt.y <= owner.bottom + slack,
                                "divider y=${pt.y} exceeds box bottom=${owner.bottom}",
                            )
                        }
                    }
                    else -> {}
                }
            }
        } finally { s.close() }
    }

    @Test fun relation_emits_cubic_bezier_path() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("classDiagram\nA --> B\n")
            val snap = s.finish()
            val cubic = snap.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.StrokePath>()
                .any { it.path.ops.any { op -> op is com.hrm.diagram.core.draw.PathOp.CubicTo } }
            assertTrue(cubic, "expected at least one CubicTo path op for the relation edge")
        } finally { s.close() }
    }

    @Test fun cssClass_overrides_box_fill_color() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("classDiagram\nclass Foo:::blue\nclass Bar\n")
            val snap = s.finish()
            val rects = snap.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.FillRect>()
            // Default amber fill (0xFFFFFDE7) should NOT be the only fill — blue palette
            // (0xFFE3F2FD) is expected from the Foo:::blue class box.
            val fills = rects.map { it.color.argb.toLong() and 0xFFFFFFFFL }.toSet()
            assertTrue(fills.contains(0xFFE3F2FDL), "expected blue palette fill (got: $fills)")
            assertTrue(fills.contains(0xFFFFFDE7L), "expected default fill for Bar (got: $fills)")
        } finally { s.close() }
    }

    @Test fun bulk_cssClass_assigns_palette_to_listed_classes() {
        val s = Diagram.session(language = SourceLanguage.MERMAID)
        try {
            s.append("classDiagram\nclass A\nclass B\ncssClass \"A,B\" green\n")
            val snap = s.finish()
            val fills = snap.drawCommands.filterIsInstance<com.hrm.diagram.core.draw.DrawCommand.FillRect>()
                .map { it.color.argb.toLong() and 0xFFFFFFFFL }.toSet()
            assertTrue(fills.contains(0xFFE8F5E9L), "expected green palette fill for both classes (got: $fills)")
        } finally { s.close() }
    }

    private fun run(src: String, chunkSize: Int) = Diagram.session(SourceLanguage.MERMAID).let { s ->
        try {
            var i = 0
            while (i < src.length) {
                val end = (i + chunkSize).coerceAtMost(src.length)
                s.append(src.substring(i, end))
                i = end
            }
            s.finish()
        } finally { s.close() }
    }
}
