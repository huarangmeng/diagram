package com.hrm.diagram.render.export

import com.hrm.diagram.core.draw.Color
import com.hrm.diagram.core.draw.Point
import com.hrm.diagram.core.draw.Rect
import com.hrm.diagram.core.draw.Size
import com.hrm.diagram.core.export.ExportBackground
import com.hrm.diagram.core.export.ExportScale
import com.hrm.diagram.core.export.SvgExportOptions
import com.hrm.diagram.core.ir.GraphIR
import com.hrm.diagram.core.ir.Node
import com.hrm.diagram.core.ir.NodeId
import com.hrm.diagram.core.ir.RichLabel
import com.hrm.diagram.core.ir.SequenceIR
import com.hrm.diagram.core.ir.SourceLanguage
import com.hrm.diagram.core.theme.DiagramTheme
import com.hrm.diagram.layout.LaidOutDiagram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LaidOutDiagramExportTest {
    @Test
    fun prepare_export_renders_graph_ir_with_theme_background() {
        val graph = GraphIR(
            nodes = listOf(Node(id = NodeId("a"), label = RichLabel.of("Alpha"))),
            sourceLanguage = SourceLanguage.DOT,
        )
        val laidOut = LaidOutDiagram(
            source = graph,
            nodePositions = mapOf(NodeId("a") to Rect(Point(20f, 16f), Size(120f, 48f))),
            edgeRoutes = emptyList(),
            bounds = Rect(Point.Zero, Size(180f, 96f)),
        )

        val rendered = laidOut.prepareExport(theme = DiagramTheme.Dark)

        assertEquals(laidOut.bounds, rendered.bounds)
        assertEquals(DiagramTheme.Dark.background, rendered.background)
        assertTrue(rendered.drawCommands.isNotEmpty(), "expected rendered graph commands")
    }

    @Test
    fun laid_out_to_svg_exports_graph_ir_frame() {
        val graph = GraphIR(
            nodes = listOf(Node(id = NodeId("a"), label = RichLabel.of("Alpha"))),
            sourceLanguage = SourceLanguage.DOT,
        )
        val laidOut = LaidOutDiagram(
            source = graph,
            nodePositions = mapOf(NodeId("a") to Rect(Point(20f, 16f), Size(120f, 48f))),
            edgeRoutes = emptyList(),
            bounds = Rect(Point.Zero, Size(180f, 96f)),
        )

        val svg = laidOut.toSvg(
            theme = DiagramTheme.Default,
            options = SvgExportOptions(
                scale = ExportScale.Width(360),
                background = ExportBackground.Transparent,
                includeXmlDeclaration = false,
            ),
        )

        assertTrue(svg.startsWith("<svg "), svg)
        assertTrue("Alpha" in svg, svg)
        assertTrue("width=\"360\"" in svg, svg)
        assertTrue("viewBox=\"0 0 180 96\"" in svg, svg)
    }

    @Test
    fun prepare_export_for_non_graph_ir_keeps_bounds_and_has_no_commands() {
        val sequence = SequenceIR(
            participants = emptyList(),
            sourceLanguage = SourceLanguage.MERMAID,
        )
        val laidOut = LaidOutDiagram(
            source = sequence,
            nodePositions = emptyMap(),
            edgeRoutes = emptyList(),
            bounds = Rect(Point.Zero, Size(200f, 120f)),
        )

        val rendered = laidOut.prepareExport(
            theme = DiagramTheme.Dark,
            background = ExportBackground.Solid(Color.White),
        )

        assertEquals(laidOut.bounds, rendered.bounds)
        assertEquals(Color.White, rendered.background)
        assertTrue(rendered.drawCommands.isEmpty(), "non-graph families should not fake one-shot export yet")
    }
}
